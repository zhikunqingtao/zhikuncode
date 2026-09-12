package com.aicodeassistant.coordinator;

import com.aicodeassistant.mcp.McpClientManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoordinatorPromptBuilderTest {

    @Test
    @DisplayName("提示词要求接管部分结果并阻止同范围重复派发")
    void promptContainsBoundedFallbackForIncompleteWorkers() {
        CoordinatorService coordinatorService = mock(CoordinatorService.class);
        McpClientManager mcpClientManager = mock(McpClientManager.class);
        when(coordinatorService.getWorkerToolsContext("session-1"))
                .thenReturn(Map.of("workerToolsContext", "standard tools"));
        when(mcpClientManager.getConnectedServers()).thenReturn(List.of());

        CoordinatorPromptBuilder builder =
                new CoordinatorPromptBuilder(coordinatorService, mcpClientManager);
        String prompt = builder.buildCoordinatorPrompt(
                "session-1", Path.of("/tmp/zhikun-scratchpad"));

        assertTrue(prompt.contains("将结果视为部分结果"));
        assertTrue(prompt.contains("最多创建一个续作 worker"));
        assertTrue(prompt.contains("只覆盖尚未完成的部分"));
        assertTrue(prompt.contains("不要把原始任务完整重发"));
        assertTrue(prompt.contains("SendMessage 返回 Agent not found"));
        assertTrue(prompt.contains("只有仍处于活动状态的 worker 才能通过 SendMessage 继续"));
    }

    @Test
    @DisplayName("提示词明确区分当前 Project 根目录和 Scratchpad")
    void promptDistinguishesProjectRootFromScratchpad() {
        CoordinatorService coordinatorService = mock(CoordinatorService.class);
        McpClientManager mcpClientManager = mock(McpClientManager.class);
        Path projectRoot = Path.of("/Users/zhikun/Desktop/郭庆涛/测试 zk");
        Path scratchpadRoot = Path.of("/tmp/zhikun-scratchpad/session-1");
        when(coordinatorService.getWorkerToolsContext("session-1"))
                .thenReturn(Map.of("workerToolsContext", "standard tools"));
        when(coordinatorService.getScratchpadDir("session-1"))
                .thenReturn(scratchpadRoot);
        when(mcpClientManager.getConnectedServers()).thenReturn(List.of());

        CoordinatorPromptBuilder builder =
                new CoordinatorPromptBuilder(coordinatorService, mcpClientManager);
        String prompt = builder.buildCoordinatorPromptForProject(
                "session-1", projectRoot);

        assertTrue(prompt.contains("主工作目录：`" + projectRoot + "`"));
        assertTrue(prompt.contains(
                "Worker 共享一个 scratchpad 目录：`" + scratchpadRoot + "`"));
        assertTrue(prompt.contains("用户未明确指定输出位置时，将文件默认写入此目录"));
        assertTrue(prompt.contains("Scratchpad 只用于内部中间文件，不是 Project 根目录；"
                + "它是 Project 外路径规则的明确例外"));
        assertTrue(prompt.contains("不得根据服务端进程目录或 Scratchpad 推断 Project 路径"));
        assertTrue(prompt.contains("除系统提供的 Scratchpad 外，只有用户明确要求时才使用 Project 外路径"));
        assertTrue(prompt.contains("Project 外操作仍受现有权限策略约束，可能被拒绝或要求确认"));
    }

    @Test
    @DisplayName("提示词包含与主模式一致的上下文压缩标记禁令段")
    void promptContainsContextCompressionMarkersSection() {
        CoordinatorService coordinatorService = mock(CoordinatorService.class);
        McpClientManager mcpClientManager = mock(McpClientManager.class);
        when(coordinatorService.getWorkerToolsContext("session-1"))
                .thenReturn(Map.of("workerToolsContext", "standard tools"));
        when(mcpClientManager.getConnectedServers()).thenReturn(List.of());

        CoordinatorPromptBuilder builder =
                new CoordinatorPromptBuilder(coordinatorService, mcpClientManager);
        String prompt = builder.buildCoordinatorPrompt(
                "session-1", Path.of("/tmp/zhikun-scratchpad"));

        assertTrue(prompt.contains("content compressed by system"));
        assertTrue(prompt.contains(
                com.aicodeassistant.prompt.SystemPromptBuilder
                        .CONTEXT_COMPRESSION_MARKERS_SECTION));
    }

}
