/**
 * AnomalyDetectionEngine — Phase 2 异常检测引擎
 * 基于规则的 Worker 行为异常检测，目标性能: < 5ms
 */

import type { ToolCallRecord, AnomalyEvent } from '@/types/apos';
import type { WorkerInfo } from '@/types';
import { useAnomalyStore } from '@/store/anomalyStore';

export class AnomalyDetectionEngine {
    /**
     * 评估 Worker 的工具调用记录，检测异常
     * 目标性能：< 5ms
     */
    evaluate(worker: WorkerInfo, records: ToolCallRecord[]): AnomalyEvent[] {
        const anomalies: AnomalyEvent[] = [];
        const now = Date.now();
        const store = useAnomalyStore.getState();

        // 规则1：循环检测 — 最近10次调用中50%+重复
        if (!store.isInCooldown(worker.workerId, 'loop_detection')) {
            if (this.checkLoopDetection(records)) {
                anomalies.push(this.createEvent(worker, 'loop_detection', 'error',
                    `Worker ${worker.workerId} 检测到重复调用模式（最近10次中50%+参数相同）`));
            }
        }

        // 规则2：卡死检测 — 超过60s无活动
        if (!store.isInCooldown(worker.workerId, 'stall_detection')) {
            if (this.checkStallDetection(records, now)) {
                anomalies.push(this.createEvent(worker, 'stall_detection', 'critical',
                    `Worker ${worker.workerId} 超过60s无任何工具调用活动`));
            }
        }

        // 规则3：连续失败 — 最近5次中3+错误
        if (!store.isInCooldown(worker.workerId, 'error_cascade')) {
            if (this.checkErrorCascade(records)) {
                anomalies.push(this.createEvent(worker, 'error_cascade', 'error',
                    `Worker ${worker.workerId} 连续失败（最近5次中${records.slice(-5).filter(r => r.status === 'error').length}次错误）`));
            }
        }

        return anomalies;
    }

    private checkLoopDetection(records: ToolCallRecord[]): boolean {
        const recent = records.slice(-10);
        if (recent.length < 4) return false;
        const keys = recent.map(r => `${r.toolName}:${r.paramsHash}`);
        const uniqueKeys = new Set(keys);
        return uniqueKeys.size < recent.length * 0.5;
    }

    private checkStallDetection(records: ToolCallRecord[], now: number): boolean {
        if (records.length === 0) return false;
        const lastRecord = records[records.length - 1];
        return (now - lastRecord.timestamp) > 60_000;
    }

    private checkErrorCascade(records: ToolCallRecord[]): boolean {
        const recent = records.slice(-5);
        if (recent.length < 3) return false;
        const errorCount = recent.filter(r => r.status === 'error').length;
        return errorCount >= 3;
    }

    private createEvent(
        worker: WorkerInfo,
        ruleId: AnomalyEvent['ruleId'],
        severity: AnomalyEvent['severity'],
        message: string
    ): AnomalyEvent {
        return {
            id: crypto.randomUUID(),
            swarmId: '',  // 由调用方补充
            workerId: worker.workerId,
            workerName: worker.currentTask || worker.workerId,
            ruleId,
            severity,
            message,
            detectedAt: Date.now(),
            resolvedAt: null,
            resolution: null,
        };
    }
}

// 单例导出
export const anomalyEngine = new AnomalyDetectionEngine();
