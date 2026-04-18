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
    if (pct < 50) return 'bg-green-500';
    if (pct < 75) return 'bg-yellow-500';
    return 'bg-red-500';
};

const getTextColor = (pct: number): string => {
    if (pct < 50) return 'text-green-600';
    if (pct < 75) return 'text-yellow-600';
    return 'text-red-600';
};

export const TokenBudgetIndicator: React.FC = () => {
    const tokenBudgetState = useMessageStore(s => s.tokenBudgetState);

    if (!tokenBudgetState?.visible) return null;

    const { pct, currentTokens, budgetTokens } = tokenBudgetState;
    const clampedPct = Math.min(pct, 100);

    return (
        <div className="w-full bg-gray-100 dark:bg-gray-800 rounded-lg px-3 py-2 transition-all duration-300 animate-in fade-in">
            <div className="flex items-center justify-between mb-1">
                <span className="text-xs font-medium text-gray-700 dark:text-gray-300">
                    Token 预算
                </span>
                <span className={`text-xs font-mono ${getTextColor(pct)}`}>
                    {currentTokens.toLocaleString()} / {budgetTokens.toLocaleString()} ({pct}%)
                </span>
            </div>
            <div className="h-1.5 bg-gray-200 dark:bg-gray-700 rounded-full overflow-hidden">
                <div
                    className={`h-full ${getBarColor(pct)} rounded-full transition-all duration-500 ease-out`}
                    style={{ width: `${clampedPct}%` }}
                />
            </div>
        </div>
    );
};
