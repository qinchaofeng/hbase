/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.regionserver;

import static org.apache.hadoop.hbase.regionserver.TestRegionServerNoMaster.*;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.TestMetaTableAccessor;
import org.apache.hadoop.hbase.client.Consistency;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.RequestConverter;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.util.StringUtils;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import com.google.protobuf.ServiceException;

/**
 * Tests for region replicas. Sad that we cannot isolate these without bringing up a whole
 * cluster. See {@link TestRegionServerNoMaster}.
 */
@Category(MediumTests.class)
public class TestRegionReplicas {
  private static final Log LOG = LogFactory.getLog(TestRegionReplicas.class);

  private static final int NB_SERVERS = 1;
  private static HTable table;
  private static final byte[] row = "TestRegionReplicas".getBytes();

  private static HRegionInfo hriPrimary;
  private static HRegionInfo hriSecondary;

  private static final HBaseTestingUtility HTU = new HBaseTestingUtility();
  private static final byte[] f = HConstants.CATALOG_FAMILY;

  @BeforeClass
  public static void before() throws Exception {
    HTU.startMiniCluster(NB_SERVERS);
    final byte[] tableName = Bytes.toBytes(TestRegionReplicas.class.getSimpleName());

    // Create table then get the single region for our new table.
    table = HTU.createTable(tableName, f);

    hriPrimary = table.getRegionLocation(row, false).getRegionInfo();

    // mock a secondary region info to open
    hriSecondary = new HRegionInfo(hriPrimary.getTable(), hriPrimary.getStartKey(),
        hriPrimary.getEndKey(), hriPrimary.isSplit(), hriPrimary.getRegionId(), 1);

    // No master
    TestRegionServerNoMaster.stopMasterAndAssignMeta(HTU);
  }

  @AfterClass
  public static void afterClass() throws Exception {
    HRegionServer.TEST_SKIP_REPORTING_TRANSITION = false;
    table.close();
    HTU.shutdownMiniCluster();
  }

  private HRegionServer getRS() {
    return HTU.getMiniHBaseCluster().getRegionServer(0);
  }

  @Test(timeout = 60000)
  public void testOpenRegionReplica() throws Exception {
    openRegion(HTU, getRS(), hriSecondary);
    try {
      //load some data to primary
      HTU.loadNumericRows(table, f, 0, 1000);

      // assert that we can read back from primary
      Assert.assertEquals(1000, HTU.countRows(table));
    } finally {
      HTU.deleteNumericRows(table, f, 0, 1000);
      closeRegion(HTU, getRS(), hriSecondary);
    }
  }

  /** Tests that the meta location is saved for secondary regions */
  @Test(timeout = 60000)
  public void testRegionReplicaUpdatesMetaLocation() throws Exception {
    openRegion(HTU, getRS(), hriSecondary);
    Table meta = null;
    try {
      meta = new HTable(HTU.getConfiguration(), TableName.META_TABLE_NAME);
      TestMetaTableAccessor.assertMetaLocation(meta, hriPrimary.getRegionName()
        , getRS().getServerName(), -1, 1, false);
    } finally {
      if (meta != null ) meta.close();
      closeRegion(HTU, getRS(), hriSecondary);
    }
  }

  @Test(timeout = 60000)
  public void testRegionReplicaGets() throws Exception {
    try {
      //load some data to primary
      HTU.loadNumericRows(table, f, 0, 1000);
      // assert that we can read back from primary
      Assert.assertEquals(1000, HTU.countRows(table));
      // flush so that region replica can read
      getRS().getRegionByEncodedName(hriPrimary.getEncodedName()).flushcache();

      openRegion(HTU, getRS(), hriSecondary);

      // first try directly against region
      HRegion region = getRS().getFromOnlineRegions(hriSecondary.getEncodedName());
      assertGet(region, 42, true);

      assertGetRpc(hriSecondary, 42, true);
    } finally {
      HTU.deleteNumericRows(table, HConstants.CATALOG_FAMILY, 0, 1000);
      closeRegion(HTU, getRS(), hriSecondary);
    }
  }

