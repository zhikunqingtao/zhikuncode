package com.aicodeassistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cron 定时任务服务 — 管理用户创建的 cron 定时任务。
 *
 * <p>支持内存存储（默认）和文件持久化（durable 模式）。</p>
 * <p>过期任务（30 天）自动清理。</p>
 *
 * @see <a href="§10.4 B2">ScheduleCronTool 设计</a>
 */
@Service
public class CronTaskService {

    private static final Logger log = LoggerFactory.getLogger(CronTaskService.class);
    private static final int MAX_JOBS = 50;
    private static final int DEFAULT_EXPIRY_DAYS = 30;

    private final ConcurrentHashMap<String, CronTask> tasks = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Path durableStorePath;

    /**
     * Cron 定时任务记录。
     */
    public record CronTask(
            String id,
            String cron,
            String prompt,
            boolean recurring,
            boolean durable,
            Instant createdAt,
            Instant expiresAt,
            String agentId
    ) {}

    public CronTaskService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.durableStorePath = Path.of(
                System.getProperty("user.dir"), ".ai-code-assistant", "scheduled_tasks.json");
        loadDurableTasks();
    }

    /**
     * 添加定时任务。
     *
     * @return 新创建的任务
     * @throws IllegalStateException 如果任务数已达上限
     */
    public CronTask addTask(String cron, String prompt, boolean recurring, boolean durable, String agentId) {
        if (tasks.size() >= MAX_JOBS) {
            throw new IllegalStateException("Maximum number of scheduled tasks reached (" + MAX_JOBS + ")");
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(DEFAULT_EXPIRY_DAYS, ChronoUnit.DAYS);

        CronTask task = new CronTask(id, cron, prompt, recurring, durable, now, expiresAt, agentId);
        tasks.put(id, task);

        if (durable) {
            persistDurableTasks();
        }
        log.info("Cron task created: id={}, cron='{}', recurring={}, durable={}", id, cron, recurring, durable);
        return task;
    }

    /**
     * 列出所有任务。
     */
    public List<CronTask> listAll() {
        return new ArrayList<>(tasks.values());
    }

    /**
     * 获取单个任务。
     */
    public Optional<CronTask> getTask(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    /**
     * 删除任务。
     *
     * @return 被删除的任务，或 empty 如果不存在
     */
    public Optional<CronTask> remove(String id) {
        CronTask removed = tasks.remove(id);
        if (removed != null && removed.durable()) {
            persistDurableTasks();
        }
        if (removed != null) {
            log.info("Cron task removed: id={}", id);
        }
        return Optional.ofNullable(removed);
    }

    /**
     * 当前任务数量。
     */
    public int taskCount() {
        return tasks.size();
    }

    /**
     * 定期清理过期任务 — 每 60 秒执行一次。
     */
    @Scheduled(fixedRate = 60000)
    public void cleanupExpiredTasks() {
        Instant now = Instant.now();
        List<String> expired = tasks.entrySet().stream()
                .filter(e -> now.isAfter(e.getValue().expiresAt()))
                .map(Map.Entry::getKey)
                .toList();

        boolean hasDurable = false;
        for (String id : expired) {
            CronTask task = tasks.remove(id);
            if (task != null && task.durable()) {
                hasDurable = true;
            }
            log.info("Expired cron task cleaned: id={}", id);
        }
        if (hasDurable) {
            persistDurableTasks();
        }
    }

    // ═══ 持久化 ═══

    private void persistDurableTasks() {
        try {
            List<CronTask> durables = tasks.values().stream()
                    .filter(CronTask::durable)
                    .toList();
            Files.createDirectories(durableStorePath.getParent());
            Path tmpPath = durableStorePath.resolveSibling(durableStorePath.getFileName() + ".tmp");
            objectMapper.writeValue(tmpPath.toFile(), durables);
            Files.move(tmpPath, durableStorePath, StandardCopyOption.ATOMIC_MOVE);
            log.debug("Durable tasks persisted: {} tasks", durables.size());
        } catch (IOException e) {
            log.error("Failed to persist durable tasks: {}", e.getMessage(), e);
        }
    }

    private void loadDurableTasks() {
        if (!Files.exists(durableStorePath)) {
            return;
        }
        try {
            List<CronTask> loaded = objectMapper.readValue(
                    durableStorePath.toFile(),
                    new TypeReference<List<CronTask>>() {});
            Instant now = Instant.now();
            for (CronTask task : loaded) {
                if (now.isBefore(task.expiresAt())) {
                    tasks.put(task.id(), task);
                }
            }
            log.info("Loaded {} durable cron tasks from {}", tasks.size(), durableStorePath);
        } catch (IOException e) {
            log.warn("Failed to load durable tasks: {}", e.getMessage());
        }
    }
}
