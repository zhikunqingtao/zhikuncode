package com.aicodeassistant.model;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 品牌化任务 ID — 标识一个任务。
 * <p>
 * ID 生成规则：前缀 + 8位 base36 随机字符。
 * 前缀映射: SHELL->'b', AGENT->'a', REMOTE_AGENT->'r',
 *   IN_PROCESS_TEAMMATE->'t', LOCAL_WORKFLOW->'w', MONITOR_MCP->'m', DREAM->'d'
 * 示例: "a1k3x7p9" (本地代理任务)
 * 组合空间: 36^8 ≈ 2.8万亿
 *
 * @see <a href="SPEC §5.5">任务模型</a>
 */
public record TaskId(String value) {

    private static final String BASE36_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz";

    public TaskId {
        Objects.requireNonNull(value, "TaskId cannot be null");
    }

    public static TaskId generate(TaskType type) {
        char prefix = switch (type) {
            case SHELL -> 'b';
            case AGENT -> 'a';
            case REMOTE_AGENT -> 'r';
            case IN_PROCESS_TEAMMATE -> 't';
            case LOCAL_WORKFLOW -> 'w';
            case MONITOR_MCP -> 'm';
            case DREAM -> 'd';
        };
        return new TaskId(prefix + randomBase36(8));
    }

    public static TaskId of(String raw) {
        return new TaskId(raw);
    }

    @Override
    public String toString() {
        return value;
    }

    private static String randomBase36(int length) {
        var random = ThreadLocalRandom.current();
        var sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE36_CHARS.charAt(random.nextInt(36)));
        }
        return sb.toString();
    }
}