  @Test(timeout = 60000)
  public void testGetOnTargetRegionReplica() throws Exception {
    try {
      //load some data to primary
      HTU.loadNumericRows(table, f, 0, 1000);
      // assert that we can read back from primary
      Assert.assertEquals(1000, HTU.countRows(table));
      // flush so that region replica can read
      getRS().getRegionByEncodedName(hriPrimary.getEncodedName()).flushcache();

      openRegion(HTU, getRS(), hriSecondary);

      // try directly Get against region replica
      byte[] row = Bytes.toBytes(String.valueOf(42));
      Get get = new Get(row);
      get.setConsistency(Consistency.TIMELINE);
      get.setReplicaId(1);
      Result result = table.get(get);
      Assert.assertArrayEquals(row, result.getValue(f, null));
    } finally {
      HTU.deleteNumericRows(table, HConstants.CATALOG_FAMILY, 0, 1000);
      closeRegion(HTU, getRS(), hriSecondary);
    }
  }

  private void assertGet(HRegion region, int value, boolean expect) throws IOException {
    byte[] row = Bytes.toBytes(String.valueOf(value));
    Get get = new Get(row);
    Result result = region.get(get);
    if (expect) {
      Assert.assertArrayEquals(row, result.getValue(f, null));
    } else {
      result.isEmpty();
    }
  }

  // build a mock rpc
  private void assertGetRpc(HRegionInfo info, int value, boolean expect)
      throws IOException, ServiceException {
    byte[] row = Bytes.toBytes(String.valueOf(value));
    Get get = new Get(row);
    ClientProtos.GetRequest getReq = RequestConverter.buildGetRequest(info.getRegionName(), get);
    ClientProtos.GetResponse getResp =  getRS().getRSRpcServices().get(null, getReq);
    Result result = ProtobufUtil.toResult(getResp.getResult());
    if (expect) {
      Assert.assertArrayEquals(row, result.getValue(f, null));
    } else {
      result.isEmpty();
    }
  }

  private void restartRegionServer() throws Exception {
    afterClass();
    before();
  }

  @Test(timeout = 300000)
  public void testRefreshStoreFiles() throws Exception {
    // enable store file refreshing
    final int refreshPeriod = 2000; // 2 sec
    HTU.getConfiguration().setInt("hbase.hstore.compactionThreshold", 100);
    HTU.getConfiguration().setInt(StorefileRefresherChore.REGIONSERVER_STOREFILE_REFRESH_PERIOD,
      refreshPeriod);
    // restart the region server so that it starts the refresher chore
    restartRegionServer();

    try {
      LOG.info("Opening the secondary region " + hriSecondary.getEncodedName());
      openRegion(HTU, getRS(), hriSecondary);

      //load some data to primary
      LOG.info("Loading data to primary region");
      HTU.loadNumericRows(table, f, 0, 1000);
      // assert that we can read back from primary
      Assert.assertEquals(1000, HTU.countRows(table));
      // flush so that region replica can read
      LOG.info("Flushing primary region");
      getRS().getRegionByEncodedName(hriPrimary.getEncodedName()).flushcache();

      // ensure that chore is run
      LOG.info("Sleeping for " + (4 * refreshPeriod));
      Threads.sleep(4 * refreshPeriod);

      LOG.info("Checking results from secondary region replica");
      HRegion secondaryRegion = getRS().getFromOnlineRegions(hriSecondary.getEncodedName());
      Assert.assertEquals(1, secondaryRegion.getStore(f).getStorefilesCount());

      assertGet(secondaryRegion, 42, true);
      assertGetRpc(hriSecondary, 42, true);
      assertGetRpc(hriSecondary, 1042, false);

      //load some data to primary
      HTU.loadNumericRows(table, f, 1000, 1100);
      getRS().getRegionByEncodedName(hriPrimary.getEncodedName()).flushcache();

      HTU.loadNumericRows(table, f, 2000, 2100);
      getRS().getRegionByEncodedName(hriPrimary.getEncodedName()).flushcache();

      // ensure that chore is run
      Threads.sleep(4 * refreshPeriod);

      assertGetRpc(hriSecondary, 42, true);
      assertGetRpc(hriSecondary, 1042, true);
      assertGetRpc(hriSecondary, 2042, true);

      // ensure that we are see the 3 store files
      Assert.assertEquals(3, secondaryRegion.getStore(f).getStorefilesCount());

      // force compaction
      HTU.compact(table.getName(), true);

      long wakeUpTime = System.currentTimeMillis() + 4 * refreshPeriod;
      while (System.currentTimeMillis() < wakeUpTime) {
        assertGetRpc(hriSecondary, 42, true);
        assertGetRpc(hriSecondary, 1042, true);
        assertGetRpc(hriSecondary, 2042, true);
        Threads.sleep(10);
      }

      // ensure that we see the compacted file only
      Assert.assertEquals(1, secondaryRegion.getStore(f).getStorefilesCount());

    } finally {
      HTU.deleteNumericRows(table, HConstants.CATALOG_FAMILY, 0, 1000);
      closeRegion(HTU, getRS(), hriSecondary);
    }
  }

