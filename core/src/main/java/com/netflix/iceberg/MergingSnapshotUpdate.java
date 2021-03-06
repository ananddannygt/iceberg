/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.iceberg;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.netflix.iceberg.ManifestEntry.Status;
import com.netflix.iceberg.exceptions.RuntimeIOException;
import com.netflix.iceberg.exceptions.ValidationException;
import com.netflix.iceberg.expressions.Evaluator;
import com.netflix.iceberg.expressions.Expression;
import com.netflix.iceberg.expressions.Expressions;
import com.netflix.iceberg.expressions.Projections;
import com.netflix.iceberg.expressions.StrictMetricsEvaluator;
import com.netflix.iceberg.io.OutputFile;
import com.netflix.iceberg.util.BinPacking.ListPacker;
import com.netflix.iceberg.util.CharSequenceWrapper;
import com.netflix.iceberg.util.StructLikeWrapper;
import com.netflix.iceberg.util.Tasks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Iterables.transform;
import static com.netflix.iceberg.TableProperties.MANIFEST_MIN_MERGE_COUNT;
import static com.netflix.iceberg.TableProperties.MANIFEST_MIN_MERGE_COUNT_DEFAULT;
import static com.netflix.iceberg.TableProperties.MANIFEST_TARGET_SIZE_BYTES;
import static com.netflix.iceberg.TableProperties.MANIFEST_TARGET_SIZE_BYTES_DEFAULT;
import static com.netflix.iceberg.util.ThreadPools.getWorkerPool;

abstract class MergingSnapshotUpdate extends SnapshotUpdate {
  private final Logger LOG = LoggerFactory.getLogger(getClass());

  private static final Joiner COMMA = Joiner.on(",");

  protected static class DeleteException extends ValidationException {
    private final String partition;

    private DeleteException(String partition) {
      super("Operation would delete existing data");
      this.partition = partition;
    }

    public String partition() {
      return partition;
    }
  }

  private final TableOperations ops;
  private final PartitionSpec spec;
  private final long manifestTargetSizeBytes;
  private final int minManifestsCountToMerge;

  // update data
  private final AtomicInteger manifestCount = new AtomicInteger(0);
  private final List<DataFile> newFiles = Lists.newArrayList();
  private final Set<CharSequenceWrapper> deletePaths = Sets.newHashSet();
  private final Set<StructLikeWrapper> dropPartitions = Sets.newHashSet();
  private Expression deleteExpression = Expressions.alwaysFalse();
  private boolean failAnyDelete = false;
  private boolean failMissingDeletePaths = false;

  // cache the new manifest once it is written
  private ManifestFile newManifest = null;
  private boolean hasNewFiles = false;

  // cache merge results to reuse when retrying
  private final Map<List<ManifestFile>, ManifestFile> mergeManifests = Maps.newConcurrentMap();

  // cache filtered manifests to avoid extra work when commits fail.
  private final Map<ManifestFile, ManifestFile> filteredManifests = Maps.newConcurrentMap();

  // tracking where files were deleted to validate retries quickly
  private final Map<ManifestFile, Set<CharSequenceWrapper>> filteredManifestToDeletedFiles =
      Maps.newConcurrentMap();

  private boolean filterUpdated = false; // used to clear caches of filtered and merged manifests

  MergingSnapshotUpdate(TableOperations ops) {
    super(ops);
    this.ops = ops;
    this.spec = ops.current().spec();
    this.manifestTargetSizeBytes = ops.current()
        .propertyAsLong(MANIFEST_TARGET_SIZE_BYTES, MANIFEST_TARGET_SIZE_BYTES_DEFAULT);
    this.minManifestsCountToMerge = ops.current()
        .propertyAsInt(MANIFEST_MIN_MERGE_COUNT, MANIFEST_MIN_MERGE_COUNT_DEFAULT);
  }

  protected PartitionSpec writeSpec() {
    // the spec is set when the write is started
    return spec;
  }

  protected Expression rowFilter() {
    return deleteExpression;
  }

  protected List<DataFile> addedFiles() {
    return newFiles;
  }

  protected void failAnyDelete() {
    this.failAnyDelete = true;
  }

  protected void failMissingDeletePaths() {
    this.failMissingDeletePaths = true;
  }

  /**
   * Add a filter to match files to delete. A file will be deleted if all of the rows it contains
   * match this or any other filter passed to this method.
   *
   * @param expr an expression to match rows.
   */
  protected void deleteByRowFilter(Expression expr) {
    Preconditions.checkNotNull(expr, "Cannot delete files using filter: null");
    this.filterUpdated = true;
    this.deleteExpression = Expressions.or(deleteExpression, expr);
  }

