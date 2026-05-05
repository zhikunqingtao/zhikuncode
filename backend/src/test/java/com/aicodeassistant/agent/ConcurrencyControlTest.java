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
        @DisplayName("50线程并发获取释放线程安全")
        void testConcurrentAcquireRelease() throws Exception {
            AgentConcurrencyController controller = new AgentConcurrencyController();
            int threadCount = 50;
            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                        AutoCloseable slot = controller.acquireSlot(
                            "agent-" + idx, 1, "session-" + (idx % 3));
                        successCount.incrementAndGet();
                        Thread.sleep(50);
                        slot.close();
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "测试应在30秒内完成");
            assertTrue(successCount.get() <= 30, "成功数应≤全局限制30");
            assertEquals(0, controller.getActiveCount(), "全部释放后 activeCount=0");
            executor.shutdown();
        }
    }
}
