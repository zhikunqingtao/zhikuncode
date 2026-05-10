package com.aicodeassistant.service.browser;

import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 浏览器 Replay 时间线服务 — ZhikunCode v1.5 升级项 A MVP。
 *
 * <p>职责：
 * <ol>
 *   <li>对指定会话采集一帧语义快照，追加到缓存时间线</li>
 *   <li>按会话暴露只读时间线（前端 {@code BrowserReplayTimeline} 消费）</li>
 *   <li>支持显式清空单会话（{@link #clear(String)}）</li>
 * </ol>
 *
 * <p>并发模型：单会话列表写入 via {@code synchronized}，读取返回不可变副本。
 * 简单模型足以应对典型浏览器自动化频率（每 click/navigate 一帧）。
 *
 * <p>红线：内存级缓存（Caffeine），不落库、不写文件系统；
 * {@code expireAfterWrite(10min) + maximumSize(200)} 由 {@link com.aicodeassistant.config.BrowserReplayConfig} 提供。
 */
@Service
public class BrowserReplayService {

    private static final Logger log = LoggerFactory.getLogger(BrowserReplayService.class);

    /** 单会话最多保留 100 帧，防止恶意频繁采集撑爆内存 */
    private static final int MAX_FRAMES_PER_SESSION = 100;

    private final DomSnapshotClient snapshotClient;
    private final Cache<String, List<BrowserSnapshot>> cache;

    public BrowserReplayService(DomSnapshotClient snapshotClient,
                                Cache<String, List<BrowserSnapshot>> browserReplayCache) {
        this.snapshotClient = snapshotClient;
        this.cache = browserReplayCache;
    }

    /**
     * 采集一帧快照并追加到时间线。
     *
     * @param sessionId         浏览器会话 ID
     * @param selector          根选择器（null 表示整页）
     * @param includeScreenshot 是否同步返回缩略图
     * @return 成功采集到的快照；失败或能力不可用返回 empty
     */
    public Optional<BrowserSnapshot> capture(String sessionId, String selector, boolean includeScreenshot) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Optional<BrowserSnapshot> snap = snapshotClient.snapshot(sessionId, selector, includeScreenshot);
        snap.ifPresent(s -> append(sessionId, s));
        return snap;
    }

    /**
     * 获取指定会话的只读时间线（按采集时间升序）。
     * 不存在的会话返回空列表。
     */
    public List<BrowserSnapshot> getTimeline(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<BrowserSnapshot> list = cache.getIfPresent(sessionId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return List.copyOf(list);
        }
    }

    /**
     * 清空指定会话的时间线（手动释放资源）。
     */
    public void clear(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            cache.invalidate(sessionId);
        }
    }

    /**
     * 快照加入时间线，超过 {@link #MAX_FRAMES_PER_SESSION} 则丢弃最旧帧（FIFO）。
     */
    private void append(String sessionId, BrowserSnapshot snapshot) {
        List<BrowserSnapshot> list = cache.get(sessionId, k -> Collections.synchronizedList(new ArrayList<>()));
        if (list == null) {
            return; // 理论不可能，Caffeine get-with-loader 保证非 null
        }
        synchronized (list) {
            list.add(snapshot);
            while (list.size() > MAX_FRAMES_PER_SESSION) {
                list.remove(0);
            }
        }
        log.debug("BrowserReplay append: session={}, frames={}, url={}",
                sessionId, list.size(), snapshot.url());
    }
}
