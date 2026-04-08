package com.aicodeassistant.permission;

/**
 * 权限否定追踪状态 — 不可变记录。
 * <p>
 * 追踪两个维度:
 * - consecutiveDenials: 连续拒绝次数（每次允许时重置为0）
 * - totalDenials: 会话内总拒绝次数
 *
 * @see <a href="SPEC §4.9.3">权限否定追踪</a>
 */
public record DenialTrackingState(
        int consecutiveDenials,
        int totalDenials
) {
    /** 创建初始状态（全零）。 */
    public static DenialTrackingState create() {
        return new DenialTrackingState(0, 0);
    }
}
