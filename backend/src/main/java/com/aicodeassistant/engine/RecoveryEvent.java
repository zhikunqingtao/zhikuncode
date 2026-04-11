package com.aicodeassistant.engine;

/**
 * 恢复事件 — 通知 SDK 消费者系统正在自动恢复。
 * <p>
 * 当 QueryEngine 遇到 413/max_output_tokens 等可恢复错误时，
 * 会先扣留错误尝试 ContextCascade 恢复，恢复过程中通过此事件通知消费者。
 *
 * @param type          恢复类型 ("413_recovery" | "max_output_recovery")
 * @param attemptNumber 恢复尝试次数
 * @param message       人类可读描述
 */
public record RecoveryEvent(
        String type,
        int attemptNumber,
        String message
) {
    /** 413 恢复事件 */
    public static RecoveryEvent of413(int attempt, String detail) {
        return new RecoveryEvent("413_recovery", attempt,
                "Attempting recovery from 413 (attempt " + attempt + "): " + detail);
    }

    /** max_output_tokens 恢复事件 */
    public static RecoveryEvent ofMaxOutput(int attempt) {
        return new RecoveryEvent("max_output_recovery", attempt,
                "Attempting recovery from max_output_tokens (attempt " + attempt + ")");
    }
}
