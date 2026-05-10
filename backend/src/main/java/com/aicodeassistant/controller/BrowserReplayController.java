package com.aicodeassistant.controller;

import com.aicodeassistant.service.browser.BrowserReplayService;
import com.aicodeassistant.service.browser.BrowserSnapshot;
import com.aicodeassistant.websocket.WebSocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 浏览器 Replay REST Controller — ZhikunCode v1.5 升级项 A MVP。
 *
 * <p>前端 {@code BrowserReplayTimeline} 通过 {@code GET /api/browser/replay/{sessionId}}
 * 拉取会话快照时间线。
 *
 * <p>数据源：{@link BrowserReplayService} 的内存 Caffeine 缓存（不落库）。
 *
 * <p><b>安全红线（P2-A 修复）</b>：
 * <ul>
 *   <li>路径参数 {@code sessionId} 经正则白名单校验，防止注入/路径穿越副作用（非法返回 400）。</li>
 *   <li>若 sessionId 已被 {@link WebSocketSessionManager} 绑定 principal，
 *       仅绑定方可读取/清空其时间线，跨用户访问返回 403，防止跨用户数据泄露。</li>
 *   <li>未绑定 principal 的 sessionId（本地/测试 / 匿名采集）保持允许访问，维持 MVP 兼容。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/browser/replay")
public class BrowserReplayController {

    private static final Logger log = LoggerFactory.getLogger(BrowserReplayController.class);

    /**
     * sessionId 白名单：字母 / 数字 / 下划线 / 中划线，长度 1~128。
     * 与 {@link com.aicodeassistant.coordinator.CoordinatorService} getScratchpadDir 保持一致。
     */
    private static final Pattern SAFE_SESSION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    private final BrowserReplayService replayService;
    private final WebSocketSessionManager sessionManager;

    public BrowserReplayController(BrowserReplayService replayService,
                                   WebSocketSessionManager sessionManager) {
        this.replayService = replayService;
        this.sessionManager = sessionManager;
    }

    /**
     * 获取指定会话的快照时间线（按采集时间升序，不存在返回空数组）。
     *
     * @return 200 正常数据；400 sessionId 非法；403 不属于当前 principal
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<List<BrowserSnapshot>> getTimeline(@PathVariable String sessionId,
                                                             Principal principal) {
        if (!isSessionIdSafe(sessionId)) {
            log.warn("BrowserReplay GET rejected: invalid sessionId format");
            return ResponseEntity.badRequest().build();
        }
        if (!isAuthorized(sessionId, principal)) {
            log.warn("BrowserReplay GET forbidden: sessionId bound to another principal");
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(replayService.getTimeline(sessionId));
    }

    /**
     * 清空指定会话的时间线（手动释放资源）。
     *
     * @return 204 清空成功；400 sessionId 非法；403 不属于当前 principal
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> clear(@PathVariable String sessionId,
                                      Principal principal) {
        if (!isSessionIdSafe(sessionId)) {
            log.warn("BrowserReplay DELETE rejected: invalid sessionId format");
            return ResponseEntity.badRequest().build();
        }
        if (!isAuthorized(sessionId, principal)) {
            log.warn("BrowserReplay DELETE forbidden: sessionId bound to another principal");
            return ResponseEntity.status(403).build();
        }
        replayService.clear(sessionId);
        return ResponseEntity.noContent().build();
    }

    private boolean isSessionIdSafe(String sessionId) {
        return sessionId != null && SAFE_SESSION_ID_PATTERN.matcher(sessionId).matches();
    }

    /**
     * principal 归属校验：
     * <ul>
     *   <li>sessionId 未绑定任何 principal → 允许（MVP 兼容匿名采集 / 本地调试）</li>
     *   <li>已绑定 principal 且与请求者匹配 → 允许</li>
     *   <li>已绑定 principal 且不匹配（含请求者为匿名 null）→ 拒绝</li>
     * </ul>
     */
    private boolean isAuthorized(String sessionId, Principal principal) {
        String bound = sessionManager.getPrincipalForSession(sessionId);
        if (bound == null) {
            return true;
        }
        return principal != null && bound.equals(principal.getName());
    }
}
