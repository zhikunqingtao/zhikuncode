package com.aicodeassistant.service.browser;

import com.aicodeassistant.service.PythonCapabilityAwareClient;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DOM 语义快照客户端 — ZhikunCode v1.5 升级项 A MVP。
 *
 * <p>对 Python 端 {@code POST /api/browser/snapshot-semantic} 的轻薄封装：
 * <ul>
 *   <li>能力域不可用（BROWSER_AUTOMATION）时静默返回 {@link Optional#empty()}，不抛业务异常</li>
 *   <li>网络 / 协议错误由 {@link PythonCapabilityAwareClient#callIfAvailable} 内建重试 + 降级</li>
 *   <li>成功时将响应 data 归一化为 {@link BrowserSnapshot}，交给 {@link BrowserReplayService} 入缓存</li>
 * </ul>
 *
 * <p>设计要点：
 * <ol>
 *   <li>无状态 — 单例 {@code @Component}，线程安全</li>
 *   <li>snapshotId 由 {@code sessionId + 毫秒时间戳} 组成，单会话内严格单调（时间线顺序天然正确）</li>
 *   <li>不感知缓存，所有存取由 ReplayService 负责 — 单一职责</li>
 * </ol>
 */
@Component
public class DomSnapshotClient {

    private static final Logger log = LoggerFactory.getLogger(DomSnapshotClient.class);
    private static final String CAPABILITY = "BROWSER_AUTOMATION";
    private static final String ENDPOINT = "/api/browser/snapshot-semantic";

    private final PythonCapabilityAwareClient pythonClient;

    public DomSnapshotClient(PythonCapabilityAwareClient pythonClient) {
        this.pythonClient = pythonClient;
    }

    /**
     * 采集指定会话当前页面的语义快照。
     *
     * @param sessionId         浏览器会话 ID，必填
     * @param selector          可选子树 CSS 选择器；null 表示整页
     * @param includeScreenshot 是否同步返回 base64 缩略图
     * @return 快照对象；Python 能力不可用 / 失败时为 {@link Optional#empty()}
     */
    public Optional<BrowserSnapshot> snapshot(String sessionId, String selector, boolean includeScreenshot) {
        if (sessionId == null || sessionId.isBlank()) {
            log.debug("DomSnapshotClient.snapshot: blank sessionId, skip");
            return Optional.empty();
        }

        Map<String, Object> body = new HashMap<>();
        body.put("session_id", sessionId);
        body.put("strict_session", false);
        body.put("interesting_only", true);
        body.put("include_screenshot", includeScreenshot);
        if (selector != null && !selector.isBlank()) {
            body.put("selector", selector);
        }

        Optional<BrowserSnapshotResponse> resp =
                pythonClient.callIfAvailable(CAPABILITY, ENDPOINT, body, BrowserSnapshotResponse.class);
        if (resp.isEmpty()) {
            log.debug("DomSnapshotClient.snapshot: python capability unavailable, session={}", sessionId);
            return Optional.empty();
        }
        BrowserSnapshotResponse r = resp.get();
        if (!r.success() || r.data() == null) {
            log.warn("DomSnapshotClient.snapshot failed: session={}, code={}, msg={}",
                    sessionId, r.errorCode(), r.errorMessage());
            return Optional.empty();
        }
        return Optional.of(normalize(sessionId, selector, r.data()));
    }

    /** 将 Python 端 data dict 归一化为 {@link BrowserSnapshot}。 */
    @SuppressWarnings("unchecked")
    private BrowserSnapshot normalize(String sessionId, String requestedSelector, Map<String, Object> data) {
        String url = stringOrNull(data.get("url"));
        String title = stringOrNull(data.get("title"));
        String selector = stringOrNull(data.get("selector"));
        if (selector == null) {
            selector = requestedSelector;
        }

        int nodeCount = 0;
        Object nc = data.get("node_count");
        if (nc instanceof Number n) {
            nodeCount = n.intValue();
        }

        List<Map<String, Object>> interactive = List.of();
        Object iv = data.get("interactive");
        if (iv instanceof List<?> list) {
            interactive = (List<Map<String, Object>>) list;
        }

        Map<String, Object> tree = Map.of();
        Object tr = data.get("tree");
        if (tr instanceof Map<?, ?> m) {
            tree = (Map<String, Object>) m;
        }

        String screenshot = stringOrNull(data.get("screenshot_base64"));

        long now = System.currentTimeMillis();
        String snapshotId = sessionId + "-" + now;
        return new BrowserSnapshot(
                snapshotId,
                sessionId,
                Instant.ofEpochMilli(now),
                url,
                title,
                selector,
                nodeCount,
                interactive,
                tree,
                screenshot
        );
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 内部响应类型 — Python 端返回统一 BrowserResponse 结构
     * （success/data/error_code/error_message）。
     *
     * <p>与 {@code WebBrowserTool.BrowserResponse} 等价，但为避免跨包依赖内部 record，
     * 本模块自行声明；字段通过 {@link JsonProperty} 显式映射 snake_case。
     */
    public record BrowserSnapshotResponse(
            boolean success,
            Map<String, Object> data,
            @JsonProperty("error_code") String errorCode,
            @JsonProperty("error_message") String errorMessage
    ) {}
}
