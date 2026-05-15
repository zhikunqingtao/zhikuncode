/**
 * PermissionBubbleDialog — Worker 权限冒泡对话框（堆叠式并发展示）
 * 同时显示所有待处理的权限请求，每个独立倒计时
 * 支持单个批准/拒绝 + 全部批准/全部拒绝批量操作
 * 60s 超时自动拒绝对应请求
 */

import React, { useEffect, useRef, useState } from 'react';
import { useSwarmStore } from '@/store/swarmStore';

interface PermissionItemState {
    countdown: number;
    intervalId: ReturnType<typeof setInterval> | null;
}

const riskColors: Record<string, { border: string; badge: string; text: string }> = {
    low: { border: 'border-green-300 dark:border-green-700', badge: 'bg-green-100 dark:bg-green-900/40 text-green-700 dark:text-green-300', text: '低风险' },
    medium: { border: 'border-yellow-300 dark:border-yellow-700', badge: 'bg-yellow-100 dark:bg-yellow-900/40 text-yellow-700 dark:text-yellow-300', text: '中风险' },
    high: { border: 'border-red-300 dark:border-red-700', badge: 'bg-red-100 dark:bg-red-900/40 text-red-700 dark:text-red-300', text: '高风险' },
};

export const PermissionBubbleDialog: React.FC = () => {
    const { pendingPermissions, resolvePermission, resolveAll } = useSwarmStore();
    const [itemStates, setItemStates] = useState<Record<string, PermissionItemState>>({});
    const itemStatesRef = useRef<Record<string, PermissionItemState>>({});

    // 保持 ref 与 state 同步，供 interval 回调内使用
    useEffect(() => {
        itemStatesRef.current = itemStates;
    }, [itemStates]);

    // 为每个新请求创建独立倒计时；清理已移除的请求
    useEffect(() => {
        const currentIds = new Set(pendingPermissions.map((r) => r.requestId));
        const prevStates = itemStatesRef.current;

        // 为新增的请求初始化倒计时
        const newStates: Record<string, PermissionItemState> = {};
        let changed = false;

        for (const req of pendingPermissions) {
            if (prevStates[req.requestId]) {
                // 保留现有状态
                newStates[req.requestId] = prevStates[req.requestId];
            } else {
                // 新请求：启动独立倒计时
                const intervalId = setInterval(() => {
                    setItemStates((prev) => {
                        const state = prev[req.requestId];
                        if (!state) return prev;

                        if (state.countdown <= 1) {
                            // 超时自动拒绝
                            resolvePermission(req.requestId, 'DENY');
                            clearInterval(state.intervalId!);
                            const updated = { ...prev };
                            delete updated[req.requestId];
                            return updated;
                        }
                        return {
                            ...prev,
                            [req.requestId]: {
                                ...state,
                                countdown: state.countdown - 1,
                            },
                        };
                    });
                }, 1000);
                newStates[req.requestId] = { countdown: 60, intervalId };
                changed = true;
            }
        }

        // 清理已处理/移除的请求
        for (const id of Object.keys(prevStates)) {
            if (!currentIds.has(id)) {
                if (prevStates[id].intervalId) {
                    clearInterval(prevStates[id].intervalId!);
                }
                changed = true;
            }
        }

        if (changed || Object.keys(newStates).length !== Object.keys(prevStates).length) {
            setItemStates(newStates);
        }

        // 组件卸载时清理所有定时器
        return () => {
            for (const state of Object.values(newStates)) {
                if (state.intervalId) clearInterval(state.intervalId);
            }
        };
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [pendingPermissions]);

    const handleDecision = (requestId: string, approved: boolean) => {
        // 清理对应的倒计时
        const state = itemStates[requestId];
        if (state?.intervalId) {
            clearInterval(state.intervalId);
        }
        setItemStates((prev) => {
            const updated = { ...prev };
            delete updated[requestId];
            return updated;
        });
        resolvePermission(requestId, approved ? 'ALLOW' : 'DENY');
    };

    const handleBatchDecision = (approved: boolean) => {
        // 清理所有倒计时
        for (const state of Object.values(itemStates)) {
            if (state.intervalId) clearInterval(state.intervalId);
        }
        setItemStates({});
        resolveAll(approved ? 'ALLOW' : 'DENY');
    };

    if (pendingPermissions.length === 0) return null;

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
            <div className="w-full max-w-md mx-4 max-h-[70vh] flex flex-col bg-white dark:bg-zinc-900 rounded-xl shadow-2xl border border-zinc-200 dark:border-zinc-700 overflow-hidden">
                {/* Header */}
                <div className="px-5 py-3 bg-zinc-50 dark:bg-zinc-800 border-b border-zinc-200 dark:border-zinc-700 flex-shrink-0">
                    <div className="flex items-center justify-between">
                        <div className="flex items-center gap-2">
                            <svg className="w-5 h-5 text-amber-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z" />
                            </svg>
                            <h3 className="text-sm font-semibold text-zinc-800 dark:text-zinc-200">
                                权限请求 ({pendingPermissions.length})
                            </h3>
                        </div>
                        {pendingPermissions.length > 1 && (
                            <div className="flex gap-2">
                                <button
                                    onClick={() => handleBatchDecision(true)}
                                    className="text-xs px-2.5 py-1 rounded-md bg-green-600 text-white hover:bg-green-700 transition-colors"
                                >
                                    全部批准
                                </button>
                                <button
                                    onClick={() => handleBatchDecision(false)}
                                    className="text-xs px-2.5 py-1 rounded-md bg-red-600 text-white hover:bg-red-700 transition-colors"
                                >
                                    全部拒绝
                                </button>
                            </div>
                        )}
                    </div>
                </div>

                {/* Permission request list — stacked */}
                <div className="overflow-y-auto flex-1 divide-y divide-zinc-100 dark:divide-zinc-800">
                    {pendingPermissions.map((req) => {
                        const state = itemStates[req.requestId];
                        const countdown = state?.countdown ?? 60;
                        const risk = riskColors[req.riskLevel] ?? riskColors.medium;

                        return (
                            <div key={req.requestId} className={`px-5 py-4 border-l-4 ${risk.border}`}>
                                {/* Worker + Risk badge */}
                                <div className="flex items-center justify-between mb-2">
                                    <span className="text-xs font-mono bg-zinc-100 dark:bg-zinc-800 px-2 py-0.5 rounded text-zinc-600 dark:text-zinc-400">
                                        {req.workerId}
                                    </span>
                                    <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${risk.badge}`}>
                                        {risk.text}
                                    </span>
                                </div>

                                {/* Tool Name */}
                                <div className="flex items-center gap-2 mb-1.5">
                                    <span className="text-[10px] uppercase tracking-wider text-zinc-400">工具</span>
                                    <span className="text-sm font-mono font-medium text-zinc-800 dark:text-zinc-200">
                                        {req.toolName}
                                    </span>
                                </div>

                                {/* Reason */}
                                <p className="text-xs text-zinc-500 dark:text-zinc-400 mb-3 leading-relaxed">
                                    {req.reason}
                                </p>

                                {/* Countdown bar + actions */}
                                <div className="flex items-center gap-3">
                                    <div className="flex-1 flex items-center gap-2">
                                        <div className="h-1 flex-1 bg-zinc-200 dark:bg-zinc-700 rounded-full overflow-hidden">
                                            <div
                                                className="h-full bg-amber-500 dark:bg-amber-400 rounded-full transition-all duration-1000"
                                                style={{ width: `${(countdown / 60) * 100}%` }}
                                            />
                                        </div>
                                        <span className="text-xs text-zinc-400 tabular-nums w-7 text-right">{countdown}s</span>
                                    </div>
                                    <div className="flex gap-2">
                                        <button
                                            onClick={() => handleDecision(req.requestId, false)}
                                            className="text-xs px-3 py-1.5 rounded-md border border-zinc-300 dark:border-zinc-600 text-zinc-700 dark:text-zinc-300 hover:bg-zinc-100 dark:hover:bg-zinc-700 transition-colors"
                                        >
                                            拒绝
                                        </button>
                                        <button
                                            onClick={() => handleDecision(req.requestId, true)}
                                            className="text-xs px-3 py-1.5 rounded-md bg-green-600 text-white hover:bg-green-700 transition-colors"
                                        >
                                            批准
                                        </button>
                                    </div>
                                </div>
                            </div>
                        );
                    })}
                </div>
            </div>
        </div>
    );
};

export default PermissionBubbleDialog;
