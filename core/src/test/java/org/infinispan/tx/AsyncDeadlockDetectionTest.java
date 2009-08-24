package org.infinispan.tx;

import org.infinispan.Cache;
import org.infinispan.commands.VisitableCommand;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.config.Configuration;
import org.infinispan.context.InvocationContext;
import org.infinispan.interceptors.base.CommandInterceptor;
import org.infinispan.test.MultipleCacheManagersTest;
import org.infinispan.test.PerCacheExecutorThread;
import org.infinispan.test.TestingUtil;
import org.infinispan.transaction.xa.TransactionTable;
import org.infinispan.util.concurrent.locks.DeadlockDetectedException;
import org.infinispan.util.concurrent.locks.DeadlockDetectingLockManager;
import org.infinispan.util.concurrent.locks.LockManager;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Tests deadlock detection for async caches.
 *
 * @author Mircea.Markus@jboss.com
 */
@Test(groups = "functional", testName = "tx.AsyncDeadlockDetectionTest")
public class AsyncDeadlockDetectionTest extends MultipleCacheManagersTest {
   private Cache cache0;
   private Cache cache1;
   private DeadlockDetectingLockManager ddLm0;
   private DeadlockDetectingLockManager ddLm1;
   private PerCacheExecutorThread t0;
   private PerCacheExecutorThread t1;
   private RemoteReplicationInterceptor remoteReplicationInterceptor;


   protected void createCacheManagers() throws Throwable {
      Configuration config = getDefaultClusteredConfig(Configuration.CacheMode.REPL_ASYNC, true);
      config.setEnableDeadlockDetection(true);
      config.setSyncCommitPhase(true);
      config.setSyncRollbackPhase(true);
      config.setUseLockStriping(false);
      assert config.isEnableDeadlockDetection();
      createClusteredCaches(2, "test", config);
      assert config.isEnableDeadlockDetection();

      cache0 = cache(0, "test");
      cache1 = cache(1, "test");
      remoteReplicationInterceptor = new RemoteReplicationInterceptor();
      cache1.getAdvancedCache().addInterceptor(remoteReplicationInterceptor, 0);
      assert cache0.getConfiguration().isEnableDeadlockDetection();
      assert cache1.getConfiguration().isEnableDeadlockDetection();
      assert !cache0.getConfiguration().isExposeJmxStatistics();
      assert !cache1.getConfiguration().isExposeJmxStatistics();

      ((DeadlockDetectingLockManager) TestingUtil.extractLockManager(cache0)).setExposeJmxStats(true);
      ((DeadlockDetectingLockManager) TestingUtil.extractLockManager(cache1)).setExposeJmxStats(true);

      ddLm0 = (DeadlockDetectingLockManager) TestingUtil.extractLockManager(cache0);
      ddLm1 = (DeadlockDetectingLockManager) TestingUtil.extractLockManager(cache1);
   }

   @BeforeMethod
   public void beforeMethod() {
      t0 = new PerCacheExecutorThread(cache0, 0);
      t1 = new PerCacheExecutorThread(cache1, 1);
   }

   @AfterMethod
   public void afterMethod() {
      t0.stopThread();
      t1.stopThread();
      ((DeadlockDetectingLockManager) TestingUtil.extractLockManager(cache0)).resetStatistics();
      ((DeadlockDetectingLockManager) TestingUtil.extractLockManager(cache1)).resetStatistics();
      remoteReplicationInterceptor.executionResponse = null;
   }

