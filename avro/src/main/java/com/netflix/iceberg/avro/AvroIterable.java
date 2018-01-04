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

package com.netflix.iceberg.avro;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.netflix.iceberg.exceptions.RuntimeIOException;
import com.netflix.iceberg.io.InputFile;
import org.apache.avro.Schema;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.file.FileReader;
import org.apache.avro.io.DatumReader;
import java.io.Closeable;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class AvroIterable<D> implements Iterable<D>, Closeable {
  private final List<Closeable> closeables = Lists.newArrayList();
  private final InputFile file;
  private final DatumReader<D> reader;
  private final Long start;
  private final Long end;
  private final boolean reuseContainers;
  private Map<String, String> metadata = null;

  AvroIterable(InputFile file, DatumReader<D> reader,
               Long start, Long length, boolean reuseContainers) {
    this.file = file;
    this.reader = reader;
    this.start = start;
    this.end = start != null ? start + length : null;
    this.reuseContainers = reuseContainers;
  }

  private DataFileReader<D> initMetadata(DataFileReader<D> reader) {
    if (metadata == null) {
      this.metadata = Maps.newHashMap();
      for (String key : reader.getMetaKeys()) {
        metadata.put(key, reader.getMetaString(key));
      }
    }
    return reader;
  }

  public Map<String, String> getMetadata() {
    if (metadata == null) {
      try (DataFileReader<D> reader = newFileReader()) {
        initMetadata(reader);
      } catch (IOException e) {
        throw new RuntimeIOException(e, "Failed to read metadata for file: %s", file);
      }
    }
    return metadata;
  }

  @Override
  public Iterator<D> iterator() {
    FileReader<D> reader = initMetadata(newFileReader());

    if (start != null) {
      reader = new AvroRangeIterator<>(reader, start, end);
    }

    if (reuseContainers) {
      return new AvroReuseIterator<>(reader);
    }

    closeables.add(reader);

    return reader;
  }

  private DataFileReader<D> newFileReader() {
    try {
      return (DataFileReader<D>) DataFileReader.openReader(
          AvroIO.stream(file.newStream(), file.getLength()), reader);
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to open file: %s", file);
    }
  }

  @Override
  public void close() throws IOException {
    while (!closeables.isEmpty()) {
      Closeable toClose = closeables.remove(0);
      if (toClose != null) {
        toClose.close();
      }
    }
  }

  private static class AvroRangeIterator<D> implements FileReader<D> {
    private final FileReader<D> reader;
    private final long end;

    AvroRangeIterator(FileReader<D> reader, long start, long end) {
      this.reader = reader;
      this.end = end;

      try {
        reader.sync(start);
      } catch (IOException e) {
        throw new RuntimeIOException(e, "Failed to find sync past position %d", start);
      }
    }

    @Override
    public Schema getSchema() {
      return reader.getSchema();
    }

    @Override
    public boolean hasNext() {
      try {
        return (reader.hasNext() && !reader.pastSync(end));
      } catch (IOException e) {
        throw new RuntimeIOException(e, "Failed to check range end: %d", end);
      }
    }

    @Override
    public D next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return reader.next();
    }

    @Override
    public D next(D reuse) {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      try {
        return reader.next(reuse);
      } catch (IOException e) {
        throw new RuntimeIOException(e, "Failed to read next record");
      }
    }

    @Override
    public void sync(long position) throws IOException {
      reader.sync(position);
    }

    @Override
    public boolean pastSync(long position) throws IOException {
      return reader.pastSync(position);
    }

    @Override
    public long tell() throws IOException {
      return reader.tell();
    }

    @Override
    public void close() throws IOException {
      reader.close();
    }

    @Override
    public Iterator<D> iterator() {
      return this;
    }
  }

  private static class AvroReuseIterator<D> implements Iterator<D>, Closeable {
    private final FileReader<D> reader;
    private D reused = null;

    AvroReuseIterator(FileReader<D> reader) {
      this.reader = reader;
    }

    @Override
    public boolean hasNext() {
      return reader.hasNext();
    }

    @Override
    public D next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }

      try {
        this.reused = reader.next(reused);
        return reused;
      } catch (IOException e) {
        throw new RuntimeIOException(e, "Failed to read next record");
      }
    }

    @Override
    public void close() throws IOException {
      reader.close();
    }
  }
}