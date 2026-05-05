package com.aicodeassistant.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 IncrementalCollapseManager 的核心逻辑
 */
class IncrementalCollapseManagerTest {

    private IncrementalCollapseManager manager;

    @BeforeEach
    void setUp() {
        manager = new IncrementalCollapseManager();
    }

    @Test
    @DisplayName("shouldCollapse: 累计轮次不足10轮不触发")
    void shouldCollapse_belowThreshold_returnsFalse() {
        // 单次请求贡献5轮，累计5 < 10
        assertFalse(manager.shouldCollapse("session-1", 5));
        // 再贡献4轮，累计9 < 10
        assertFalse(manager.shouldCollapse("session-1", 4));
    }

    @Test
    @DisplayName("shouldCollapse: 单次请求贡献达到10轮阈值触发")
    void shouldCollapse_atThreshold_returnsTrue() {
        assertTrue(manager.shouldCollapse("session-1", 10));
    }

    @Test
    @DisplayName("shouldCollapse: 多次请求累计达到阈值触发")
    void shouldCollapse_cumulativeReachesThreshold_returnsTrue() {
        // 模拟典型场景：多次请求每次贡献2轮
        assertFalse(manager.shouldCollapse("session-1", 2)); // 累计2
        assertFalse(manager.shouldCollapse("session-1", 2)); // 累计4
        assertFalse(manager.shouldCollapse("session-1", 2)); // 累计6
        assertFalse(manager.shouldCollapse("session-1", 2)); // 累计8
        assertTrue(manager.shouldCollapse("session-1", 2));  // 累计10 >= 10
    }

    @Test
    @DisplayName("shouldCollapse: 单次超过阈值也触发")
    void shouldCollapse_aboveThreshold_returnsTrue() {
        assertTrue(manager.shouldCollapse("session-1", 15));
    }

    @Test
    @DisplayName("recordCollapse后重置累计基准，需再累积10轮才触发")
    void recordCollapse_updatesLastCollapsedTurn() {
        // 先累积到10触发
        assertTrue(manager.shouldCollapse("session-1", 10));

        var segment = new IncrementalCollapseManager.CollapsedSegment(
                1, 10, "Summary of turns 1-10", 5000, 500, List.of("msg-1", "msg-2"));
        manager.recordCollapse("session-1", segment);

        // recordCollapse后，需再累积10轮才触发
        assertFalse(manager.shouldCollapse("session-1", 5));  // 累计15, 差值=15-10=5 < 10
        assertFalse(manager.shouldCollapse("session-1", 4));  // 累计19, 差值=19-10=9 < 10
        assertTrue(manager.shouldCollapse("session-1", 1));   // 累计20, 差值=20-10=10 >= 10
    }

    @Test
    @DisplayName("getSegments: 返回已记录的折叠段")
    void getSegments_returnsRecordedSegments() {
        var segment1 = new IncrementalCollapseManager.CollapsedSegment(
                1, 10, "Summary 1", 5000, 500, List.of("msg-1"));
        var segment2 = new IncrementalCollapseManager.CollapsedSegment(
                11, 20, "Summary 2", 4000, 400, List.of("msg-2"));

        manager.recordCollapse("session-1", segment1);
        manager.recordCollapse("session-1", segment2);

        List<IncrementalCollapseManager.CollapsedSegment> segments = manager.getSegments("session-1");
        assertEquals(2, segments.size());
        assertEquals("Summary 1", segments.get(0).summaryText());
        assertEquals("Summary 2", segments.get(1).summaryText());
    }

    @Test
    @DisplayName("getSegments: 不存在的会话返回空列表")
    void getSegments_nonExistentSession_returnsEmpty() {
        assertTrue(manager.getSegments("non-existent").isEmpty());
    }

    @Test
    @DisplayName("cleanupExpiredSessions: 清理过期会话")
    void cleanupExpiredSessions_removesExpired() throws Exception {
        // 先创建一个会话（累积1轮）
        manager.shouldCollapse("session-1", 1);
        assertEquals(1, manager.getActiveSessionCount());

        // 通过反射修改lastAccessTime使其过期
        var field = IncrementalCollapseManager.class.getDeclaredField("sessionStates");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var sessionStates = (java.util.concurrent.ConcurrentHashMap<String, ?>) field.get(manager);
        var stateObj = sessionStates.get("session-1");
        var accessTimeField = stateObj.getClass().getDeclaredField("lastAccessTime");
        accessTimeField.setAccessible(true);
        // 设为31分钟前（超过30分钟超时）
        accessTimeField.set(stateObj, System.currentTimeMillis() - 31 * 60 * 1000L);

        // 执行清理
        manager.cleanupExpiredSessions();

        assertEquals(0, manager.getActiveSessionCount());
    }

    @Test
    @DisplayName("多会话隔离: 不同会话状态互不影响")
    void multipleSession_isolation() {
        // session-1累计5, 不触发
        assertFalse(manager.shouldCollapse("session-1", 5));
        // session-2累计15 >= 10, 触发
        assertTrue(manager.shouldCollapse("session-2", 15));

        // session-1再累积4=9, 仍不触发
        assertFalse(manager.shouldCollapse("session-1", 4));

        assertEquals(2, manager.getActiveSessionCount());
    }

    @Test
    @DisplayName("CollapsedSegment record 正确存储数据")
    void collapsedSegment_recordCorrectness() {
        var segment = new IncrementalCollapseManager.CollapsedSegment(
                1, 10, "Test summary", 3000, 300, List.of("id-1", "id-2", "id-3"));

        assertEquals(1, segment.turnStart());
        assertEquals(10, segment.turnEnd());
        assertEquals("Test summary", segment.summaryText());
        assertEquals(3000, segment.originalTokens());
        assertEquals(300, segment.summaryTokens());
        assertEquals(3, segment.originalMessageIds().size());
    }
}