  @Test(timeout = 300000)
  public void testFlushAndCompactionsInPrimary() throws Exception {

    long runtime = 30 * 1000;
    // enable store file refreshing
    final int refreshPeriod = 100; // 100ms refresh is a lot
    HTU.getConfiguration().setInt("hbase.hstore.compactionThreshold", 3);
    HTU.getConfiguration().setInt(StorefileRefresherChore.REGIONSERVER_STOREFILE_REFRESH_PERIOD, refreshPeriod);
    // restart the region server so that it starts the refresher chore
    restartRegionServer();
    final int startKey = 0, endKey = 1000;

    try {
      openRegion(HTU, getRS(), hriSecondary);

      //load some data to primary so that reader won't fail
      HTU.loadNumericRows(table, f, startKey, endKey);
      TestRegionServerNoMaster.flushRegion(HTU, hriPrimary);
      // ensure that chore is run
      Threads.sleep(2 * refreshPeriod);

      final AtomicBoolean running = new AtomicBoolean(true);
      @SuppressWarnings("unchecked")
      final AtomicReference<Exception>[] exceptions = new AtomicReference[3];
      for (int i=0; i < exceptions.length; i++) {
        exceptions[i] = new AtomicReference<Exception>();
      }

      Runnable writer = new Runnable() {
        int key = startKey;
        @Override
        public void run() {
          try {
            while (running.get()) {
              byte[] data = Bytes.toBytes(String.valueOf(key));
              Put put = new Put(data);
              put.add(f, null, data);
              table.put(put);
              key++;
              if (key == endKey) key = startKey;
            }
          } catch (Exception ex) {
            LOG.warn(ex);
            exceptions[0].compareAndSet(null, ex);
          }
        }
      };

      Runnable flusherCompactor = new Runnable() {
        Random random = new Random();
        @Override
        public void run() {
          try {
            while (running.get()) {
              // flush or compact
              if (random.nextBoolean()) {
                TestRegionServerNoMaster.flushRegion(HTU, hriPrimary);
              } else {
                HTU.compact(table.getName(), random.nextBoolean());
              }
            }
          } catch (Exception ex) {
            LOG.warn(ex);
            exceptions[1].compareAndSet(null, ex);
          }
        }
      };

      Runnable reader = new Runnable() {
        Random random = new Random();
        @Override
        public void run() {
          try {
            while (running.get()) {
              // whether to do a close and open
              if (random.nextInt(10) == 0) {
                try {
                  closeRegion(HTU, getRS(), hriSecondary);
                } catch (Exception ex) {
                  LOG.warn("Failed closing the region " + hriSecondary + " "  + StringUtils.stringifyException(ex));
                  exceptions[2].compareAndSet(null, ex);
                }
                try {
                  openRegion(HTU, getRS(), hriSecondary);
                } catch (Exception ex) {
                  LOG.warn("Failed opening the region " + hriSecondary + " "  + StringUtils.stringifyException(ex));
                  exceptions[2].compareAndSet(null, ex);
                }
              }

              int key = random.nextInt(endKey - startKey) + startKey;
              assertGetRpc(hriSecondary, key, true);
            }
          } catch (Exception ex) {
            LOG.warn("Failed getting the value in the region " + hriSecondary + " "  + StringUtils.stringifyException(ex));
            exceptions[2].compareAndSet(null, ex);
          }
        }
      };

      LOG.info("Starting writer and reader");
      ExecutorService executor = Executors.newFixedThreadPool(3);
      executor.submit(writer);
      executor.submit(flusherCompactor);
      executor.submit(reader);

      // wait for threads
      Threads.sleep(runtime);
      running.set(false);
      executor.shutdown();
      executor.awaitTermination(30, TimeUnit.SECONDS);

      for (AtomicReference<Exception> exRef : exceptions) {
        Assert.assertNull(exRef.get());
      }

    } finally {
      HTU.deleteNumericRows(table, HConstants.CATALOG_FAMILY, startKey, endKey);
      closeRegion(HTU, getRS(), hriSecondary);
    }
  }
}
