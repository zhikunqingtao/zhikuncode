package com.aicodeassistant.config;

import com.aicodeassistant.service.browser.BrowserSnapshot;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * 浏览器 Replay 时间线缓存配置 — ZhikunCode v1.5 升级项 A MVP。
 *
 * <p>缓存策略：
 * <ul>
 *   <li>{@code expireAfterWrite(10min)} — 会话结束后 10 分钟自动回收，防止内存泄漏</li>
 *   <li>{@code maximumSize(200)} — 上限 200 个活跃会话，LRU 淘汰</li>
 *   <li>value 为 {@link List}&lt;{@link BrowserSnapshot}&gt;，按时间顺序追加</li>
 * </ul>
 *
 * <p>红线：Replay 数据仅驻留内存，不落库、不写文件系统。
 */
@Configuration
public class BrowserReplayConfig {

    /** 最大会话数 — 200 足以覆盖单机日常浏览器自动化压力 */
    private static final long MAX_SIZE = 200L;

    /** 写入后过期时间 — 10 分钟覆盖典型浏览器会话生命周期 */
    private static final Duration EXPIRE_AFTER_WRITE = Duration.ofMinutes(10);

    @Bean
    public Cache<String, List<BrowserSnapshot>> browserReplayCache() {
        return Caffeine.newBuilder()
                .maximumSize(MAX_SIZE)
                .expireAfterWrite(EXPIRE_AFTER_WRITE)
                .build();
    }
}
