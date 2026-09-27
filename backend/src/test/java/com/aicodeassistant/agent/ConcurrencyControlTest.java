package com.aicodeassistant.agent;

import com.aicodeassistant.tool.agent.AgentConcurrencyController;
import com.aicodeassistant.tool.agent.AgentLimitExceededException;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-CONC-001~005 并发控制专项测试。
 */
@DisplayName("并发控制专项测试")
class ConcurrencyControlTest {

    @Nested
    @DisplayName("TC-CONC-001 全局并发限制边界验证")
    class GlobalConcurrencyLimitTest {

        @Test
        @DisplayName("获取30个槽位后第31个应抛异常")
        void testGlobalConcurrencyLimit() {
            AgentConcurrencyController controller = new AgentConcurrencyController();
            List<AutoCloseable> slots = new ArrayList<>();

            for (int i = 0; i < 30; i++) {
                AutoCloseable slot = controller.acquireSlot("agent-" + i, 1, "session-" + (i % 5));
                assertNotNull(slot);
                slots.add(slot);
            }
            assertEquals(30, controller.getActiveCount());

            assertThrows(AgentLimitExceededException.class, () ->
                controller.acquireSlot("agent-31", 1, "session-new"));

            assertDoesNotThrow(() -> slots.get(0).close());
            AutoCloseable newSlot = controller.acquireSlot("agent-new", 1, "session-0");
            assertNotNull(newSlot);

            // Cleanup
            try { newSlot.close(); } catch (Exception ignored) {}
            for (int i = 1; i < slots.size(); i++) {
                try { slots.get(i).close(); } catch (Exception ignored) {}
            }
        }
    }

    @Nested
    @DisplayName("TC-CONC-002 会话级并发隔离验证")
    class SessionIsolationTest {

        @Test
        @DisplayName("sessionA满载后sessionB不受影响")
        void testSessionLevelIsolation() {
            AgentConcurrencyController controller = new AgentConcurrencyController();
            String sessionA = "session-A";
            String sessionB = "session-B";
            List<AutoCloseable> slots = new ArrayList<>();

            for (int i = 0; i < 10; i++) {
                slots.add(controller.acquireSlot("agent-a-" + i, 1, sessionA));
            }

            assertThrows(AgentLimitExceededException.class, () ->
                controller.acquireSlot("agent-a-11", 1, sessionA));

            AutoCloseable slotB = controller.acquireSlot("agent-b-1", 1, sessionB);
            assertNotNull(slotB);

            // Cleanup
            try { slotB.close(); } catch (Exception ignored) {}
            for (AutoCloseable s : slots) {
                try { s.close(); } catch (Exception ignored) {}
            }
        }
    }

    @Nested
    @DisplayName("TC-CONC-003 嵌套深度限制验证")
    class NestingDepthTest {

        @Test
        @DisplayName("深度1-3成功，深度4应失败")
        void testNestingDepthLimit() {
            AgentConcurrencyController controller = new AgentConcurrencyController();
            List<AutoCloseable> slots = new ArrayList<>();

            for (int depth = 1; depth <= 3; depth++) {
                AutoCloseable slot = controller.acquireSlot("agent-d" + depth, depth, "session-1");
                assertNotNull(slot);
                slots.add(slot);
            }

            assertThrows(AgentLimitExceededException.class, () ->
                controller.acquireSlot("agent-d4", 4, "session-1"));

            for (AutoCloseable s : slots) {
                try { s.close(); } catch (Exception ignored) {}
            }
        }
    }

    @Nested
    @DisplayName("TC-CONC-004 异常路径槽位不泄漏")
    class SlotLeakPreventionTest {

        @Test
        @DisplayName("异常后槽位应通过try-with-resources释放")
        void testSlotLeakPrevention() {
            AgentConcurrencyController controller = new AgentConcurrencyController();
            int initialCount = controller.getActiveCount();

            try (AutoCloseable slot = controller.acquireSlot("agent-test", 1, "session-test")) {
                assertEquals(initialCount + 1, controller.getActiveCount());
                throw new RuntimeException("Simulated agent failure");
            } catch (RuntimeException e) {
                // 预期异常
            } catch (Exception e) {
                fail("Unexpected exception: " + e.getMessage());
            }

            assertEquals(initialCount, controller.getActiveCount());
        }
    }

    @Nested
    @DisplayName("TC-CONC-005 多线程并发获取/释放安全性")
    class ConcurrentSafetyTest {

        @RepeatedTest(3)
        @DisplayName("50线程分阶段获取释放并验证槽位复用")
        void testConcurrentAcquireRelease() throws Exception {
            AgentConcurrencyController controller = new AgentConcurrencyController();
            int threadCount = 50;
            int globalLimit = 30;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            try {
                // 两轮复用相同会话；每个会话只申请一个槽位，单独检验全局限制。
                for (int round = 0; round < 2; round++) {
                    CountDownLatch ready = new CountDownLatch(threadCount);
                    CountDownLatch start = new CountDownLatch(1);
                    CountDownLatch attempted = new CountDownLatch(threadCount);
                    CountDownLatch release = new CountDownLatch(1);
                    AtomicInteger heldCount = new AtomicInteger();
                    AtomicInteger peakHeldCount = new AtomicInteger();
                    AtomicInteger rejectedCount = new AtomicInteger();
                    List<Future<?>> tasks = new ArrayList<>();
                    try {
                        for (int i = 0; i < threadCount; i++) {
                            final int idx = i;
                            tasks.add(executor.submit(() -> {
                                ready.countDown();
                                start.await();
                                AgentConcurrencyController.AgentSlot slot;
                                try {
                                    slot = controller.acquireSlot("agent-" + idx, 1, "session-" + idx);
                                } catch (AgentLimitExceededException e) {
                                    rejectedCount.incrementAndGet();
                                    attempted.countDown();
                                    return null;
                                }
                                try (slot) {
                                    int held = heldCount.incrementAndGet();
                                    peakHeldCount.accumulateAndGet(held, Math::max);
                                    attempted.countDown();
                                    try {
                                        release.await();
                                    } finally {
                                        heldCount.decrementAndGet();
                                    }
                                }
                                return null;
                            }));
                        }
                        assertTrue(ready.await(10, TimeUnit.SECONDS), "全部线程应准备好获取槽位");
                        start.countDown();
                        assertTrue(attempted.await(10, TimeUnit.SECONDS), "全部获取尝试应完成");
                        // 获取阶段禁止释放，因此这里测量的是同时持有数，而非累计成功次数。
                        assertEquals(globalLimit, heldCount.get(), "同时持有数应达到全局上限");
                        assertEquals(globalLimit, peakHeldCount.get(), "并发持有峰值不得超过全局上限");
                        assertEquals(globalLimit, controller.getActiveCount(), "控制器应记录全部在用槽位");
                        assertEquals(threadCount - globalLimit, rejectedCount.get(), "满载后的申请应被拒绝");
                    } finally {
                        start.countDown();
                        release.countDown();
                    }
                    // Future.get 传播非限流异常；异常不能被计入预期拒绝后悄然忽略。
                    for (Future<?> task : tasks) {
                        task.get(10, TimeUnit.SECONDS);
                    }
                    assertEquals(0, heldCount.get(), "全部工作线程应释放槽位");
                    assertEquals(0, controller.getActiveCount(), "全部释放后 activeCount=0");
                    for (int i = 0; i < threadCount; i++) {
                        assertEquals(0, controller.getSessionActiveCount("session-" + i),
                                "全部释放后会话计数应归零");
                    }
                }
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "执行器应在清理后终止");
            }
        }
    }
}
