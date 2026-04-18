package com.aicodeassistant.service;

import com.aicodeassistant.tool.Tool;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 提示缓存断裂检测器 (§11.3.6)。
 * <p>
 * 追踪每次请求的 system prompt 和 tools 列表的哈希值，
 * 当哈希值与上一次不同时，判定为缓存断裂事件。
 * <p>
 * 用途: 帮助诊断不必要的缓存断裂，优化 API 调用成本。
 */
@Component
public class PromptCacheBreakDetector {

    private static final Logger log = LoggerFactory.getLogger(PromptCacheBreakDetector.class);

    /**
     * 会话级缓存状态。
     */
    private record SessionCacheState(String previousSystemHash, String previousToolsHash) {}

    /**
     * 缓存断裂检测结果。
     *
     * @param systemBreak system prompt 哈希发生变化
     * @param toolsBreak  tools 列表哈希发生变化
     * @param firstCall   是否为会话首次调用（无前序哈希可比较）
     */
    public record CacheBreakResult(boolean systemBreak, boolean toolsBreak, boolean firstCall) {
        public boolean hasBreak() {
            return !firstCall && (systemBreak || toolsBreak);
        }
    }

    /** 会话ID → 上次缓存状态 */
    private final ConcurrentHashMap<String, SessionCacheState> sessionStates = new ConcurrentHashMap<>();

    /** Caffeine 缓存：工具定义哈希，用于检测工具列表变更 */
    private final Cache<String, String> toolDefinitionHashCache = Caffeine.newBuilder()
            .maximumSize(200)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    /** 上一次工具集哈希，用于全局变更检测 */
    private volatile String lastToolSetHash = null;

    /**
     * 检测提示缓存是否断裂。
     * <p>
     * 计算当前 system prompt 和 tools 列表的 SHA-256 哈希，
     * 与会话上一次的哈希进行比较。
     *
     * @param sessionId    会话ID
     * @param systemPrompt 当前 system prompt 内容
     * @param toolNames    当前可用工具名称列表
     * @return 检测结果
     */
    public CacheBreakResult detectBreak(String sessionId, String systemPrompt, List<String> toolNames) {
        String currentSystemHash = sha256(systemPrompt != null ? systemPrompt : "");
        String currentToolsHash = sha256(toolNames != null ? String.join(",", toolNames) : "");

        SessionCacheState previous = sessionStates.get(sessionId);

        // 更新状态
        sessionStates.put(sessionId, new SessionCacheState(currentSystemHash, currentToolsHash));

        if (previous == null) {
            return new CacheBreakResult(false, false, true);
        }

        boolean systemBreak = !currentSystemHash.equals(previous.previousSystemHash());
        boolean toolsBreak = !currentToolsHash.equals(previous.previousToolsHash());

        if (systemBreak || toolsBreak) {
            log.info("Cache break detected in session {}: system={}, tools={}",
                    sessionId, systemBreak, toolsBreak);
        }

        return new CacheBreakResult(systemBreak, toolsBreak, false);
    }

    /**
     * 清除会话的缓存状态 — 会话结束时调用。
     *
     * @param sessionId 会话ID
     */
    public void clearSession(String sessionId) {
        sessionStates.remove(sessionId);
    }

    /**
     * 获取当前追踪的会话数量。
     */
    public int trackedSessionCount() {
        return sessionStates.size();
    }

    /**
     * 计算内建工具和MCP工具之间的缓存断点位置。
     * <p>
     * 断点位置 = 内建工具数量（即MCP工具起始索引）。
     * 调用方应在此位置的前一个 tool definition 上添加 cache_control 标记。
     *
     * @param sortedTools 已排序的工具列表（内建在前，MCP在后）
     * @return 断点位置索引，-1 表示无MCP工具（无需断点）
     */
    public int computeBreakpointPosition(List<Tool> sortedTools) {
        int breakpoint = -1;
        for (int i = 0; i < sortedTools.size(); i++) {
            if (sortedTools.get(i).isMcp()) {
                breakpoint = i;
                break;
            }
        }
        return breakpoint;
    }

    /**
     * 检测工具集是否发生变更（基于 Caffeine 缓存的哈希对比）。
     * <p>
     * 对当前工具定义计算 SHA-256 哈希，与缓存中的哈希比较。
     * 不一致时表示工具集发生变更（如MCP服务器新增/移除工具）。
     *
     * @param sessionId 会话ID
     * @param tools     当前工具列表
     * @return true 表示工具集发生变更
     */
    public boolean hasToolSetChanged(String sessionId, List<Tool> tools) {
        String currentHash = computeToolDefinitionsHash(tools);
        String cacheKey = "toolset::" + sessionId;
        String cachedHash = toolDefinitionHashCache.getIfPresent(cacheKey);
        toolDefinitionHashCache.put(cacheKey, currentHash);

        if (cachedHash == null) {
            return false; // 首次调用，无前序哈希可比较
        }

        boolean changed = !currentHash.equals(cachedHash);
        if (changed) {
            log.info("Tool set changed in session {}: old={}, new={}",
                    sessionId, cachedHash.substring(0, 8), currentHash.substring(0, 8));
        }
        return changed;
    }

    /**
     * 计算工具定义列表的 SHA-256 哈希。
     * 基于工具名称 + 描述 + schema 的组合哈希。
     */
    private String computeToolDefinitionsHash(List<Tool> tools) {
        String content = tools.stream()
                .map(t -> t.getName() + ":" + t.getDescription() + ":" + t.getInputSchema().hashCode())
                .collect(Collectors.joining("|"));
        return sha256(content);
    }

    /**
     * SHA-256 哈希工具方法。
     */
    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in all JVMs
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
