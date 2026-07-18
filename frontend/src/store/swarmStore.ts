/**
 * SwarmStore — Swarm 多Agent并行协作状态管理
 * 管理 Swarm 实例状态、Worker 进度和消息日志。
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import type {
    SwarmInfo,
    WorkerInfo,
    SwarmLogEntry,
    SwarmStateUpdatePayload,
    WorkerProgressPayload,
} from '@/types';
import { generateUUID } from '@/utils/uuid';

export interface SwarmStoreState {
    /** 活跃 Swarm 实例 (swarmId → SwarmInfo) */
    swarms: Map<string, SwarmInfo>;

    /** Swarm 消息日志 */
    logs: SwarmLogEntry[];

    /** 当前查看的 Swarm ID */
    activeSwarmId: string | null;

    /** Swarm 面板是否可见 */
    panelVisible: boolean;

    // ═══ Actions ═══

    /** 处理 swarm_state_update 消息 */
    updateSwarmState: (data: SwarmStateUpdatePayload) => void;

    /** 处理 worker_progress 消息 */
    updateWorkerProgress: (data: WorkerProgressPayload) => void;

    /** 添加日志条目 */
    addLogEntry: (entry: Omit<SwarmLogEntry, 'id' | 'timestamp'>) => void;

    /** 设置活跃 Swarm */
    setActiveSwarm: (swarmId: string | null) => void;

    /** 切换面板可见性 */
    togglePanel: () => void;

    /** 设置面板可见性 */
    setPanelVisible: (visible: boolean) => void;

    /** 清除指定 Swarm 的数据 */
    removeSwarm: (swarmId: string) => void;

    /** 清除所有数据 */
    clearAll: () => void;
}

export const useSwarmStore = create<SwarmStoreState>()(
    immer((set) => ({
        swarms: new Map(),
        logs: [],
        activeSwarmId: null,
        panelVisible: false,

        updateSwarmState: (data) => set((state) => {
            const workers: Record<string, WorkerInfo> = {};
            if (data.workers) {
                Object.entries(data.workers).forEach(([id, ws]) => {
                    workers[id] = {
                        workerId: ws.workerId,
                        status: ws.status,
                        currentTask: ws.currentTask,
                        toolCallCount: ws.toolCallCount,
                        tokenConsumed: ws.tokenConsumed,
                        recentToolCalls: [],
                        progressPercent: null,
                        totalSteps: null,
                        completedSteps: null,
                        errorMessage: null,
                        currentStepDescription: null,
                        terminationReason: null,
                    };
                });
            }

            state.swarms.set(data.swarmId, {
                swarmId: data.swarmId,
                teamName: data.swarmId, // Will be updated from API if needed
                phase: data.phase,
                activeWorkers: data.activeWorkers,
                totalWorkers: data.totalWorkers,
                completedTasks: data.completedTasks,
                totalTasks: data.totalTasks,
                workers,
            });

            // Auto-show panel when a swarm starts
            if (data.phase === 'RUNNING' || data.phase === 'INITIALIZING') {
                state.panelVisible = true;
                state.activeSwarmId = data.swarmId;
            }

            // Add log entry for phase changes
            state.logs.push({
                id: generateUUID(),
                timestamp: Date.now(),
                type: 'message',
                content: `Swarm ${data.swarmId} → ${data.phase} (${data.activeWorkers}/${data.totalWorkers} workers)`,
            });

            // Keep logs under 200 entries
            if (state.logs.length > 200) {
                state.logs = state.logs.slice(-200);
            }
        }),

        updateWorkerProgress: (data) => set((state) => {
            const swarm = state.swarms.get(data.swarmId);
            if (swarm) {
                swarm.workers[data.workerId] = {
                    workerId: data.workerId,
                    status: data.status,
                    currentTask: data.currentTask,
                    toolCallCount: data.toolCallCount,
                    tokenConsumed: data.tokenConsumed,
                    recentToolCalls: data.recentToolCalls || [],
                    progressPercent: data.progressPercent ?? null,
                    totalSteps: data.totalSteps ?? null,
                    completedSteps: data.completedSteps ?? null,
                    errorMessage: data.errorMessage ?? null,
                    currentStepDescription: data.currentStepDescription ?? null,
                    terminationReason: data.terminationReason ?? null,
                };
            }

            // Add log for status transitions
            if (data.status === 'WORKING') {
                state.logs.push({
                    id: generateUUID(),
                    timestamp: Date.now(),
                    type: 'worker_start',
                    workerId: data.workerId,
                    content: `Worker ${data.workerId} started: ${data.currentTask?.substring(0, 60) ?? ''}`,
                });
            } else if (data.status === 'IDLE') {
                state.logs.push({
                    id: generateUUID(),
                    timestamp: Date.now(),
                    type: 'worker_complete',
                    workerId: data.workerId,
                    content: `Worker ${data.workerId} completed (${data.toolCallCount} tools, ${data.tokenConsumed} tokens)`,
                });
            } else if (data.status === 'TERMINATED') {
                state.logs.push({
                    id: generateUUID(),
                    timestamp: Date.now(),
                    type: 'worker_error',
                    workerId: data.workerId,
                    content: `Worker ${data.workerId} terminated`,
                });
            }
        }),

        addLogEntry: (entry) => set((state) => {
            state.logs.push({
                ...entry,
                id: generateUUID(),
                timestamp: Date.now(),
            });
            if (state.logs.length > 200) {
                state.logs = state.logs.slice(-200);
            }
        }),

        setActiveSwarm: (swarmId) => set((state) => {
            state.activeSwarmId = swarmId;
        }),

        togglePanel: () => set((state) => {
            state.panelVisible = !state.panelVisible;
        }),

        setPanelVisible: (visible) => set((state) => {
            state.panelVisible = visible;
        }),

        removeSwarm: (swarmId) => set((state) => {
            state.swarms.delete(swarmId);
            if (state.activeSwarmId === swarmId) {
                state.activeSwarmId = null;
            }
        }),

        clearAll: () => set((state) => {
            state.swarms.clear();
            state.logs = [];
            state.activeSwarmId = null;
            state.panelVisible = false;
        }),
    }))
);
