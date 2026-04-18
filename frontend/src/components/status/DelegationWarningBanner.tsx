/**
 * DelegationWarningBanner — "不委派理解"原则警告横幅
 * 当 Coordinator 检测到未充分研究就直接实现时显示黄色警告
 * 可逐条关闭
 */

import React, { useCallback } from 'react';
import { useCoordinatorStore } from '@/store/coordinatorStore';

export const DelegationWarningBanner: React.FC = () => {
    const warnings = useCoordinatorStore((s) => s.delegationWarnings);
    const dismissWarning = useCoordinatorStore((s) => s.dismissWarning);

    // 仅显示未关闭的警告
    const visibleWarnings = warnings.filter((w) => !w.dismissed);

    const handleDismiss = useCallback(
        (id: string) => {
            dismissWarning(id);
        },
        [dismissWarning]
    );

    const handleDismissAll = useCallback(() => {
        visibleWarnings.forEach((w) => dismissWarning(w.id));
    }, [visibleWarnings, dismissWarning]);

    if (visibleWarnings.length === 0) return null;

    return (
        <div className="space-y-2">
            {visibleWarnings.map((warning) => (
                <div
                    key={warning.id}
                    className="flex items-start gap-3 px-4 py-2.5
                               bg-amber-50 dark:bg-amber-950/30
                               border border-amber-300 dark:border-amber-700
                               rounded-lg shadow-sm
                               animate-in slide-in-from-top-2 duration-300"
                    role="alert"
                >
                    {/* Warning icon */}
                    <div className="flex-shrink-0 mt-0.5">
                        <svg
                            className="w-4.5 h-4.5 text-amber-500 dark:text-amber-400"
                            fill="none" stroke="currentColor" viewBox="0 0 24 24"
                        >
                            <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={2}
                                d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z"
                            />
                        </svg>
                    </div>

                    {/* Warning content */}
                    <div className="flex-1 min-w-0">
                        <p className="text-xs font-medium text-amber-800 dark:text-amber-200">
                            委派质量警告
                        </p>
                        <p className="text-xs text-amber-700 dark:text-amber-300 mt-0.5 break-words">
                            {warning.message}
                        </p>
                        <span className="text-[10px] text-amber-500 dark:text-amber-500 mt-1 block">
                            {new Date(warning.timestamp).toLocaleTimeString()}
                        </span>
                    </div>

                    {/* Dismiss button */}
                    <button
                        onClick={() => handleDismiss(warning.id)}
                        className="flex-shrink-0 w-5 h-5 rounded flex items-center justify-center
                                   hover:bg-amber-200 dark:hover:bg-amber-800 transition-colors"
                        title="关闭警告"
                    >
                        <svg
                            className="w-3 h-3 text-amber-600 dark:text-amber-400"
                            fill="none" stroke="currentColor" viewBox="0 0 24 24"
                        >
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                        </svg>
                    </button>
                </div>
            ))}

            {/* Dismiss all button (when multiple warnings) */}
            {visibleWarnings.length > 1 && (
                <div className="flex justify-end">
                    <button
                        onClick={handleDismissAll}
                        className="text-[11px] text-amber-600 dark:text-amber-400
                                   hover:text-amber-800 dark:hover:text-amber-200
                                   transition-colors px-2 py-0.5"
                    >
                        全部关闭
                    </button>
                </div>
            )}
        </div>
    );
};
