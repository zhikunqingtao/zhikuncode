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
     * 流式调用 LLM API — 返回统一的 StreamEvent 流。
     * 方法阻塞直到流结束（在 Virtual Thread 中执行）。
     *
     * @param model          模型名称
     * @param messages       消息列表（Map 格式，由 Provider 转换为供应商格式）
     * @param systemPrompt   系统提示
     * @param tools          工具定义列表
     * @param maxTokens      最大输出 Token
     * @param thinkingConfig 思考模式配置
     * @param callback       流式回调接口
     */
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
