/**
 * TokenBudgetIndicator — Token 预算进度指示器
 * SPEC: §7.1 Token 预算续写 WebSocket 推送
 *
 * 显示当前 token 使用进度条和百分比。
 * 当 TokenBudgetTracker 触发 nudge 时自动显示，回合结束后自动隐藏。
 */

import React from 'react';
import { useMessageStore } from '@/store/messageStore';

/** 颜色阈值：pct < 50% 绿色，50-75% 黄色，>75% 红色 */
const getBarColor = (pct: number): string => {
    if (pct < 50) return 'bg-ok';
    if (pct < 75) return 'bg-warn';
    return 'bg-err';
};

const getTextColor = (pct: number): string => {
    if (pct < 50) return 'text-ok';
    if (pct < 75) return 'text-warn';
    return 'text-err';
};

export const TokenBudgetIndicator: React.FC = () => {
    const tokenBudgetState = useMessageStore(s => s.tokenBudgetState);

    if (!tokenBudgetState?.visible) return null;

    const { pct, currentTokens, budgetTokens } = tokenBudgetState;
    const clampedPct = Math.min(pct, 100);

    return (
        <div className="w-full bg-sunken2 rounded-[10px] px-3 py-2 transition-[width] duration-slow animate-in fade-in">
            <div className="flex items-center justify-between mb-1">
                <span className="text-[13px] font-medium text-t1">
                    Token 预算
                </span>
                <span className={`text-[13px] font-mono ${getTextColor(pct)}`}>
                    {currentTokens.toLocaleString()} / {budgetTokens.toLocaleString()} ({pct}%)
                </span>
            </div>
            <div className="h-1.5 bg-sunken2 rounded-full overflow-hidden">
                <div
                    className={`h-full ${getBarColor(pct)} rounded-full transition-[width] duration-sheet ease-out`}
                    style={{ width: `${clampedPct}%` }}
                />
            </div>
        </div>
    );
};
