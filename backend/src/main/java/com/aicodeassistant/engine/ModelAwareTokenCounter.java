package com.aicodeassistant.engine;

import com.aicodeassistant.llm.ModelRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import com.aicodeassistant.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型感知 Token 计数器 — 多模型 Token 估算适配层。
 * <p>
 * 基于不同模型的 tokenCharRatio 进行精准估算，并对中文内容进行修正。
 * <p>
 * 设计原则：
 * - 组合 TokenCounter 和唯一的 ModelRegistry
 * - 不改变 TokenCounter 的原有行为（向后兼容）
 * - 提供基于 modelId 的增强估算能力
 */
@Component
public class ModelAwareTokenCounter {

    private static final Logger log = LoggerFactory.getLogger(ModelAwareTokenCounter.class);

    private final ModelRegistry registry;
    private final TokenCounter tokenCounter;

    @Autowired public ModelAwareTokenCounter(ModelRegistry registry,TokenCounter tokenCounter){this.registry=registry;this.tokenCounter=tokenCounter;}

    /**
     * 基于模型的单文本 Token 估算。
     * <p>
     * 算法：
     * 1. 获取模型的 tokenCharRatio
     * 2. 计算基础估算值 = content.length() / ratio
     * 3. 中文修正：中文字符占比 > 30% 时，降低 ratio（中文 Token 密度更高）
     *
     * @param content 文本内容
     * @param modelId 模型标识
     * @return 估算 Token 数
     */
    public long estimateTokens(String content, String modelId) {
        if (content == null || content.isEmpty()) return 0;

        double ratio = registry.getTokenCharRatio(modelId);

        // 中文内容修正
        double chineseRatio = calculateChineseRatio(content);
        if (chineseRatio > 0.3) {
            // 中文 Token 密度更高，降低 ratio 使估算值增大
            double adjustedRatio = ratio * (1.0 - chineseRatio * 0.3);
            return (long) (content.length() / adjustedRatio);
        }

        return (long) (content.length() / ratio);
    }

    /**
     * 基于模型的消息列表 Token 估算。
     *
     * @param messages 消息列表
     * @param modelId  模型标识
     * @return 估算 Token 数
     */
    public long estimateTokens(List<Message> messages, String modelId) {
        if (messages == null || messages.isEmpty()) return 0;

        return tokenCounter.estimateTokens(messages, modelId);
    }

    /**
     * 获取模型的上下文窗口大小。
     */
    public int getContextWindow(String modelId) {
        return registry.getContextWindowForModel(modelId);
    }

    /**
     * 获取模型的最大输出 Token 数。
     */
    public int getOutputMaxTokens(String modelId) {
        return registry.getMaxOutputTokensForModel(modelId);
    }

    /**
     * 计算中文字符占比。
     *
     * @param content 文本内容
     * @return 中文字符占比 (0.0 ~ 1.0)
     */
    private double calculateChineseRatio(String content) {
        if (content == null || content.isEmpty()) return 0.0;

        // 仅取前 2000 字符采样，避免大文本性能问题
        String sample = content.length() > 2000 ? content.substring(0, 2000) : content;

        long chineseChars = sample.codePoints()
                .filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
                .count();

        return (double) chineseChars / sample.length();
    }
}