   public void testRemoteTxVsLocal() throws Exception {
      assertEquals(PerCacheExecutorThread.OperationsResult.BEGGIN_TX_OK, t0.execute(PerCacheExecutorThread.Operations.BEGGIN_TX));
      t0.setKeyValue("k1", "v1_t0");
      assertEquals(PerCacheExecutorThread.OperationsResult.PUT_KEY_VALUE_OK, t0.execute(PerCacheExecutorThread.Operations.PUT_KEY_VALUE));
      t0.setKeyValue("k2", "v2_t0");
      assertEquals(PerCacheExecutorThread.OperationsResult.PUT_KEY_VALUE_OK, t0.execute(PerCacheExecutorThread.Operations.PUT_KEY_VALUE));

      assertEquals(PerCacheExecutorThread.OperationsResult.BEGGIN_TX_OK, t1.execute(PerCacheExecutorThread.Operations.BEGGIN_TX));
      t1.setKeyValue("k2", "v2_t1");
      assertEquals(PerCacheExecutorThread.OperationsResult.PUT_KEY_VALUE_OK, t1.execute(PerCacheExecutorThread.Operations.PUT_KEY_VALUE));

      t0.execute(PerCacheExecutorThread.Operations.COMMIT_TX);

      LockManager lockManager = TestingUtil.extractLockManager(cache1);
      while (!lockManager.isLocked("k1")) {
         Thread.sleep(50);
      }
      System.out.println("successful replication !");


      t1.setKeyValue("k1", "v1_t1");
      t1.executeNoResponse(PerCacheExecutorThread.Operations.PUT_KEY_VALUE);


      Object t1Response = t1.waitForResponse();
      Object t0Response = remoteReplicationInterceptor.getResponse();

      System.out.println("t0Response = " + t0Response);
      System.out.println("t1Response = " + t1Response);

      assert xor(t1Response instanceof DeadlockDetectedException, t0Response instanceof DeadlockDetectedException);

      if (t0Response instanceof DeadlockDetectedException) {
         replListener(cache0).expectWithTx(PutKeyValueCommand.class, PutKeyValueCommand.class);
         assertEquals(t1.execute(PerCacheExecutorThread.Operations.COMMIT_TX), PerCacheExecutorThread.OperationsResult.COMMIT_TX_OK);
         replListener(cache0).waitForRpc();
      }

      assertFalse(ddLm0.isLocked("k1"));
      assertFalse(ddLm1.isLocked("k1"));
      assertFalse(ddLm0.isLocked("k2"));
      assertFalse(ddLm1.isLocked("k2"));
      TransactionTable transactionTable0 = TestingUtil.extractComponent(cache0, TransactionTable.class);
      assertEquals(transactionTable0.getLocalTxCount(), 0);
      for (int i = 0; i < 20; i++) {
         if (!(transactionTable0.getRemoteTxCount() == 0)) Thread.sleep(50);
      }

      assertEquals(transactionTable0.getRemoteTxCount(), 0);

      TransactionTable transactionTable1 = TestingUtil.extractComponent(cache1, TransactionTable.class);
      assertEquals(transactionTable1.getLocalTxCount(), 0);
      for (int i = 0; i < 20; i++) {
         if (!(transactionTable1.getRemoteTxCount() == 0)) Thread.sleep(50);
      }
      assertEquals(transactionTable1.getRemoteTxCount(), 0);

      if (t1Response instanceof DeadlockDetectedException) {
         assertEquals(cache0.get("k1"), "v1_t0");
         assertEquals(cache0.get("k2"), "v2_t0");
         assertEquals(cache1.get("k1"), "v1_t0");
         assertEquals(cache1.get("k2"), "v2_t0");
      } else {
         assertEquals(cache0.get("k1"), "v1_t1");
         assertEquals(cache0.get("k2"), "v2_t1");
         assertEquals(cache1.get("k1"), "v1_t1");
         assertEquals(cache1.get("k2"), "v2_t1");
      }
   }


   public static class RemoteReplicationInterceptor extends CommandInterceptor {

      public volatile Object executionResponse;

      @Override
      protected Object handleDefault(InvocationContext ctx, VisitableCommand command) throws Throwable {
         try {
            return invokeNextInterceptor(ctx, command);
         } catch (Throwable throwable) {
            if (!ctx.isOriginLocal()) {
               log.trace("Setting thrownExceptionForRemoteTx to " + throwable);
               executionResponse = throwable;
            } else {
               log.trace("Ignoring throwable " + throwable);
               executionResponse = "NONE";
            }
            throw throwable;
         }
      }

      public Object getResponse() throws Exception {
         while (executionResponse == null) {
            Thread.sleep(50);
         }
         return executionResponse;
      }
   }
}
