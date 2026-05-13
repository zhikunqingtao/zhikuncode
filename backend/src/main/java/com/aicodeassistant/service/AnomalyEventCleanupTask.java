package com.aicodeassistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 异常事件定时清理任务。
 * 每小时执行一次，删除 30 天前已解决的事件，保留未解决事件不清理。
 */
@Component
public class AnomalyEventCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(AnomalyEventCleanupTask.class);
    private static final long RETENTION_DAYS = 30;

    private final AnomalyEventRepository anomalyEventRepository;

    public AnomalyEventCleanupTask(AnomalyEventRepository anomalyEventRepository) {
        this.anomalyEventRepository = anomalyEventRepository;
    }

    @Scheduled(fixedRate = 3600_000) // 每小时
    public void cleanupExpiredEvents() {
        long cutoffMs = System.currentTimeMillis() - (RETENTION_DAYS * 24 * 60 * 60 * 1000L);
        int deleted = anomalyEventRepository.deleteResolvedBefore(cutoffMs);
        if (deleted > 0) {
            log.info("Cleaned up {} expired anomaly events (resolved before {})", deleted, cutoffMs);
        }
    }
}
