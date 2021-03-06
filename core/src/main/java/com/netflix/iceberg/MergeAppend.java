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

import com.netflix.iceberg.exceptions.CommitFailedException;

/**
 * Append implementation that produces a minimal number of manifest files.
 * <p>
 * This implementation will attempt to commit 5 times before throwing {@link CommitFailedException}.
 */
class MergeAppend extends MergingSnapshotUpdate implements AppendFiles {
  MergeAppend(TableOperations ops) {
    super(ops);
  }

  @Override
  public MergeAppend appendFile(DataFile file) {
    add(file);
    return this;
  }
}
