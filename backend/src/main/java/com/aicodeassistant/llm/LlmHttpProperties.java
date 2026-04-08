package com.aicodeassistant.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LLM HTTP 客户端配置 — OkHttp 连接池参数外部化。
 * <p>
 * 通过 application.yml 的 llm.http 前缀配置，支持生产环境调优。
 *
 * @see <a href="SPEC §3.1.3">流式处理</a>
 */
@ConfigurationProperties(prefix = "llm.http")
public record LlmHttpProperties(
        @DefaultValue PoolProperties pool,
        @DefaultValue("10") int connectTimeoutSeconds,
        @DefaultValue("10") int writeTimeoutSeconds,
        @DefaultValue("true") boolean retryOnFailure
) {
    public record PoolProperties(
            @DefaultValue("5") int maxIdleConnections,
            @DefaultValue("30") int keepAliveSeconds
    ) {}
}
