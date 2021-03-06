/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.util.List;
import java.util.Set;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestIncrementalDataTableScan extends TableTestBase {

  @Before
  public void setupTableProperties() {
    table.updateProperties().set(TableProperties.MANIFEST_MIN_MERGE_COUNT, "2").commit();
  }

  @Test
  public void testInvalidScans() {
    add(table.newAppend(), files("A"));
    AssertHelpers.assertThrows(
        "from and to snapshots cannot be the same, since from snapshot is exclusive and not part of the scan",
        IllegalArgumentException.class, "fromSnapshotId and toSnapshotId cannot be the same",
        () -> appendsBetweenScan(1, 1));
  }

  @Test
  public void testAppends() {
    add(table.newAppend(), files("A")); // 1
    add(table.newAppend(), files("B"));
    add(table.newAppend(), files("C"));
    add(table.newAppend(), files("D"));
    add(table.newAppend(), files("E")); // 5
    Assert.assertEquals(Sets.newHashSet("B", "C", "D", "E"), appendsBetweenScan(1, 5));
    Assert.assertEquals(Sets.newHashSet("C", "D", "E"), appendsBetweenScan(2, 5));
  }

  @Test
  public void testReplaceOverwritesDeletes() {
    add(table.newAppend(), files("A")); // 1
    add(table.newAppend(), files("B"));
    add(table.newAppend(), files("C"));
    add(table.newAppend(), files("D"));
    add(table.newAppend(), files("E")); // 5
    Assert.assertEquals(Sets.newHashSet("B", "C", "D", "E"), appendsBetweenScan(1, 5));

    replace(table.newRewrite(), files("A", "B", "C"), files("F", "G")); // 6
    Assert.assertEquals("Replace commits are ignored", Sets.newHashSet("B", "C", "D", "E"), appendsBetweenScan(1, 6));
    Assert.assertEquals(Sets.newHashSet("E"), appendsBetweenScan(4, 6));
    // 6th snapshot is a replace. No new content is added
    Assert.assertTrue("Replace commits are ignored", appendsBetweenScan(5, 6).isEmpty());
    delete(table.newDelete(), files("D")); // 7
    // 7th snapshot is a delete.
    Assert.assertTrue("Replace and delete commits are ignored", appendsBetweenScan(5, 7).isEmpty());
    Assert.assertTrue("Delete commits are ignored", appendsBetweenScan(6, 7).isEmpty());
    add(table.newAppend(), files("I")); // 8
    // snapshots 6 and 7 are ignored
    Assert.assertEquals(Sets.newHashSet("B", "C", "D", "E", "I"), appendsBetweenScan(1, 8));
    Assert.assertEquals(Sets.newHashSet("I"), appendsBetweenScan(6, 8));
    Assert.assertEquals(Sets.newHashSet("I"), appendsBetweenScan(7, 8));

    overwrite(table.newOverwrite(), files("H"), files("E")); // 9
    AssertHelpers.assertThrows(
        "Overwrites are not supported for Incremental scan", UnsupportedOperationException.class,
        "Found overwrite operation, cannot support incremental data in snapshots (8, 9]",
        () -> appendsBetweenScan(8, 9));
  }

  @Test
  public void testTransactions() {
    Transaction transaction = table.newTransaction();

    add(transaction.newAppend(), files("A")); // 1
    add(transaction.newAppend(), files("B"));
    add(transaction.newAppend(), files("C"));
    add(transaction.newAppend(), files("D"));
    add(transaction.newAppend(), files("E")); // 5
    transaction.commitTransaction();
    Assert.assertEquals(Sets.newHashSet("B", "C", "D", "E"), appendsBetweenScan(1, 5));

    transaction = table.newTransaction();
    replace(transaction.newRewrite(), files("A", "B", "C"), files("F", "G")); // 6
    transaction.commitTransaction();
    Assert.assertEquals("Replace commits are ignored", Sets.newHashSet("B", "C", "D", "E"), appendsBetweenScan(1, 6));
    Assert.assertEquals(Sets.newHashSet("E"), appendsBetweenScan(4, 6));
    // 6th snapshot is a replace. No new content is added
    Assert.assertTrue("Replace commits are ignored", appendsBetweenScan(5, 6).isEmpty());

    transaction = table.newTransaction();
    delete(transaction.newDelete(), files("D")); // 7
    transaction.commitTransaction();
    // 7th snapshot is a delete.
    Assert.assertTrue("Replace and delete commits are ignored", appendsBetweenScan(5, 7).isEmpty());
    Assert.assertTrue("Delete commits are ignored", appendsBetweenScan(6, 7).isEmpty());

    transaction = table.newTransaction();
    add(transaction.newAppend(), files("I")); // 8
    transaction.commitTransaction();
    // snapshots 6, 7 and 8 are ignored
    Assert.assertEquals(Sets.newHashSet("B", "C", "D", "E", "I"), appendsBetweenScan(1, 8));
    Assert.assertEquals(Sets.newHashSet("I"), appendsBetweenScan(6, 8));
    Assert.assertEquals(Sets.newHashSet("I"), appendsBetweenScan(7, 8));
  }

  @Test
  public void testRollbacks() {
    add(table.newAppend(), files("A")); // 1
    add(table.newAppend(), files("B"));
    add(table.newAppend(), files("C")); // 3
    // Go back to snapshot "B"
    table.rollback().toSnapshotId(2).commit(); // 2
    Assert.assertEquals(2, table.currentSnapshot().snapshotId());
    Assert.assertEquals(Sets.newHashSet("B"), appendsBetweenScan(1, 2));
    Assert.assertEquals(Sets.newHashSet("B"), appendsAfterScan(1));

    Transaction transaction = table.newTransaction();
    add(transaction.newAppend(), files("D")); // 4
    add(transaction.newAppend(), files("E")); // 5
    add(transaction.newAppend(), files("F"));
    transaction.commitTransaction();
    // Go back to snapshot "E"
    table.rollback().toSnapshotId(5).commit();
    Assert.assertEquals(5, table.currentSnapshot().snapshotId());
    Assert.assertEquals(Sets.newHashSet("B", "D", "E"), appendsBetweenScan(1, 5));
    Assert.assertEquals(Sets.newHashSet("B", "D", "E"), appendsAfterScan(1));
  }

  private static DataFile file(String name) {
    return DataFiles.builder(SPEC)
            .withPath(name + ".parquet")
            .withFileSizeInBytes(0)
            .withPartitionPath("data_bucket=0") // easy way to set partition data for now
            .withRecordCount(1)
            .build();
  }

  private static void add(AppendFiles appendFiles, List<DataFile> adds) {
    for (DataFile f : adds) {
      appendFiles.appendFile(f);
    }
    appendFiles.commit();
  }

  private static void delete(DeleteFiles deleteFiles, List<DataFile> deletes) {
    for (DataFile f : deletes) {
      deleteFiles.deleteFile(f);
    }
    deleteFiles.commit();
  }

  private static void replace(RewriteFiles rewriteFiles, List<DataFile> deletes, List<DataFile> adds) {
    rewriteFiles.rewriteFiles(Sets.newHashSet(deletes), Sets.newHashSet(adds));
    rewriteFiles.commit();
  }

  private static void overwrite(OverwriteFiles overwriteFiles, List<DataFile> adds, List<DataFile> deletes) {
    for (DataFile f : adds) {
      overwriteFiles.addFile(f);
    }
    for (DataFile f : deletes) {
      overwriteFiles.deleteFile(f);
    }
    overwriteFiles.commit();
  }

  private static List<DataFile> files(String... names) {
    return Lists.transform(Lists.newArrayList(names), TestIncrementalDataTableScan::file);
  }

  private Set<String> appendsAfterScan(long fromSnapshotId) {
    final TableScan appendsAfter = table.newScan().appendsAfter(fromSnapshotId);
    return filesToScan(appendsAfter);
  }

  private Set<String> appendsBetweenScan(long fromSnapshotId, long toSnapshotId) {
    Snapshot s1 = table.snapshot(fromSnapshotId);
    Snapshot s2 = table.snapshot(toSnapshotId);
    TableScan appendsBetween = table.newScan().appendsBetween(s1.snapshotId(), s2.snapshotId());
    return filesToScan(appendsBetween);
  }

  private static Set<String> filesToScan(TableScan tableScan) {
    Iterable<String> filesToRead = Iterables.transform(tableScan.planFiles(), t -> {
      String path = t.file().path().toString();
      return path.split("\\.")[0];
    });
    return Sets.newHashSet(filesToRead);
  }
}
