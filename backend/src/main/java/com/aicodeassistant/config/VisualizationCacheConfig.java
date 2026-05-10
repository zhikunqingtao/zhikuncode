package com.aicodeassistant.config;

import com.aicodeassistant.engine.VisualizationHint;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Visualization Auto-Routing 相关 Caffeine 缓存配置。
 *
 * <p>对齐 ZhikunCode 差异化升级方案 v1.5 升级项 C（全栈可视化 Beta）：
 * <ul>
 *   <li>缓存 Classifier 判别结果，避免同一会话内重复调用 LLM fast model</li>
 *   <li>键：{@code sha256(sessionId + userQuestion + toolSummary)}</li>
 *   <li>值：{@link VisualizationHint}，null 表示"不可视化"（Caffeine 不支持 null，用特殊空实例）</li>
 * </ul>
 *
 * <p>默认关闭（{@code visualization.auto-routing.enabled=false}），Bean 本身零开销。
 */
@Configuration
public class VisualizationCacheConfig {

    /** 最大条目数 — 10k 条足够单机日常会话容量 */
    private static final long MAX_SIZE = 10_000L;

    /** 写入后过期时间 — 10 分钟覆盖典型对话轮次密度 */
    private static final Duration EXPIRE_AFTER_WRITE = Duration.ofMinutes(10);

    @Bean
    public Cache<String, VisualizationHint> visualizationHintCache() {
        return Caffeine.newBuilder()
                .maximumSize(MAX_SIZE)
                .expireAfterWrite(EXPIRE_AFTER_WRITE)
                .build();
    }
}
