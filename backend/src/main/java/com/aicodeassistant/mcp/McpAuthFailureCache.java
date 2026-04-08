package com.aicodeassistant.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 认证失败缓存 — 避免对已知认证失败的服务器重复尝试。
 * <p>
 * 缓存 TTL: 15 分钟。缓存期内跳过认证尝试，直接返回 NEEDS_AUTH 状态。
 *
 * @see <a href="SPEC §4.3.6">MCP 认证失败缓存</a>
 */
@Service
public class McpAuthFailureCache {

    private static final Logger log = LoggerFactory.getLogger(McpAuthFailureCache.class);
    static final Duration AUTH_FAILURE_TTL = Duration.ofMinutes(15);

    private final Map<String, Instant> failureCache = new ConcurrentHashMap<>();

    /**
     * 检查服务器是否在认证失败缓存期内。
     */
    public boolean isAuthFailureCached(String serverId) {
        Instant failedAt = failureCache.get(serverId);
        if (failedAt == null) return false;
        if (Instant.now().isAfter(failedAt.plus(AUTH_FAILURE_TTL))) {
            failureCache.remove(serverId);
            return false;
        }
        return true;
    }

    /** 记录认证失败 */
    public void recordAuthFailure(String serverId) {
        failureCache.put(serverId, Instant.now());
        log.info("MCP auth failure cached for server: {}", serverId);
    }

    /** 清除指定服务器的失败缓存 */
    public void clearFailure(String serverId) {
        failureCache.remove(serverId);
    }

    /** 清除所有缓存 */
    public void clearAll() {
        failureCache.clear();
    }

    /** 缓存大小（测试用） */
    int size() {
        return failureCache.size();
    }
}
