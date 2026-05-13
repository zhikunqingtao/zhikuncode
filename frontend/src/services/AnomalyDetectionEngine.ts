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
        try {
            const anomalies: AnomalyEvent[] = [];
            const now = Date.now();
            const store = useAnomalyStore.getState();

            // 规则1：循环检测 — 连续 5 次相同 fingerprint
            if (!store.isInCooldown(worker.workerId, 'loop_detection')) {
                if (this.checkLoopDetection(records)) {
                    anomalies.push(this.createEvent(worker, 'loop_detection', 'error',
                        `Worker ${worker.workerId} 检测到重复调用模式（连续5次相同操作）`));
                }
            }

            // 规则2：卡死检测 — 超过 90s 无活动（排除 elicitation 等待）
            if (!store.isInCooldown(worker.workerId, 'stall_detection')) {
                if (this.checkStallDetection(worker, records, now)) {
                    anomalies.push(this.createEvent(worker, 'stall_detection', 'critical',
                        `Worker ${worker.workerId} 超过90s无任何工具调用活动`));
                }
            }

            // 规则3：连续失败 — 最近 10 条中 4+ 错误
            if (!store.isInCooldown(worker.workerId, 'error_cascade')) {
                if (this.checkErrorCascade(records)) {
                    const errorCount = records.slice(-10).filter(r => r.status === 'error').length;
                    anomalies.push(this.createEvent(worker, 'error_cascade', 'error',
                        `Worker ${worker.workerId} 连续失败（最近10次中${errorCount}次错误）`));
                }
            }

            return anomalies;
        } catch (err) {
            console.error('[AnomalyDetectionEngine] evaluate() error:', err);
            return [];
        }
    }

    /**
     * 循环检测：检测连续 5 次相同 fingerprint（toolName:paramsHash）
     * 最小样本要求：≥ 8 条记录
     */
    private checkLoopDetection(records: ToolCallRecord[]): boolean {
        if (records.length < 8) return false;
        const recent = records.slice(-10);

        // 检测连续相同 fingerprint 的最大长度
        let maxConsecutive = 1;
        let currentConsecutive = 1;
        for (let i = 1; i < recent.length; i++) {
            const prevKey = `${recent[i - 1].toolName}:${recent[i - 1].paramsHash}`;
            const currKey = `${recent[i].toolName}:${recent[i].paramsHash}`;
            if (currKey === prevKey) {
                currentConsecutive++;
                maxConsecutive = Math.max(maxConsecutive, currentConsecutive);
            } else {
                currentConsecutive = 1;
            }
        }
        return maxConsecutive >= 5;
    }

    /**
     * 卡死检测：超过 90s 无活动，排除非工作状态
     */
    private checkStallDetection(worker: WorkerInfo, records: ToolCallRecord[], now: number): boolean {
        if (records.length === 0) return false;
        const lastRecord = records[records.length - 1];
        const elapsed = now - lastRecord.timestamp;

        // 阈值提升到 90s
        if (elapsed <= 90_000) return false;

        // 基于 status 枚举判断（可靠）：IDLE/STARTING 状态不算卡死
        if (worker.status === 'IDLE' || worker.status === 'STARTING') {
            return false;
        }

        return true;
    }

    /**
     * 连续失败检测：最近 10 条中 4+ 错误
     * 最小样本要求：≥ 5 条记录
     */
    private checkErrorCascade(records: ToolCallRecord[]): boolean {
        if (records.length < 5) return false;
        const recent = records.slice(-10);
        const errorCount = recent.filter(r => r.status === 'error').length;
        return errorCount >= 4;
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
