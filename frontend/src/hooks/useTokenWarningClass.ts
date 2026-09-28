/**
 * useTokenWarningClass — TOKEN 警告态 html class 监听（星舰 HUD 事件特效 v4）
 *
 * 数据链路：dispatch.ts `token_warning` → messageStore.tokenWarning；
 *          `token_budget_nudge` → messageStore.tokenBudgetState（TokenBudgetIndicator 消费）。
 * 任一来源处于 warning 激活态时给 document.documentElement 加 `token-warning` class，
 * 解除时移除。监听只维护 class；视觉由 CSS 组合选择器
 * `html.spaceship.fx-event.token-warning` 门控（非星舰主题/关闭事件特效时无副作用）。
 */

import { useEffect } from 'react';
import { useMessageStore } from '@/store/messageStore';
import type { TokenBudgetState } from '@/store/messageStore';
import type { TokenWarningPayload } from '@/types';

/** 与 TokenBudgetIndicator 的黄色（warn）阈值对齐：pct ≥ 50 进入警告 */
const TOKEN_WARN_PCT_THRESHOLD = 50;

/**
 * warning 激活态判定：
 * - token_warning 事件告警中（warningLevel ≠ normal）；或
 * - Token 预算指示可见且占用 ≥ 50%（TokenBudgetIndicator 的黄/红档）
 */
export function isTokenWarningActive(
    tokenBudgetState: TokenBudgetState | null,
    tokenWarning: TokenWarningPayload | null,
): boolean {
    if (tokenWarning && tokenWarning.warningLevel !== 'normal') return true;
    return Boolean(tokenBudgetState?.visible)
        && (tokenBudgetState?.pct ?? 0) >= TOKEN_WARN_PCT_THRESHOLD;
}

export function useTokenWarningClass(): void {
    const tokenBudgetState = useMessageStore(s => s.tokenBudgetState);
    const tokenWarning = useMessageStore(s => s.tokenWarning);

    useEffect(() => {
        const root = document.documentElement;
        root.classList.toggle('token-warning', isTokenWarningActive(tokenBudgetState, tokenWarning));
        // 卸载兜底清理，避免 class 残留
        return () => { root.classList.remove('token-warning'); };
    }, [tokenBudgetState, tokenWarning]);
}

export default useTokenWarningClass;
