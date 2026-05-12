/**
 * AnomalyStore — Phase 2 异常事件状态管理
 * 管理 Worker 异常检测结果、冷却控制和已解决历史
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import type { AnomalyEvent } from '@/types/apos';
import { notificationService } from '@/services/NotificationService';

interface AnomalyStoreState {
    activeAnomalies: AnomalyEvent[];
    resolvedHistory: AnomalyEvent[];
    cooldownMap: Map<string, number>;  // key: `${workerId}:${ruleId}`, value: timestamp

    addAnomaly: (anomaly: AnomalyEvent) => void;
    resolveAnomaly: (anomalyId: string, resolution: string) => void;
    isInCooldown: (workerId: string, ruleId: string) => boolean;
    clearResolved: () => void;
}

export const useAnomalyStore = create<AnomalyStoreState>()(
    immer((set, get) => ({
        activeAnomalies: [],
        resolvedHistory: [],
        cooldownMap: new Map(),

        addAnomaly: (anomaly) => {
            const key = `${anomaly.workerId}:${anomaly.ruleId}`;
            // 冷却期检查
            if (get().isInCooldown(anomaly.workerId, anomaly.ruleId)) return;

            set(state => {
                state.activeAnomalies.push(anomaly);
                state.cooldownMap.set(key, Date.now());
            });

            // 推送通知：仅 critical/error 级别触发
            if (anomaly.severity === 'critical' || anomaly.severity === 'error') {
                const ruleNameMap: Record<string, string> = {
                    loop_detection: '循环检测',
                    stall_detection: '卡死检测',
                    error_cascade: '连续失败',
                };
                const ruleLabel = ruleNameMap[anomaly.ruleId] || anomaly.ruleId;
                notificationService.send(
                    `[${anomaly.severity.toUpperCase()}] ${ruleLabel}`,
                    {
                        body: `${anomaly.workerName}: ${anomaly.message}`,
                        tag: `anomaly-${anomaly.workerId}-${anomaly.ruleId}`,
                    }
                );
            }
        },

        resolveAnomaly: (anomalyId, resolution) => set(state => {
            const idx = state.activeAnomalies.findIndex(a => a.id === anomalyId);
            if (idx !== -1) {
                const anomaly = state.activeAnomalies[idx];
                // 解决时设置冷却期，防止相同异常短时间内重复触发
                const key = `${anomaly.workerId}:${anomaly.ruleId}`;
                state.cooldownMap.set(key, Date.now());
                const resolved = { ...anomaly, resolvedAt: Date.now(), resolution };
                state.activeAnomalies.splice(idx, 1);
                state.resolvedHistory.push(resolved as AnomalyEvent);
            }
        }),

        isInCooldown: (workerId, ruleId) => {
            const key = `${workerId}:${ruleId}`;
            const lastTime = get().cooldownMap.get(key);
            if (!lastTime) return false;
            return (Date.now() - lastTime) < 30_000;  // 30s 固定冷却
        },

        clearResolved: () => set(state => { state.resolvedHistory = []; }),
    }))
);

// 暴露到 window 以便 E2E 测试可以访问同一实例（避免 Vite 动态 import 模块双实例问题）
if (typeof window !== 'undefined') {
    (window as any).__anomalyStore__ = useAnomalyStore;
}
