/**
 * PermissionBubbleDialog — Worker 权限冒泡对话框
 * 显示 Worker 请求的权限详情，提供批准/拒绝按钮
 * 60s 超时自动拒绝
 */

import React, { useCallback, useEffect, useState } from 'react';
import { useSwarmStore } from '@/store/swarmStore';

const riskColors: Record<string, { border: string; badge: string; text: string }> = {
    low: { border: 'border-green-300 dark:border-green-700', badge: 'bg-green-100 dark:bg-green-900/40 text-green-700 dark:text-green-300', text: '低风险' },
    medium: { border: 'border-yellow-300 dark:border-yellow-700', badge: 'bg-yellow-100 dark:bg-yellow-900/40 text-yellow-700 dark:text-yellow-300', text: '中风险' },
    high: { border: 'border-red-300 dark:border-red-700', badge: 'bg-red-100 dark:bg-red-900/40 text-red-700 dark:text-red-300', text: '高风险' },
};

export const PermissionBubbleDialog: React.FC = () => {
    const { pendingPermissions, removePermissionBubble } = useSwarmStore();
    const [countdown, setCountdown] = useState(60);

    const currentRequest = pendingPermissions[0];

    // Countdown timer
    useEffect(() => {
        if (!currentRequest) {
            setCountdown(60);
            return;
        }

        setCountdown(60);
        const interval = setInterval(() => {
            setCountdown((prev) => {
                if (prev <= 1) {
                    // Auto-deny on timeout
                    handleDecision(false);
                    return 60;
                }
                return prev - 1;
            });
        }, 1000);

        return () => clearInterval(interval);
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [currentRequest?.requestId]);

    const handleDecision = useCallback(async (approved: boolean) => {
        if (!currentRequest) return;

        try {
            await fetch(`/api/swarm/permission/${currentRequest.requestId}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ approved }),
            });
        } catch (e) {
            console.error('Failed to send permission decision:', e);
        }

        removePermissionBubble(currentRequest.requestId);
    }, [currentRequest, removePermissionBubble]);

    if (!currentRequest) return null;

    const risk = riskColors[currentRequest.riskLevel] ?? riskColors.medium;

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
            <div className={`w-full max-w-md mx-4 bg-white dark:bg-zinc-900 rounded-xl shadow-2xl border-2 ${risk.border} overflow-hidden`}>
                {/* Header */}
                <div className="px-5 py-3 bg-zinc-50 dark:bg-zinc-800 border-b border-zinc-200 dark:border-zinc-700">
                    <div className="flex items-center justify-between">
                        <div className="flex items-center gap-2">
                            <svg className="w-5 h-5 text-amber-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z" />
                            </svg>
                            <h3 className="text-sm font-semibold text-zinc-800 dark:text-zinc-200">
                                Worker 权限请求
                            </h3>
                        </div>
                        <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${risk.badge}`}>
                            {risk.text}
                        </span>
                    </div>
                </div>

                {/* Body */}
                <div className="px-5 py-4 space-y-3">
                    {/* Worker Badge */}
                    <div className="flex items-center gap-2">
                        <span className="text-[10px] uppercase tracking-wider text-zinc-400">Worker</span>
                        <span className="text-xs font-mono bg-zinc-100 dark:bg-zinc-800 px-2 py-0.5 rounded text-zinc-700 dark:text-zinc-300">
                            {currentRequest.workerId}
                        </span>
                    </div>

                    {/* Tool Name */}
                    <div className="flex items-center gap-2">
                        <span className="text-[10px] uppercase tracking-wider text-zinc-400">工具</span>
                        <span className="text-sm font-mono font-medium text-zinc-800 dark:text-zinc-200">
                            {currentRequest.toolName}
                        </span>
                    </div>

                    {/* Reason */}
                    <div className="bg-zinc-50 dark:bg-zinc-800/50 rounded-lg p-3">
                        <div className="text-[10px] uppercase tracking-wider text-zinc-400 mb-1">原因</div>
                        <div className="text-sm text-zinc-700 dark:text-zinc-300 leading-relaxed">
                            {currentRequest.reason}
                        </div>
                    </div>

                    {/* Countdown */}
                    <div className="flex items-center justify-center gap-2">
                        <div className="h-1 flex-1 bg-zinc-200 dark:bg-zinc-700 rounded-full overflow-hidden">
                            <div
                                className="h-full bg-amber-500 dark:bg-amber-400 rounded-full transition-all duration-1000"
                                style={{ width: `${(countdown / 60) * 100}%` }}
                            />
                        </div>
                        <span className="text-xs text-zinc-400 tabular-nums w-8 text-right">{countdown}s</span>
                    </div>
                </div>

                {/* Actions */}
                <div className="px-5 py-3 bg-zinc-50 dark:bg-zinc-800 border-t border-zinc-200 dark:border-zinc-700 flex justify-end gap-2">
                    <button
                        onClick={() => handleDecision(false)}
                        className="px-4 py-2 text-sm rounded-lg border border-zinc-300 dark:border-zinc-600 text-zinc-700 dark:text-zinc-300 hover:bg-zinc-100 dark:hover:bg-zinc-700 transition-colors min-w-[88px] min-h-[44px]"
                    >
                        拒绝
                    </button>
                    <button
                        onClick={() => handleDecision(true)}
                        className="px-4 py-2 text-sm rounded-lg bg-green-600 hover:bg-green-700 text-white transition-colors min-w-[88px] min-h-[44px]"
                    >
                        批准
                    </button>
                </div>

                {/* Queue indicator */}
                {pendingPermissions.length > 1 && (
                    <div className="px-5 py-1.5 bg-zinc-100 dark:bg-zinc-800/80 text-center">
                        <span className="text-[10px] text-zinc-400">
                            还有 {pendingPermissions.length - 1} 个权限请求等待处理
                        </span>
                    </div>
                )}
            </div>
        </div>
    );
};

export default PermissionBubbleDialog;
