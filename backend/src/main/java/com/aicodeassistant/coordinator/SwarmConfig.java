package com.aicodeassistant.coordinator;

import java.nio.file.Path;
import java.util.List;

/**
 * Swarm 配置 — 定义 Swarm 实例的运行参数。
 * <p>
 * 包含 Worker 数量上限、空闲超时、工具过滤、Scratchpad 路径等配置。
 *
 * @param teamName              团队名称（唯一标识）
 * @param maxWorkers            最大 Worker 数量（默认 5）
 * @param backend               执行后端类型
 * @param workerModel           Worker 使用的模型（null 时继承 Leader 模型）
 * @param workerToolAllowList   Worker 工具白名单（空列表表示不限制）
 * @param workerToolDenyList    Worker 工具黑名单
 * @param scratchpadDir         Scratchpad 共享目录路径
 * @param workerIdleTimeoutMs   Worker 空闲超时（毫秒），超时后回收
 * @param taskQueueSize         任务队列容量上限
 * @param permissionBubbleEnabled 是否启用权限冒泡机制
 * @see <a href="SPEC §11">Team/Swarm 多Agent协作</a>
 */
public record SwarmConfig(
        String teamName,
        int maxWorkers,
        SwarmBackendType backend,
        String workerModel,
        List<String> workerToolAllowList,
        List<String> workerToolDenyList,
        Path scratchpadDir,
        long workerIdleTimeoutMs,
        int taskQueueSize,
        boolean permissionBubbleEnabled
) {

    /** 默认最大 Worker 数量 */
    public static final int DEFAULT_MAX_WORKERS = 5;

    /** 默认 Worker 空闲超时: 5 分钟 */
    public static final long DEFAULT_WORKER_IDLE_TIMEOUT_MS = 300_000L;

    /** 默认任务队列容量 */
    public static final int DEFAULT_TASK_QUEUE_SIZE = 50;

    /** 执行后端类型 */
    public enum SwarmBackendType {
        /** 进程内 Virtual Thread 并发 */
        IN_PROCESS,
        /** 外部进程（预留扩展） */
        EXTERNAL_PROCESS
    }

    /**
     * 快速创建工厂方法 — 使用默认配置。
     *
     * @param teamName      团队名称
     * @param scratchpadDir Scratchpad 目录
     * @return 默认配置的 SwarmConfig
     */
    public static SwarmConfig withDefaults(String teamName, Path scratchpadDir) {
        return new SwarmConfig(
                teamName,
                DEFAULT_MAX_WORKERS,
                SwarmBackendType.IN_PROCESS,
                null,
                List.of(),
                List.of(),
                scratchpadDir,
                DEFAULT_WORKER_IDLE_TIMEOUT_MS,
                DEFAULT_TASK_QUEUE_SIZE,
                true
        );
    }

    /**
     * 自定义 Worker 数量的工厂方法。
     */
    public static SwarmConfig withWorkers(String teamName, int maxWorkers, Path scratchpadDir) {
        return new SwarmConfig(
                teamName,
                maxWorkers,
                SwarmBackendType.IN_PROCESS,
                null,
                List.of(),
                List.of(),
                scratchpadDir,
                DEFAULT_WORKER_IDLE_TIMEOUT_MS,
                DEFAULT_TASK_QUEUE_SIZE,
                true
        );
    }
}