  /**
   * Add a partition tuple to drop from the table during the delete phase.
   */
  protected void dropPartition(StructLike partition) {
    dropPartitions.add(StructLikeWrapper.wrap(partition));
  }

  /**
   * Add a specific path to be deleted in the new snapshot.
   */
  protected void delete(CharSequence path) {
    Preconditions.checkNotNull(path, "Cannot delete file path: null");
    this.filterUpdated = true;
    deletePaths.add(CharSequenceWrapper.wrap(path));
  }

  /**
   * Add a file to the new snapshot.
   */
  protected void add(DataFile file) {
    hasNewFiles = true;
    newFiles.add(file);
  }

  @Override
  public List<ManifestFile> apply(TableMetadata base) {
    if (filterUpdated) {
      cleanUncommittedFilters(SnapshotUpdate.EMPTY_SET);
      this.filterUpdated = false;
    }

    Snapshot current = base.currentSnapshot();
    Map<Integer, List<ManifestFile>> groups = Maps.newTreeMap(Comparator.<Integer>reverseOrder());

    // use a common metrics evaluator for all manifests because it is bound to the table schema
    StrictMetricsEvaluator metricsEvaluator = new StrictMetricsEvaluator(
        ops.current().schema(), deleteExpression);

    // add the current spec as the first group. files are added to the beginning.
    try {
      if (newFiles.size() > 0) {
        ManifestFile newManifest = newFilesAsManifest();
        List<ManifestFile> manifestGroup = Lists.newArrayList();
        manifestGroup.add(newManifest);
        groups.put(newManifest.partitionSpecId(), manifestGroup);
      }

      Set<CharSequenceWrapper> deletedFiles = Sets.newHashSet();

      // group manifests by compatible partition specs to be merged
      if (current != null) {
        List<ManifestFile> manifests = current.manifests();
        ManifestFile[] filtered = new ManifestFile[manifests.size()];
        // open all of the manifest files in parallel, use index to avoid reordering
        Tasks.range(filtered.length)
            .stopOnFailure().throwFailureWhenFinished()
            .executeWith(getWorkerPool())
            .run(index -> {
              ManifestFile manifest = filterManifest(
                  deleteExpression, metricsEvaluator,
                  manifests.get(index));
              filtered[index] = manifest;
            }, IOException.class);

        for (ManifestFile manifest : filtered) {
          Set<CharSequenceWrapper> manifestDeletes = filteredManifestToDeletedFiles.get(manifest);
          if (manifestDeletes != null) {
            deletedFiles.addAll(manifestDeletes);
          }

          List<ManifestFile> group = groups.get(manifest.partitionSpecId());
          if (group != null) {
            group.add(manifest);
          } else {
            group = Lists.newArrayList();
            group.add(manifest);
            groups.put(manifest.partitionSpecId(), group);
          }
        }
      }

      List<ManifestFile> manifests = Lists.newArrayList();
      for (Map.Entry<Integer, List<ManifestFile>> entry : groups.entrySet()) {
        for (ManifestFile manifest : mergeGroup(entry.getKey(), entry.getValue())) {
          manifests.add(manifest);
        }
      }

      ValidationException.check(!failMissingDeletePaths || deletedFiles.containsAll(deletePaths),
          "Missing required files to delete: %s",
          COMMA.join(transform(filter(deletePaths,
              path -> !deletedFiles.contains(path)),
              CharSequenceWrapper::get)));

      return manifests;

    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to create snapshot manifest list");
    }
  }

  private void cleanUncommittedMerges(Set<ManifestFile> committed) {
    // iterate over a copy of entries to avoid concurrent modification
    List<Map.Entry<List<ManifestFile>, ManifestFile>> entries =
        Lists.newArrayList(mergeManifests.entrySet());

    for (Map.Entry<List<ManifestFile>, ManifestFile> entry : entries) {
      // delete any new merged manifests that aren't in the committed list
      ManifestFile merged = entry.getValue();
      if (!committed.contains(merged)) {
        deleteFile(merged.path());
        // remove the deleted file from the cache
        mergeManifests.remove(entry.getKey());
      }
    }
  }

