import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { isTokenWarningActive, useTokenWarningClass } from '../useTokenWarningClass';
import { useMessageStore } from '@/store/messageStore';

const WARNING_BUDGET = { pct: 82, currentTokens: 164000, budgetTokens: 200000, visible: true };

describe('isTokenWarningActive', () => {
    it('预算可见且 pct ≥ 50 为 warning 激活态', () => {
        expect(isTokenWarningActive(WARNING_BUDGET, null)).toBe(true);
        expect(isTokenWarningActive({ ...WARNING_BUDGET, pct: 50 }, null)).toBe(true);
    });

    it('低占用 / 不可见 / null 均非 warning 态', () => {
        expect(isTokenWarningActive({ ...WARNING_BUDGET, pct: 30 }, null)).toBe(false);
        expect(isTokenWarningActive({ ...WARNING_BUDGET, visible: false }, null)).toBe(false);
        expect(isTokenWarningActive(null, null)).toBe(false);
    });

    it('token_warning 事件告警（warningLevel ≠ normal）即为激活态', () => {
        const warning = { type: 'token_warning' as const, currentTokens: 180000, maxTokens: 200000, usagePercent: 90, warningLevel: 'critical' };
        expect(isTokenWarningActive(null, warning)).toBe(true);
        expect(isTokenWarningActive(null, { ...warning, warningLevel: 'normal' })).toBe(false);
    });
});

describe('useTokenWarningClass', () => {
    beforeEach(() => {
        document.documentElement.classList.remove('token-warning');
        act(() => {
            useMessageStore.setState({ tokenBudgetState: null, tokenWarning: null });
        });
    });

    afterEach(() => {
        document.documentElement.classList.remove('token-warning');
        act(() => {
            useMessageStore.setState({ tokenBudgetState: null, tokenWarning: null });
        });
    });

    it('tokenBudgetState 进入 warning 态 → html 加 token-warning class；解除 → 移除', () => {
        renderHook(() => useTokenWarningClass());
        const root = document.documentElement;
        expect(root.classList.contains('token-warning')).toBe(false);

        act(() => {
            useMessageStore.getState().setTokenBudgetState(WARNING_BUDGET);
        });
        expect(root.classList.contains('token-warning')).toBe(true);

        act(() => {
            useMessageStore.getState().clearTokenBudgetState();
        });
        expect(root.classList.contains('token-warning')).toBe(false);
    });

    it('预算可见但低占用（pct < 50）不加 class', () => {
        renderHook(() => useTokenWarningClass());
        act(() => {
            useMessageStore.getState().setTokenBudgetState({ ...WARNING_BUDGET, pct: 30 });
        });
        expect(document.documentElement.classList.contains('token-warning')).toBe(false);
    });

    it('token_warning 事件告警同样驱动 class，清除后移除', () => {
        renderHook(() => useTokenWarningClass());
        const root = document.documentElement;

        act(() => {
            useMessageStore.getState().setTokenWarning({
                type: 'token_warning', currentTokens: 180000, maxTokens: 200000,
                usagePercent: 90, warningLevel: 'warning',
            });
        });
        expect(root.classList.contains('token-warning')).toBe(true);

        act(() => {
            useMessageStore.getState().clearTokenWarning();
        });
        expect(root.classList.contains('token-warning')).toBe(false);
    });

    it('卸载时兜底清理 class', () => {
        const { unmount } = renderHook(() => useTokenWarningClass());
        act(() => {
            useMessageStore.getState().setTokenBudgetState(WARNING_BUDGET);
        });
        expect(document.documentElement.classList.contains('token-warning')).toBe(true);

        unmount();
        expect(document.documentElement.classList.contains('token-warning')).toBe(false);
    });
});
