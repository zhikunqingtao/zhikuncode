package com.aicodeassistant.tool.agent;

import com.aicodeassistant.coordinator.TaskNotificationFormatter;
import com.aicodeassistant.engine.QueryEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubAgentExecutorStatusPropagationTest {

    @Test
    @DisplayName("max_turns 状态同时传播到返回值和 Coordinator 通知")
    void maxTurnsStatusIsPreservedInCoordinatorNotification() {
        QueryEngine.QueryResult queryResult =
                new QueryEngine.QueryResult(List.of(), null, "max_turns", null, 30);
        SubAgentExecutor.AgentRequest request = new SubAgentExecutor.AgentRequest(
                "agent-bank",
                "调查银行积存金",
                "worker",
                null,
                SubAgentExecutor.IsolationMode.NONE,
                false);

        SubAgentExecutor.AgentResult result = SubAgentExecutor.buildFinalResult(
                queryResult,
                "已完成部分银行数据收集",
                request,
                new TaskNotificationFormatter(),
                true,
                1234L);

        assertEquals(SubAgentExecutor.AgentResult.STATUS_MAX_TURNS, result.status());
        assertTrue(result.result().contains("<status>max_turns</status>"));
        assertTrue(result.result().contains("已完成部分银行数据收集"));
    }

    @Test
    @DisplayName("非 Coordinator 模式仍保留原始答案和真实状态")
    void nonCoordinatorResultKeepsRawAnswerAndStatus() {
        QueryEngine.QueryResult queryResult =
                new QueryEngine.QueryResult(List.of(), null, "end_turn", null, 2);
        SubAgentExecutor.AgentRequest request = new SubAgentExecutor.AgentRequest(
                "agent-research",
                "研究任务",
                "worker",
                null,
                SubAgentExecutor.IsolationMode.NONE,
                false);

        SubAgentExecutor.AgentResult result = SubAgentExecutor.buildFinalResult(
                queryResult,
                "研究完成",
                request,
                new TaskNotificationFormatter(),
                false,
                100L);

        assertEquals(SubAgentExecutor.AgentResult.STATUS_COMPLETED, result.status());
        assertEquals("研究完成", result.result());
    }

    @Test
    @DisplayName("failed 时 task-notification 的 summary 包含 QueryResult.error 失败原因")
    void failedNotificationSummaryContainsErrorReason() {
        QueryEngine.QueryResult queryResult = new QueryEngine.QueryResult(
                List.of(), null, "error",
                "PROVIDER_PAYMENT_REQUIRED: 模型账户余额不足，请充值或切换模型", 3);
        SubAgentExecutor.AgentRequest request = new SubAgentExecutor.AgentRequest(
                "agent-fail",
                "失败任务",
                "worker",
                null,
                SubAgentExecutor.IsolationMode.NONE,
                false);

        SubAgentExecutor.AgentResult result = SubAgentExecutor.buildFinalResult(
                queryResult,
                "子代理未返回响应。",
                request,
                new TaskNotificationFormatter(),
                true,
                500L);

        assertEquals(SubAgentExecutor.AgentResult.STATUS_FAILED, result.status());
        assertTrue(result.result().contains("<status>failed</status>"));
        // summary 必须携带失败原因，不能被静默丢弃
        assertTrue(result.result().contains(
                "failed: PROVIDER_PAYMENT_REQUIRED: 模型账户余额不足，请充值或切换模型"));
    }

    @Test
    @DisplayName("非 Coordinator 模式下 failed 结果同样携带失败原因")
    void nonCoordinatorFailedResultContainsErrorReason() {
        QueryEngine.QueryResult queryResult = new QueryEngine.QueryResult(
                List.of(), null, "error", "PROVIDER_RATE_LIMITED: 模型请求频率超限，请稍后重试或切换模型", 1);
        SubAgentExecutor.AgentRequest request = new SubAgentExecutor.AgentRequest(
                "agent-fail-raw",
                "失败任务",
                "worker",
                null,
                SubAgentExecutor.IsolationMode.NONE,
                false);

        SubAgentExecutor.AgentResult result = SubAgentExecutor.buildFinalResult(
                queryResult,
                "",
                request,
                new TaskNotificationFormatter(),
                false,
                100L);

        assertEquals(SubAgentExecutor.AgentResult.STATUS_FAILED, result.status());
        assertEquals("failed: PROVIDER_RATE_LIMITED: 模型请求频率超限，请稍后重试或切换模型", result.result());
    }

    @Test
    @DisplayName("fork 路径（formatter=null）failed 时同样透出失败原因，不因无 formatter 丢失前缀")
    void forkPathFailedResultContainsErrorReasonWithoutFormatter() {
        QueryEngine.QueryResult queryResult = new QueryEngine.QueryResult(
                List.of(), null, "error", "PROVIDER_PAYMENT_REQUIRED: 模型账户余额不足，请充值或切换模型", 2);
        SubAgentExecutor.AgentRequest request = new SubAgentExecutor.AgentRequest(
                "fork-agent-fail",
                "fork 失败任务",
                "worker",
                null,
                SubAgentExecutor.IsolationMode.NONE,
                false);

        // fork 路径以 buildFinalResult(result, answer, request, null, false, durationMs) 收尾，
        // 必须与主路径一致地在 failed 时透出 QueryResult.error
        SubAgentExecutor.AgentResult result = SubAgentExecutor.buildFinalResult(
                queryResult,
                "fork 子代理未返回响应。",
                request,
                null,
                false,
                300L);

        assertEquals(SubAgentExecutor.AgentResult.STATUS_FAILED, result.status());
        assertEquals("failed: PROVIDER_PAYMENT_REQUIRED: 模型账户余额不足，请充值或切换模型"
                + "\nfork 子代理未返回响应。", result.result());
    }
}