  private void cleanUncommittedFilters(Set<ManifestFile> committed) {
    // iterate over a copy of entries to avoid concurrent modification
    List<Map.Entry<ManifestFile, ManifestFile>> filterEntries =
        Lists.newArrayList(filteredManifests.entrySet());

    for (Map.Entry<ManifestFile, ManifestFile> entry : filterEntries) {
      // remove any new filtered manifests that aren't in the committed list
      ManifestFile manifest = entry.getKey();
      ManifestFile filtered = entry.getValue();
      if (!committed.contains(filtered)) {
        // only delete if the filtered copy was created
        if (!manifest.equals(filtered)) {
          deleteFile(filtered.path());
        }

        // remove the entry from the cache
        filteredManifests.remove(manifest);
      }
    }
  }

  @Override
  protected void cleanUncommitted(Set<ManifestFile> committed) {
    if (newManifest != null && !committed.contains(newManifest)) {
      deleteFile(newManifest.path());
      this.newManifest = null;
    }
    cleanUncommittedMerges(committed);
    cleanUncommittedFilters(committed);
  }

  private boolean nothingToFilter() {
    return (deleteExpression == null || deleteExpression == Expressions.alwaysFalse()) &&
        deletePaths.isEmpty() && dropPartitions.isEmpty();
  }

  /**
   * @return a ManifestReader that is a filtered version of the input manifest.
   */
  private ManifestFile filterManifest(Expression deleteExpression,
                                        StrictMetricsEvaluator metricsEvaluator,
                                        ManifestFile manifest) throws IOException {
    ManifestFile cached = filteredManifests.get(manifest);
    if (cached != null) {
      return cached;
    }

    if (nothingToFilter()) {
      filteredManifests.put(manifest, manifest);
      return manifest;
    }

    try (ManifestReader reader = ManifestReader.read(ops.io().newInputFile(manifest.path()))) {
      Expression inclusiveExpr = Projections
          .inclusive(reader.spec())
          .project(deleteExpression);
      Evaluator inclusive = new Evaluator(reader.spec().partitionType(), inclusiveExpr);

      Expression strictExpr = Projections
          .strict(reader.spec())
          .project(deleteExpression);
      Evaluator strict = new Evaluator(reader.spec().partitionType(), strictExpr);

      // this is reused to compare file paths with the delete set
      CharSequenceWrapper pathWrapper = CharSequenceWrapper.wrap("");

      // reused to compare file partitions with the drop set
      StructLikeWrapper partitionWrapper = StructLikeWrapper.wrap(null);

      // this assumes that the manifest doesn't have files to remove and streams through the
      // manifest without copying data. if a manifest does have a file to remove, this will break
      // out of the loop and move on to filtering the manifest.
      boolean hasDeletedFiles = false;
      for (ManifestEntry entry : reader.entries()) {
        DataFile file = entry.file();
        boolean fileDelete = (deletePaths.contains(pathWrapper.set(file.path())) ||
            dropPartitions.contains(partitionWrapper.set(file.partition())));
        if (fileDelete || inclusive.eval(file.partition())) {
          ValidationException.check(
              fileDelete || strict.eval(file.partition()) || metricsEvaluator.eval(file),
              "Cannot delete file where some, but not all, rows match filter %s: %s",
              this.deleteExpression, file.path());

          hasDeletedFiles = true;
          if (failAnyDelete) {
            throw new DeleteException(writeSpec().partitionToPath(file.partition()));
          }
          break; // as soon as a deleted file is detected, stop scanning
        }
      }

      if (!hasDeletedFiles) {
        filteredManifests.put(manifest, manifest);
        return manifest;
      }

      // when this point is reached, there is at least one file that will be deleted in the
      // manifest. produce a copy of the manifest with all deleted files removed.
      Set<CharSequenceWrapper> deletedPaths = Sets.newHashSet();
      OutputFile filteredCopy = manifestPath(manifestCount.getAndIncrement());
      ManifestWriter writer = new ManifestWriter(reader.spec(), filteredCopy, snapshotId());
      try {
        for (ManifestEntry entry : reader.entries()) {
          DataFile file = entry.file();
          boolean fileDelete = (deletePaths.contains(pathWrapper.set(file.path())) ||
              dropPartitions.contains(partitionWrapper.set(file.partition())));
          if (entry.status() != Status.DELETED) {
            if (fileDelete || inclusive.eval(file.partition())) {
              ValidationException.check(
                  fileDelete || strict.eval(file.partition()) || metricsEvaluator.eval(file),
                  "Cannot delete file where some, but not all, rows match filter %s: %s",
                  this.deleteExpression, file.path());

              writer.delete(entry);

              CharSequenceWrapper wrapper = CharSequenceWrapper.wrap(entry.file().path());
              if (deletedPaths.contains(wrapper)) {
                LOG.warn("Deleting a duplicate path from manifest {}: {}",
                    manifest.path(), wrapper.get());
              }
              deletedPaths.add(wrapper);

            } else {
              writer.addExisting(entry);
            }
          }
        }
      } finally {
        writer.close();
      }

      // return the filtered manifest as a reader
      ManifestFile filtered = writer.toManifestFile();

      // update caches
      filteredManifests.put(manifest, filtered);
      filteredManifestToDeletedFiles.put(filtered, deletedPaths);

      return filtered;
    }
  }

