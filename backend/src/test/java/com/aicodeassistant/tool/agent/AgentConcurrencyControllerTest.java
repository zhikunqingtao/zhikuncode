package com.aicodeassistant.tool.agent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-AGENT-002 AgentConcurrencyController 并发限制验证
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TC-AGENT-002 AgentConcurrencyController 并发限制验证")
class AgentConcurrencyControllerTest {

    private AgentConcurrencyController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentConcurrencyController();
    }

    @Nested
    @DisplayName("全局并发限制（≤30）")
    class GlobalConcurrencyLimitTest {

        @Test
        @DisplayName("获取 30 个全局槽位应全部成功")
        void shouldAcquire30GlobalSlots() {
            List<AgentConcurrencyController.AgentSlot> slots = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                AgentConcurrencyController.AgentSlot slot =
                        controller.acquireSlot("agent-" + i, 1, "session-" + (i % 15));
                assertNotNull(slot);
                slots.add(slot);
            }
            assertEquals(30, controller.getActiveCount());
            slots.forEach(AgentConcurrencyController.AgentSlot::close);
        }

        @Test
        @DisplayName("第 31 个全局槽位应抛出 AgentLimitExceededException")
        void shouldRejectThe31stGlobalSlot() {
            List<AgentConcurrencyController.AgentSlot> slots = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                slots.add(controller.acquireSlot("agent-" + i, 1, "session-" + i));
            }

            AgentLimitExceededException ex = assertThrows(
                    AgentLimitExceededException.class,
                    () -> controller.acquireSlot("agent-31", 1, "session-31")
            );
            assertTrue(ex.getMessage().contains("Concurrent agent limit reached"));
            slots.forEach(AgentConcurrencyController.AgentSlot::close);
        }

        @Test
        @DisplayName("释放 1 个槽位后应可再次获取")
        void shouldAcquireAfterRelease() {
            List<AgentConcurrencyController.AgentSlot> slots = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                slots.add(controller.acquireSlot("agent-" + i, 1, "session-" + i));
            }

            slots.get(0).close();
            assertEquals(29, controller.getActiveCount());

            AgentConcurrencyController.AgentSlot newSlot =
                    controller.acquireSlot("agent-new", 1, "session-new");
            assertNotNull(newSlot);
            assertEquals(30, controller.getActiveCount());

            newSlot.close();
            for (int i = 1; i < slots.size(); i++) {
                slots.get(i).close();
            }
        }
    }

    @Nested
    @DisplayName("会话级并发限制（≤10）")
    class SessionConcurrencyLimitTest {

        @Test
        @DisplayName("单会话获取 10 个槽位应成功")
        void shouldAcquire10SessionSlots() {
            String sessionId = "session-single";
            List<AgentConcurrencyController.AgentSlot> slots = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                slots.add(controller.acquireSlot("agent-" + i, 1, sessionId));
            }
            assertEquals(10, controller.getSessionActiveCount(sessionId));
            slots.forEach(AgentConcurrencyController.AgentSlot::close);
        }

        @Test
        @DisplayName("单会话第 11 个槽位应抛出异常")
        void shouldRejectThe11thSessionSlot() {
            String sessionId = "session-limit";
            List<AgentConcurrencyController.AgentSlot> slots = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                slots.add(controller.acquireSlot("agent-" + i, 1, sessionId));
            }

            AgentLimitExceededException ex = assertThrows(
                    AgentLimitExceededException.class,
                    () -> controller.acquireSlot("agent-11", 1, sessionId)
            );
            assertTrue(ex.getMessage().contains("Session"));
            assertTrue(ex.getMessage().contains("concurrent agent limit reached"));
            slots.forEach(AgentConcurrencyController.AgentSlot::close);
        }
    }

    @Nested
    @DisplayName("嵌套深度限制（≤3）")
    class NestingDepthLimitTest {

        @Test
        @DisplayName("嵌套深度 1~3 应成功")
        void shouldAllowNestingUpTo3() {
            for (int depth = 1; depth <= 3; depth++) {
                AgentConcurrencyController.AgentSlot slot =
                        controller.acquireSlot("agent-depth-" + depth, depth, "session-nest");
                assertNotNull(slot);
                slot.close();
            }
        }

        @Test
        @DisplayName("嵌套深度 4 应抛出异常")
        void shouldRejectNestingDepth4() {
            AgentLimitExceededException ex = assertThrows(
                    AgentLimitExceededException.class,
                    () -> controller.acquireSlot("agent-deep", 4, "session-nest")
            );
            assertTrue(ex.getMessage().contains("nesting depth"));
            assertTrue(ex.getMessage().contains("exceeds max"));
        }
    }

    @Nested
    @DisplayName("AgentSlot 自动释放")
    class AgentSlotAutoCloseTest {

        @Test
        @DisplayName("try-with-resources 应自动释放槽位")
        void shouldAutoReleaseSlotOnClose() {
            try (var slot = controller.acquireSlot("agent-auto", 1, "session-auto")) {
                assertEquals(1, controller.getActiveCount());
                assertEquals(1, controller.getSessionActiveCount("session-auto"));
            }
            assertEquals(0, controller.getActiveCount());
            assertEquals(0, controller.getSessionActiveCount("session-auto"));
        }
    }
}
