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

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.netflix.iceberg.expressions.Evaluator;
import com.netflix.iceberg.expressions.Expression;
import com.netflix.iceberg.expressions.Expressions;
import com.netflix.iceberg.expressions.InclusiveManifestEvaluator;
import com.netflix.iceberg.io.CloseableIterable;
import com.netflix.iceberg.types.Types;
import java.io.Closeable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

class ManifestGroup {
  private static final Types.StructType EMPTY_STRUCT = Types.StructType.of();

  private final TableOperations ops;
  private final Set<ManifestFile> manifests;
  private final Expression dataFilter;
  private final Expression fileFilter;
  private final boolean ignoreDeleted;
  private final boolean ignoreExisting;
  private final List<String> columns;

  private final LoadingCache<Integer, InclusiveManifestEvaluator> EVAL_CACHE = CacheBuilder
      .newBuilder()
      .build(new CacheLoader<Integer, InclusiveManifestEvaluator>() {
        @Override
        public InclusiveManifestEvaluator load(Integer specId) {
          PartitionSpec spec = ops.current().spec(specId);
          return new InclusiveManifestEvaluator(spec, dataFilter);
        }
      });

  ManifestGroup(TableOperations ops, Iterable<ManifestFile> manifests) {
    this(ops, Sets.newHashSet(manifests), Expressions.alwaysTrue(), Expressions.alwaysTrue(),
        false, false, ImmutableList.of("*"));
  }

  private ManifestGroup(TableOperations ops, Set<ManifestFile> manifests,
                        Expression dataFilter, Expression fileFilter,
                        boolean ignoreDeleted, boolean ignoreExisting, List<String> columns) {
    this.ops = ops;
    this.manifests = manifests;
    this.dataFilter = dataFilter;
    this.fileFilter = fileFilter;
    this.ignoreDeleted = ignoreDeleted;
    this.ignoreExisting = ignoreExisting;
    this.columns = columns;
  }

  public ManifestGroup filterData(Expression expr) {
    return new ManifestGroup(
        ops, manifests, Expressions.and(dataFilter, expr), fileFilter, ignoreDeleted,
        ignoreExisting, columns);
  }

  public ManifestGroup filterFiles(Expression expr) {
    return new ManifestGroup(
        ops, manifests, dataFilter, Expressions.and(fileFilter, expr), ignoreDeleted,
        ignoreExisting, columns);
  }

  public ManifestGroup ignoreDeleted() {
    return new ManifestGroup(ops, manifests, dataFilter, fileFilter, true, ignoreExisting, columns);
  }

  public ManifestGroup ignoreDeleted(boolean ignoreDeleted) {
    return new ManifestGroup(ops, manifests, dataFilter, fileFilter, ignoreDeleted, ignoreExisting,
        columns);
  }

  public ManifestGroup ignoreExisting() {
    return new ManifestGroup(ops, manifests, dataFilter, fileFilter, ignoreDeleted, true, columns);
  }

  public ManifestGroup ignoreExisting(boolean ignoreExisting) {
    return new ManifestGroup(ops, manifests, dataFilter, fileFilter, ignoreDeleted, ignoreExisting,
        columns);
  }

  public ManifestGroup select(List<String> columns) {
    return new ManifestGroup(
        ops, manifests, dataFilter, fileFilter, ignoreDeleted, ignoreExisting,
        Lists.newArrayList(columns));
  }

  public ManifestGroup select(String... columns) {
    return select(Arrays.asList(columns));
  }

  /**
   * Returns an iterable for manifest entries in the set of manifests.
   * <p>
   * Entries are not copied and it is the caller's responsibility to make defensive copies if
   * adding these entries to a collection.
   *
   * @return a CloseableIterable of manifest entries.
   */
  public CloseableIterable<ManifestEntry> entries() {
    Evaluator evaluator = new Evaluator(DataFile.getType(EMPTY_STRUCT), fileFilter);
    List<Closeable> toClose = Lists.newArrayList();

    Iterable<ManifestFile> matchingManifests = Iterables.filter(manifests,
        manifest -> EVAL_CACHE.getUnchecked(manifest.partitionSpecId()).eval(manifest));

    if (ignoreDeleted) {
      // only scan manifests that have entries other than deletes
      // remove any manifests that don't have any existing or added files. if either the added or
      // existing files count is missing, the manifest must be scanned.
      matchingManifests = Iterables.filter(manifests, manifest ->
          manifest.addedFilesCount() == null || manifest.existingFilesCount() == null ||
              manifest.addedFilesCount() + manifest.existingFilesCount() > 0);
    }

    if (ignoreExisting) {
      // only scan manifests that have entries other than existing
      // remove any manifests that don't have any deleted or added files. if either the added or
      // deleted files count is missing, the manifest must be scanned.
      matchingManifests = Iterables.filter(manifests, manifest ->
          manifest.addedFilesCount() == null || manifest.deletedFilesCount() == null ||
              manifest.addedFilesCount() + manifest.deletedFilesCount() > 0);
    }

    Iterable<Iterable<ManifestEntry>> readers = Iterables.transform(
        matchingManifests,
        manifest -> {
          ManifestReader reader = ManifestReader.read(ops.io().newInputFile(manifest.path()));
          FilteredManifest filtered = reader.filterRows(dataFilter).select(columns);
          toClose.add(reader);

          Iterable<ManifestEntry> entries = filtered.allEntries();
          if (ignoreDeleted) {
            entries = filtered.liveEntries();
          }

          if (ignoreExisting) {
            entries = Iterables.filter(entries,
                entry -> entry.status() != ManifestEntry.Status.EXISTING);
          }

          if (fileFilter != null && fileFilter != Expressions.alwaysTrue()) {
            entries = Iterables.filter(entries,
                entry -> evaluator.eval((GenericDataFile) entry.file()));
          }

          return entries;
        });

    return CloseableIterable.combine(Iterables.concat(readers), toClose);
  }
}