  @SuppressWarnings("unchecked")
  private Iterable<ManifestFile> mergeGroup(int specId, List<ManifestFile> group)
      throws IOException {
    // use a lookback of 1 to avoid reordering the manifests. using 1 also means this should pack
    // from the end so that the manifest that gets under-filled is the first one, which will be
    // merged the next time.
    ListPacker<ManifestFile> packer = new ListPacker<>(manifestTargetSizeBytes, 1);
    List<List<ManifestFile>> bins = packer.packEnd(group, manifest -> manifest.length());

    // process bins in parallel, but put results in the order of the bins into an array to preserve
    // the order of manifests and contents. preserving the order helps avoid random deletes when
    // data files are eventually aged off.
    List<ManifestFile>[] binResults = (List<ManifestFile>[])
        Array.newInstance(List.class, bins.size());
    Tasks.range(bins.size())
        .stopOnFailure().throwFailureWhenFinished()
        .executeWith(getWorkerPool())
        .run(index -> {
          List<ManifestFile> bin = bins.get(index);
          List<ManifestFile> outputManifests = Lists.newArrayList();
          binResults[index] = outputManifests;

          if (bin.size() == 1) {
            // no need to rewrite
            outputManifests.add(bin.get(0));
            return;
          }

          // if the bin has a new manifest (the new data files) then only merge it if the number of
          // manifests is above the minimum count. this is applied only to bins with an in-memory
          // manifest so that large manifests don't prevent merging older groups.
          if (bin.contains(newManifest) && bin.size() < minManifestsCountToMerge) {
            // not enough to merge, add all manifest files to the output list
            outputManifests.addAll(bin);
          } else {
            // merge the group
            outputManifests.add(createManifest(specId, bin));
          }
        }, IOException.class);

    return Iterables.concat(binResults);
  }

  private ManifestFile createManifest(int specId, List<ManifestFile> bin) throws IOException {
    // if this merge was already rewritten, use the existing file.
    // if the new files are in this merge, then the ManifestFile for the new files has changed and
    // will be a cache miss.
    if (mergeManifests.containsKey(bin)) {
      return mergeManifests.get(bin);
    }

    OutputFile out = manifestPath(manifestCount.getAndIncrement());

    ManifestWriter writer = new ManifestWriter(ops.current().spec(specId), out, snapshotId());
    try {

      for (ManifestFile manifest : bin) {
        try (ManifestReader reader = ManifestReader.read(ops.io().newInputFile(manifest.path()))) {
          for (ManifestEntry entry : reader.entries()) {
            if (entry.status() == Status.DELETED) {
              // suppress deletes from previous snapshots. only files deleted by this snapshot
              // should be added to the new manifest
              if (entry.snapshotId() == snapshotId()) {
                writer.add(entry);
              }
            } else if (entry.status() == Status.ADDED && entry.snapshotId() == snapshotId()) {
              // adds from this snapshot are still adds, otherwise they should be existing
              writer.add(entry);
            } else {
              // add all files from the old manifest as existing files
              writer.addExisting(entry);
            }
          }
        }
      }

    } finally {
      writer.close();
    }

    ManifestFile manifest = writer.toManifestFile();

    // update the cache
    mergeManifests.put(bin, manifest);

    return manifest;
  }

  private ManifestFile newFilesAsManifest() throws IOException {
    if (hasNewFiles && newManifest != null) {
      deleteFile(newManifest.path());
      newManifest = null;
    }

    if (newManifest == null) {
      OutputFile out = manifestPath(manifestCount.getAndIncrement());

      ManifestWriter writer = new ManifestWriter(spec, out, snapshotId());
      try {
        writer.addAll(newFiles);
      } finally {
        writer.close();
      }

      this.newManifest = writer.toManifestFile();
      this.hasNewFiles = false;
    }

    return newManifest;
  }
}
