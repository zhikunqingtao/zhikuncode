package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.CommandContext;
import com.aicodeassistant.command.CommandResult;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.state.AppState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * /model 命令行为测试 — 中性模型列表、未知模型错误、已知模型不修改任何状态。
 * <p>
 * 该命令不切换会话模型：真实切换由输入栏模型选择器（/app/model）完成。
 */
class ModelCommandTest {

    private static final List<String> MODELS = List.of("qwen3.7-plus", "gpt-4o");

    private static LlmProviderRegistry registryWith(List<String> models) {
        LlmProviderRegistry providerRegistry = mock(LlmProviderRegistry.class);
        when(providerRegistry.listAvailableModels()).thenReturn(models);
        return providerRegistry;
    }

    private static CommandContext contextWithCurrentModel(String currentModel) {
        return CommandContext.of("session-1", "/tmp/workspace", currentModel, AppState.defaultState());
    }

    @Test
    @DisplayName("无参 → TEXT：列表来自注册表、不含 (current)、引导模型选择器")
    void noArgsListsNeutralModels() {
        ModelCommand command = new ModelCommand(registryWith(MODELS));

        CommandResult result = command.execute("", contextWithCurrentModel("qwen3.7-plus"));

        assertEquals(CommandResult.ResultType.TEXT, result.type());
        assertTrue(result.isSuccess());
        assertTrue(result.value().contains("qwen3.7-plus"));
        assertTrue(result.value().contains("gpt-4o"));
        assertFalse(result.value().contains("(current)"));
        assertTrue(result.value().contains("model selector in the input bar"));
    }

    @Test
    @DisplayName("未知模型 → ERROR，列出可用模型")
    void unknownModelReturnsError() {
        ModelCommand command = new ModelCommand(registryWith(MODELS));

        CommandResult result = command.execute("not-a-model", contextWithCurrentModel(null));

        assertEquals(CommandResult.ResultType.ERROR, result.type());
        assertFalse(result.isSuccess());
        assertTrue(result.error().contains("Unknown model: not-a-model"));
        assertTrue(result.error().contains("qwen3.7-plus"));
    }

    @Test
    @DisplayName("已知模型 → TEXT 诚实回执，不切换会话模型")
    void knownModelReturnsHonestReceiptWithoutSwitching() {
        ModelCommand command = new ModelCommand(registryWith(MODELS));

        CommandResult result = command.execute("gpt-4o", contextWithCurrentModel("qwen3.7-plus"));

        assertEquals(CommandResult.ResultType.TEXT, result.type());
        assertTrue(result.isSuccess());
        assertTrue(result.value().contains("does not switch models"));
        assertTrue(result.value().contains("model selector in the input bar"));
        assertFalse(result.value().contains("switched to"));
    }

    @Test
    @DisplayName("描述为静态文案，不猜测当前模型")
    void descriptionIsStaticAndDoesNotGuessCurrentModel() {
        ModelCommand command = new ModelCommand(registryWith(MODELS));

        String description = command.getDescription();

        assertFalse(description.contains("currently"));
        assertTrue(description.contains("model selector"));
    }
}
