/**
 * AnomalyAlertPanel — 异常告警面板
 * 展示活跃异常事件，支持中止 Worker 和忽略操作
 */

import React, { useCallback, useState } from 'react';
import { useAnomalyStore } from '@/store/anomalyStore';
import { useSessionStore } from '@/store/sessionStore';
import type { AnomalyEvent } from '@/types/apos';

/** 根据 ruleId 返回对应图标 */
function getRuleIcon(ruleId: AnomalyEvent['ruleId']): string {
    switch (ruleId) {
        case 'loop_detection':
            return '🔄';
        case 'stall_detection':
            return '⏳';
        case 'error_cascade':
            return '❌';
        default:
            return '⚠️';
    }
}

/** 根据 severity 返回着色 class */
function getSeverityColor(severity: AnomalyEvent['severity']): string {
    switch (severity) {
        case 'critical':
            return 'text-red-600 dark:text-red-400';
        case 'error':
            return 'text-yellow-600 dark:text-yellow-400';
        default:
            return 'text-gray-600 dark:text-gray-400';
    }
}

/** 根据 severity 返回背景色 */
function getSeverityBg(severity: AnomalyEvent['severity']): string {
    switch (severity) {
        case 'critical':
            return 'bg-red-50 dark:bg-red-900/20 border-red-200 dark:border-red-800';
        case 'error':
            return 'bg-yellow-50 dark:bg-yellow-900/20 border-yellow-200 dark:border-yellow-800';
        default:
            return 'bg-gray-50 dark:bg-gray-800 border-gray-200 dark:border-gray-700';
    }
}

export const AnomalyAlertPanel: React.FC = () => {
    const { activeAnomalies, resolveAnomaly } = useAnomalyStore();
    const [abortingIds, setAbortingIds] = useState<Set<string>>(new Set());

    const handleAbort = useCallback(async (anomaly: AnomalyEvent) => {
        setAbortingIds((prev) => new Set(prev).add(anomaly.id));
        try {
            const sessionId = useSessionStore.getState().sessionId || 'default';
            await fetch(`/api/swarm/${anomaly.swarmId}/worker/${anomaly.workerId}/abort`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ reason: 'user_abort', triggeredBy: 'anomaly_alert', sessionId }),
            });
            resolveAnomaly(anomaly.id, 'abort');
        } catch (err) {
            console.error('Failed to abort worker:', err);
        } finally {
            setAbortingIds((prev) => {
                const next = new Set(prev);
                next.delete(anomaly.id);
                return next;
            });
        }
    }, [resolveAnomaly]);

    const handleDismiss = useCallback((anomalyId: string) => {
        resolveAnomaly(anomalyId, 'dismiss');
    }, [resolveAnomaly]);

    return (
        <div className="p-6">
            {/* Header with count badge */}
            <div className="flex items-center gap-2 mb-4">
                <h2 className="text-lg font-bold text-gray-900 dark:text-gray-100">
                    Anomaly Alerts
                </h2>
                {activeAnomalies.length > 0 && (
                    <span className="inline-flex items-center justify-center px-2 py-0.5 text-xs font-bold rounded-full bg-red-500 text-white">
                        {activeAnomalies.length}
                    </span>
                )}
            </div>

            {/* Empty state */}
            {activeAnomalies.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-12 text-gray-400 dark:text-gray-500">
                    <span className="text-4xl mb-3">✅</span>
                    <p className="text-sm font-medium">运行正常</p>
                    <p className="text-xs mt-1">No anomalies detected</p>
                </div>
            ) : (
                <div className="space-y-3">
                    {activeAnomalies.map((anomaly) => (
                        <div
                            key={anomaly.id}
                            className={`rounded-lg border p-4 ${getSeverityBg(anomaly.severity)}`}
                        >
                            {/* Top row: icon + worker name + severity */}
                            <div className="flex items-center gap-2 mb-2">
                                <span className="text-lg">{getRuleIcon(anomaly.ruleId)}</span>
                                <span className={`font-semibold text-sm ${getSeverityColor(anomaly.severity)}`}>
                                    {anomaly.workerName}
                                </span>
                                <span className={`text-xs px-1.5 py-0.5 rounded font-medium ${
                                    anomaly.severity === 'critical'
                                        ? 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300'
                                        : 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/40 dark:text-yellow-300'
                                }`}>
                                    {anomaly.severity.toUpperCase()}
                                </span>
                            </div>

                            {/* Message */}
                            <p className="text-sm text-gray-700 dark:text-gray-300 mb-3">
                                {anomaly.message}
                            </p>

                            {/* Action buttons */}
                            <div className="flex gap-2">
                                <button
                                    onClick={() => handleAbort(anomaly)}
                                    disabled={abortingIds.has(anomaly.id)}
                                    className="px-3 py-1.5 text-xs font-medium rounded-md bg-red-600 text-white hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                                >
                                    {abortingIds.has(anomaly.id) ? '中止中...' : '中止 Worker'}
                                </button>
                                <button
                                    onClick={() => handleDismiss(anomaly.id)}
                                    className="px-3 py-1.5 text-xs font-medium rounded-md bg-gray-200 text-gray-700 hover:bg-gray-300 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600 transition-colors"
                                >
                                    忽略
                                </button>
                            </div>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
};

