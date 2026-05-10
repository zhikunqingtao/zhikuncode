package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.Command;
import com.aicodeassistant.command.CommandContext;
import com.aicodeassistant.command.CommandResult;
import com.aicodeassistant.command.CommandType;
import com.aicodeassistant.service.browser.BrowserReplayService;
import com.aicodeassistant.service.browser.BrowserSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * /browser-snapshot (别名: /snap) — 采集当前会话浏览器语义快照并追加到 Replay 时间线。
 *
 * <p>对齐 ZhikunCode 差异化升级方案 v1.5 升级项 A MVP。
 *
 * <p>用法:
 * <pre>
 *   /browser-snapshot                     采集整页快照（含缩略图）
 *   /browser-snapshot #main               指定 CSS 选择器采集子树
 *   /browser-snapshot #main no-screenshot 跳过缩略图（节省 WebSocket 流量）
 * </pre>
 *
 * <p>语义：
 * <ul>
 *   <li>Python BROWSER_AUTOMATION 能力不可用时返回 error 提示</li>
 *   <li>成功后返回文本摘要：节点数 + 交互元素数 + 时间线深度</li>
 *   <li>详细数据由前端 {@code BrowserReplayTimeline} 异步拉取渲染</li>
 * </ul>
 */
@Component
public class BrowserSnapshotCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(BrowserSnapshotCommand.class);

    private final BrowserReplayService replayService;

    public BrowserSnapshotCommand(BrowserReplayService replayService) {
        this.replayService = replayService;
    }

    @Override public String getName() { return "browser-snapshot"; }
    @Override public List<String> getAliases() { return List.of("snap"); }
    @Override
    public String getDescription() {
        return "Capture a semantic accessibility snapshot of the current browser session and append to replay timeline";
    }
    @Override public CommandType getType() { return CommandType.LOCAL; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        String sessionId = context.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return CommandResult.error("No active session.");
        }

        String selector = null;
        boolean includeScreenshot = true;
        if (args != null && !args.isBlank()) {
            String[] tokens = args.trim().split("\\s+");
            for (String t : tokens) {
                if ("no-screenshot".equalsIgnoreCase(t) || "--no-screenshot".equalsIgnoreCase(t)) {
                    includeScreenshot = false;
                } else if (selector == null) {
                    selector = t;
                }
            }
        }

        Optional<BrowserSnapshot> snapOpt = replayService.capture(sessionId, selector, includeScreenshot);
        if (snapOpt.isEmpty()) {
            return CommandResult.error(
                    "Browser semantic snapshot unavailable. "
                    + "Ensure Python BROWSER_AUTOMATION capability is active and the session has an open page.");
        }
        BrowserSnapshot snap = snapOpt.get();
        int frames = replayService.getTimeline(sessionId).size();
        String summary = String.format(
                "Browser snapshot captured: url=%s | nodes=%d | interactive=%d | frames=%d%s",
                snap.url(),
                snap.nodeCount(),
                snap.interactive() == null ? 0 : snap.interactive().size(),
                frames,
                selector == null ? "" : " | selector=" + selector
        );
        log.info("/browser-snapshot captured: session={}, snapshotId={}", sessionId, snap.snapshotId());
        return CommandResult.text(summary);
    }
}
