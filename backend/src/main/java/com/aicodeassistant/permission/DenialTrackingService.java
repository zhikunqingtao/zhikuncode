package com.aicodeassistant.permission;

import com.aicodeassistant.model.*;
import com.aicodeassistant.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 权限否定追踪服务 — 电路断路器模式。
 * <p>
 * 当自动模式分类器连续拒绝过多操作时，自动降级到人工提示模式，
 * 防止合法操作被静默阻塞。
 * <p>
 * 追踪两个维度:
 * - consecutiveDenials: 连续拒绝次数（每次允许时重置为0）
 * - totalDenials: 会话内总拒绝次数
 *
 * @see <a href="SPEC §4.9.3">权限否定追踪</a>
 */
@Service
public class DenialTrackingService {

    private static final Logger log = LoggerFactory.getLogger(DenialTrackingService.class);

    // ============ 否定追踪限制常量 ============
    /** 连续拒绝上限 */
    public static final int MAX_CONSECUTIVE = 3;
    /** 会话总拒绝上限 */
    public static final int MAX_TOTAL = 20;

    // 应用级状态（普通会话）
    private volatile DenialTrackingState globalState = DenialTrackingState.create();

    /**
     * 获取当前全局状态。
     */
    public DenialTrackingState getState() {
        return globalState;
    }

    /**
     * 记录一次分类器拒绝。
     * 同时递增 consecutiveDenials 和 totalDenials。
     */
    public DenialTrackingState recordDenial(DenialTrackingState state) {
        DenialTrackingState newState = new DenialTrackingState(
                state.consecutiveDenials() + 1,
                state.totalDenials() + 1
        );
        log.debug("Denial recorded: consecutive={}, total={}",
                newState.consecutiveDenials(), newState.totalDenials());
        return newState;
    }

    /**
     * 记录一次允许（分类器通过或快速路径命中）。
     * 重置 consecutiveDenials 为 0，保留 totalDenials。
     * 如果 consecutiveDenials 已为 0，返回原对象（避免无意义的状态更新）。
     */
    public DenialTrackingState recordSuccess(DenialTrackingState state) {
        if (state.consecutiveDenials() == 0) return state;
        DenialTrackingState newState = new DenialTrackingState(0, state.totalDenials());
        log.debug("Success recorded: consecutive reset, total={}", newState.totalDenials());
        return newState;
    }

    /**
     * 电路断路器：检查是否应降级到人工提示模式。
     *
     * @return true 如果连续拒绝 >= 3 或 总拒绝 >= 20
     */
    public boolean shouldFallbackToPrompting(DenialTrackingState state) {
        return state.consecutiveDenials() >= MAX_CONSECUTIVE
                || state.totalDenials() >= MAX_TOTAL;
    }

    /**
     * 拒绝限制被触发时的处理逻辑。
     * <p>
     * 流程:
     * 1. 判断触发的是哪个限制（连续 vs 总数）
     * 2. Headless 模式 → 抛出 AbortError 中止执行
     * 3. CLI/Web 模式 → 降级到人工提示，让用户审核
     * 4. 如果是总数限制，重置所有计数器；连续限制保留总数
     * 5. 记录分析事件
     *
     * @return 降级后的权限决策（ask），或 null（未触发限制）
     */
    public PermissionDecision handleDenialLimitExceeded(
            DenialTrackingState state,
            PermissionContext context,
            String classifierReason,
            Tool tool) {

        if (!shouldFallbackToPrompting(state)) {
            return null; // 未触发限制，正常流程
        }

        boolean hitTotalLimit = state.totalDenials() >= MAX_TOTAL;
        int totalCount = state.totalDenials();
        int consecutiveCount = state.consecutiveDenials();

        String warning = hitTotalLimit
                ? totalCount + " actions were blocked this session. Please review."
                : consecutiveCount + " consecutive actions were blocked. Please review.";

        log.warn("Denial limit exceeded: hitTotal={}, consecutive={}, total={}, tool={}",
                hitTotalLimit, consecutiveCount, totalCount,
                tool != null ? tool.getName() : "unknown");

        // Headless 模式直接中止
        if (context.isHeadless()) {
            throw new DenialLimitAbortException(
                    "Agent aborted: too many classifier denials in headless mode"
                            + " (consecutive=" + consecutiveCount + ", total=" + totalCount + ")");
        }

        // 总数限制命中时重置所有计数器
        if (hitTotalLimit) {
            persistState(context, DenialTrackingState.create());
        }

        // 返回降级决策：转为 ask 模式，让用户审核
        return PermissionDecision.ask(
                warning + "\n\nLatest blocked action: " + classifierReason);
    }

    /**
     * 持久化否定状态。
     * <p>
     * 支持两种场景:
     * - 异步子代理: 通过 localDenialTracking 原地更新
     * - 普通会话: 通过全局状态持久化
     */
    public void persistState(PermissionContext context, DenialTrackingState state) {
        // P1 简化实现: 直接更新全局状态
        this.globalState = state;
        log.debug("State persisted: consecutive={}, total={}",
                state.consecutiveDenials(), state.totalDenials());
    }

    /**
     * 重置全局状态。
     */
    public void reset() {
        this.globalState = DenialTrackingState.create();
        log.info("Denial tracking state reset");
    }

    /**
     * 否定限制中止异常 — Headless 模式下的电路断路器触发。
     */
    public static class DenialLimitAbortException extends RuntimeException {
        public DenialLimitAbortException(String message) {
            super(message);
        }
    }
}
