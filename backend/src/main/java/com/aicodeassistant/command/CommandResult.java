package com.aicodeassistant.command;

import java.util.Map;

/**
 * 命令执行结果 — 封装命令执行后的返回值。
 * <p>
 * 三种结果类型:
 * <ul>
 *     <li>TEXT — 纯文本结果</li>
 *     <li>COMPACT — 压缩结果（来自 /compact 命令）</li>
 *     <li>SKIP — 跳过消息显示</li>
 *     <li>JSX — 需要前端渲染的结构化数据</li>
 *     <li>ERROR — 执行错误</li>
 * </ul>
 *
 * @see <a href="SPEC §3.3">命令系统</a>
 */
public record CommandResult(
        ResultType type,
        String value,
        Map<String, Object> data,
        String error
) {

    public enum ResultType {
        TEXT,
        COMPACT,
        SKIP,
        JSX,
        ERROR
    }

    // ───── 工厂方法 ─────

    public static CommandResult text(String value) {
        return new CommandResult(ResultType.TEXT, value, Map.of(), null);
    }

    public static CommandResult compact(String displayText, Map<String, Object> compactionData) {
        return new CommandResult(ResultType.COMPACT, displayText, compactionData, null);
    }

    public static CommandResult skip() {
        return new CommandResult(ResultType.SKIP, null, Map.of(), null);
    }

    public static CommandResult jsx(Map<String, Object> data) {
        return new CommandResult(ResultType.JSX, null, data, null);
    }

    public static CommandResult error(String error) {
        return new CommandResult(ResultType.ERROR, null, Map.of(), error);
    }

    public boolean isSuccess() {
        return type != ResultType.ERROR;
    }
}
