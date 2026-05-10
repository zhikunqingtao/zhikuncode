package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Visualization Auto-Routing 意图分类器 — 三道闸门。
 *
 * <p>对齐 ZhikunCode 差异化升级方案 v1.5 升级项 C（全栈可视化 Beta）。
 *
 * <p><b>三道闸门</b>（全部放行才调 fast-model）：
 * <ol>
 *   <li>开关闸门：{@code visualization.auto-routing.enabled} — 默认 {@code false}，零开销直返</li>
 *   <li>轻量闸门：关键词正则命中 <b>或</b> 当前轮存在 {@code tool_use/tool_result}</li>
 *   <li>缓存闸门：{@code sha256(sessionId + userQuestion + toolSummary)} — 10 分钟 TTL，
 *       空哨兵也会被缓存避免重复 LLM 调用</li>
 * </ol>
 *
 * <p><b>静默失败</b>：LLM 超时/解析失败均返回 {@link VisualizationHint#EMPTY}，
 * 用户侧无感知（仅 {@code log.debug}）。
 */
@Service
public class VisualizationIntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(VisualizationIntentClassifier.class);

    /** 关键词闸门（修订版 v1.5）：触发词命中即放行到缓存/LLM 层 */
    private static final Pattern KEYWORD_PATTERN = Pattern.compile(
            "(?i)(图|图表|可视化|时间线|架构|流程|工作流|依赖|调用链|热度|复杂度|"
                    + "schema|ddl|er|diagram|mermaid|timeline|graph|tree|api|端点|接口)");

    /** 摘要字符数上限 — 限制 LLM 输入体积与缓存键熵 */
    private static final int SUMMARY_CHAR_LIMIT = 512;

    /** 最大输出 token — 仅需返回一个 JSON 对象 */
    private static final int MAX_TOKENS = 256;

    /** LLM 超时 — side-query 风格的 15s */
    private static final long TIMEOUT_MS = 15_000L;

    private static final String SYSTEM_PROMPT = """
            You are a visualization intent classifier. Given a user question and the latest tool execution summary,
            decide whether a visualization view fits. Reply ONLY with a compact JSON object. DO NOT add prose.

            Schema:
            { "viewType": string, "dataSource": string, "params": object }

            Allowed viewType values (pick one; empty string = no visualization):
            - "git-timeline"          (git history, commit flow)
            - "schema-viewer"         (database schema, tables, ER)
            - "change-impact-graph"   (refactor / impact of a change)
            - "code-path-tracer"      (call chain, execution path)
            - "code-complexity-treemap" (hotspots, LOC, complexity)
            - "api-sequence-diagram"  (API/service interaction sequence)
            - "mermaid"               (generic flow/sequence/state, emit mermaid source in params.source)
            - ""                      (no fit)

            Rules:
            - If uncertain, return { "viewType": "", "dataSource": "", "params": {} }.
            - Keep params small (<= 8 keys). Do not invent data values.
            """;

    private final SideQueryService sideQueryService;
    private final ObjectMapper objectMapper;
    private final Cache<String, VisualizationHint> cache;

    private final boolean autoRoutingEnabled;

    public VisualizationIntentClassifier(
            SideQueryService sideQueryService,
            ObjectMapper objectMapper,
            Cache<String, VisualizationHint> visualizationHintCache,
            @Value("${visualization.auto-routing.enabled:false}") boolean autoRoutingEnabled
    ) {
        this.sideQueryService = sideQueryService;
        this.objectMapper = objectMapper;
        this.cache = visualizationHintCache;
        this.autoRoutingEnabled = autoRoutingEnabled;
    }

    /**
     * 分类入口 — 返回 {@link VisualizationHint#EMPTY} 表示不可视化。
     *
     * @param sessionId    会话 ID（缓存键组成）
     * @param userQuestion 最新用户问题
     * @param messages     当前 QueryLoopState 消息列表（用于判定 toolExecution 与 toolSummary）
     * @return 非 null 的 {@link VisualizationHint}
     */
    public VisualizationHint classify(String sessionId, String userQuestion, List<Message> messages) {
        // ===== 闸门 1：全局开关 =====
        if (!autoRoutingEnabled) {
            return VisualizationHint.EMPTY;
        }

        if (sessionId == null || userQuestion == null || userQuestion.isBlank()) {
            return VisualizationHint.EMPTY;
        }

        String toolSummary = summarizeLatestToolExchange(messages);
        boolean keywordHit = KEYWORD_PATTERN.matcher(userQuestion).find();
        boolean hasToolExecution = !toolSummary.isEmpty();

        // ===== 闸门 2：关键词 OR 当前轮工具执行 =====
        if (!keywordHit && !hasToolExecution) {
            return VisualizationHint.EMPTY;
        }

        // ===== 闸门 3：sha256(sessionId + userQuestion + toolSummary) =====
        String cacheKey = buildCacheKey(sessionId, userQuestion, toolSummary);
        if (cacheKey == null) {
            return VisualizationHint.EMPTY;
        }

        VisualizationHint cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        // ===== LLM 判别 =====
        VisualizationHint hint = invokeClassifier(userQuestion, toolSummary);
        cache.put(cacheKey, hint);
        return hint;
    }

    // ==================== internal ====================

    /**
     * 构建轻量 toolSummary：取最后一条 assistant 的 tool_use + 相邻 user 消息的 tool_result。
     * 仅用于 LLM 上下文，非持久化；超出 {@link #SUMMARY_CHAR_LIMIT} 字符会截断。
     */
    private String summarizeLatestToolExchange(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        String latestToolUse = null;
        String latestToolResult = null;

        for (int i = messages.size() - 1; i >= 0 && (latestToolUse == null || latestToolResult == null); i--) {
            Message m = messages.get(i);
            if (m instanceof Message.AssistantMessage am && latestToolUse == null) {
                for (ContentBlock cb : am.content()) {
                    if (cb instanceof ContentBlock.ToolUseBlock tu) {
                        latestToolUse = tu.name();
                        break;
                    }
                }
            } else if (m instanceof Message.UserMessage um && latestToolResult == null) {
                for (ContentBlock cb : um.content()) {
                    if (cb instanceof ContentBlock.ToolResultBlock tr) {
                        latestToolResult = tr.content();
                        break;
                    }
                }
            }
        }

        if (latestToolUse == null && latestToolResult == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        if (latestToolUse != null) {
            sb.append("lastTool=").append(latestToolUse);
        }
        if (latestToolResult != null) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("lastResult=").append(truncate(latestToolResult, SUMMARY_CHAR_LIMIT));
        }
        return sb.toString();
    }

    private String buildCacheKey(String sessionId, String userQuestion, String toolSummary) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(sessionId.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(userQuestion.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(toolSummary.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            log.debug("SHA-256 unavailable, skip auto-routing: {}", e.getMessage());
            return null;
        }
    }

    private VisualizationHint invokeClassifier(String userQuestion, String toolSummary) {
        String userContent = "USER_QUESTION:\n" + truncate(userQuestion, SUMMARY_CHAR_LIMIT)
                + (toolSummary.isEmpty() ? "" : "\n\nTOOL_SUMMARY:\n" + toolSummary);
        String raw;
        try {
            raw = sideQueryService.query(SYSTEM_PROMPT, userContent, MAX_TOKENS, TIMEOUT_MS);
        } catch (RuntimeException e) {
            log.debug("Visualization classifier LLM error: {}", e.getMessage());
            return VisualizationHint.EMPTY;
        }
        if (raw == null || raw.isBlank()) {
            return VisualizationHint.EMPTY;
        }
        return parseHint(raw);
    }

    @SuppressWarnings("unchecked")
    private VisualizationHint parseHint(String raw) {
        String cleaned = stripMarkdownFence(raw.strip());
        try {
            JsonNode node = objectMapper.readTree(cleaned);
            String viewType = node.path("viewType").asText("");
            String dataSource = node.path("dataSource").asText("");
            Map<String, Object> params;
            JsonNode paramsNode = node.path("params");
            if (paramsNode.isObject()) {
                params = objectMapper.convertValue(paramsNode, Map.class);
                if (params == null) params = new LinkedHashMap<>();
            } else {
                params = new LinkedHashMap<>();
            }
            if (viewType == null || viewType.isBlank()) {
                return VisualizationHint.EMPTY;
            }
            return new VisualizationHint(viewType, dataSource, params);
        } catch (Exception e) {
            log.debug("Visualization classifier parse failed: raw={}, err={}", truncate(cleaned, 200), e.getMessage());
            return VisualizationHint.EMPTY;
        }
    }

    private static String stripMarkdownFence(String s) {
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        return s.strip();
    }

    private static String truncate(String s, int limit) {
        if (s == null) return "";
        return s.length() <= limit ? s : s.substring(0, limit);
    }
}
