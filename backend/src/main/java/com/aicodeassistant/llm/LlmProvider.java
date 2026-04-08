package com.aicodeassistant.llm;

import java.util.List;
import java.util.Map;

/**
 * LLM Provider 接口 — 统一多模型供应商的 API 调用。
 * <p>
 * 支持的供应商: OpenAI/Azure、Ollama (本地)、通义千问等。
 * 每个供应商实现各自的请求/响应格式转换，对上层透明。
 * <p>
 * 【架构裁决 #1】使用回调模式替代 Reactor Flux。
 * streamChat() 在当前线程阻塞直到流结束，配合 Virtual Threads 使用。
 *
 * @see <a href="SPEC §3.1.3">流式处理</a>
 */
public interface LlmProvider {

    /** 供应商标识 */
    String getProviderName();

    /** 支持的模型列表 */
    List<String> getSupportedModels();

    // ==================== 核心调用 ====================

    /**
     * 流式调用 LLM API (强类型版本)。
     * 方法阻塞直到流结束（在 Virtual Thread 中执行）。
     *
     * @param model          模型名称
     * @param messages       消息列表 (强类型)
     * @param systemPrompt   系统提示 (强类型，支持分段+缓存控制)
     * @param tools          工具定义列表 (强类型)
     * @param maxTokens      最大输出 Token
     * @param thinkingConfig 思考模式配置
     * @param callback       流式回调接口
     */
    default void streamChat(
            String model,
            List<MessageParam> messages,
            SystemPrompt systemPrompt,
            List<ToolDefinition> tools,
            int maxTokens,
            ThinkingConfig thinkingConfig,
            StreamChatCallback callback
    ) {
        // 默认桥接: 将强类型转为 Map 后调用旧签名
        List<Map<String, Object>> mapMessages = MessageParamConverter.toMaps(messages);
        List<Map<String, Object>> mapTools = tools.stream()
                .map(ToolDefinition::toAnthropicFormat)
                .toList();
        streamChat(model, mapMessages, systemPrompt.toPlainText(), mapTools,
                maxTokens, thinkingConfig, callback);
    }

    /**
     * 流式调用 LLM API (Map 格式，向后兼容)。
     * 新代码应使用强类型版本 {@link #streamChat(String, List, SystemPrompt, List, int, ThinkingConfig, StreamChatCallback)}。
     *
     * @deprecated 使用强类型版本替代
     */
    @Deprecated
    void streamChat(
            String model,
            List<Map<String, Object>> messages,
            String systemPrompt,
            List<Map<String, Object>> tools,
            int maxTokens,
            ThinkingConfig thinkingConfig,
            StreamChatCallback callback
    );

    /** 中断当前请求 */
    void abort();

    /**
     * 同步调用 LLM — 用于分类器等低延迟场景。
     * 不使用流式，直接返回完整响应文本。
     * <p>
     * 各 Provider 应覆写此方法以支持 stopSequences（使用非流式端点）。
     * 默认实现通过 streamChat 收集完整响应，但不支持 stopSequences。
     *
     * @param model         模型名称
     * @param systemPrompt  系统提示
     * @param userContent   用户内容
     * @param maxTokens     最大输出 token
     * @param stopSequences 停止序列（可为null）
     * @param timeoutMs     超时毫秒数
     * @return LLM 响应文本
     */
    default String chatSync(String model, String systemPrompt, String userContent,
                            int maxTokens, String[] stopSequences, long timeoutMs) {
        if (stopSequences != null && stopSequences.length > 0) {
            throw new UnsupportedOperationException(
                    "Provider does not support chatSync with stopSequences. " +
                    "Override chatSync() in your LlmProvider implementation.");
        }
        StringBuilder response = new StringBuilder();
        streamChat(model,
                List.of(Map.of("role", "user", "content", userContent)),
                systemPrompt,
                List.of(),
                maxTokens,
                null,
                new StreamChatCallback() {
                    @Override public void onEvent(LlmStreamEvent event) {
                        if (event instanceof LlmStreamEvent.TextDelta delta) {
                            response.append(delta.text());
                        }
                    }
                    @Override public void onComplete() {}
                    @Override public void onError(Throwable error) {}
                });
        return response.toString();
    }

    // ==================== 模型能力查询 ====================

    /** 获取默认/推荐模型 */
    String getDefaultModel();

    /** 获取快速/轻量模型 — 用于分类器、摘要等低延迟场景 */
    default String getFastModel() { return null; }

    /** 查询模型的能力信息 */
    ModelCapabilities getModelCapabilities(String model);

    // ==================== 能力查询快捷方法 ====================

    default boolean supportsThinking(String model) {
        return getModelCapabilities(model).supportsThinking();
    }

    default boolean supportsToolUse(String model) {
        return getModelCapabilities(model).supportsToolUse();
    }

    default boolean supportsImages(String model) {
        return getModelCapabilities(model).supportsImages();
    }

    // ==================== 缓存能力 ====================

    default boolean supportsCaching() { return false; }

    // ==================== Token 计数 (P2) ====================

    default boolean supportsTokenCounting() { return false; }

    default int estimateTokenCount(List<Map<String, Object>> messages, String systemPrompt) {
        return -1; // P0: 依赖 API 返回的 usage
    }
}
