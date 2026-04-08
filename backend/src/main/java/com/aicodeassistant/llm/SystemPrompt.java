package com.aicodeassistant.llm;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 系统提示强类型 — 支持分段 + 缓存控制。
 * <p>
 * 部分 LLM Provider 支持对系统提示的分段缓存（如 Anthropic 的 cache_control），
 * 此 record 允许标记哪些段可缓存。
 *
 * @param segments 提示分段列表
 */
public record SystemPrompt(List<Segment> segments) {

    /**
     * 单段系统提示。
     */
    public record Segment(String text, boolean cacheControl) {
        public Segment(String text) {
            this(text, false);
        }
    }

    /**
     * 转换为纯文本（所有段拼接）。
     */
    public String toPlainText() {
        return segments.stream()
                .map(Segment::text)
                .collect(Collectors.joining("\n"));
    }

    /**
     * 从纯文本创建单段系统提示。
     */
    public static SystemPrompt of(String text) {
        return new SystemPrompt(List.of(new Segment(text, false)));
    }

    /**
     * 从纯文本创建带缓存控制的单段系统提示。
     */
    public static SystemPrompt ofCached(String text) {
        return new SystemPrompt(List.of(new Segment(text, true)));
    }
}
