package com.aicodeassistant.service.browser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 浏览器语义快照单帧 — ZhikunCode v1.5 升级项 A MVP。
 *
 * <p>对齐 {@code /api/browser/snapshot-semantic} 响应数据结构，
 * 由 {@link DomSnapshotClient} 将 Python 端返回的 {@code BrowserResponse.data} 归一化为本 record。
 *
 * <p>不落库、不写文件系统；仅存活在 {@link BrowserReplayService} 的 Caffeine 缓存内。
 *
 * @param snapshotId     快照 ID（sessionId + 毫秒时间戳，同一会话内单调）
 * @param sessionId      所属会话
 * @param capturedAt     采集时间戳
 * @param url            当前页面 URL
 * @param title          当前页面标题
 * @param selector       根选择器（null 表示整页）
 * @param nodeCount      语义树节点总数
 * @param interactive    交互元素清单（role/name/value/disabled），最多 200 个
 * @param tree           原始语义 DOM 树（Playwright accessibility snapshot 输出）
 * @param screenshotBase64 可选缩略图（base64 PNG），前端 Replay 缩略渲染用
 */
public record BrowserSnapshot(
        String snapshotId,
        String sessionId,
        Instant capturedAt,
        String url,
        String title,
        String selector,
        int nodeCount,
        List<Map<String, Object>> interactive,
        Map<String, Object> tree,
        String screenshotBase64
) {
}
