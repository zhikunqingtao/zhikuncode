/**
 * CoordinatorStore — Coordinator 四阶段工作流状态管理
 * 管理工作流实例、阶段进度、Agent 任务和委派警告
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import type {
    WorkflowState,
    WorkflowPhaseState,
    WorkflowPhaseUpdatePayload,
    AgentTask,
    DelegationWarning,
    AgentSpawnPayload,
} from '@/types';

/** 四个阶段的默认定义 */
const DEFAULT_PHASES: WorkflowPhaseState[] = [
    { name: 'Research',       index: 0, status: 'pending', prompt: '' },
    { name: 'Synthesis',      index: 1, status: 'pending', prompt: '' },
    { name: 'Implementation', index: 2, status: 'pending', prompt: '' },
    { name: 'Verification',   index: 3, status: 'pending', prompt: '' },
];

export interface CoordinatorStoreState {
    /** 当前活跃的工作流 */
    activeWorkflow: WorkflowState | null;

    /** Agent 任务列表 */
    agentTasks: AgentTask[];

    /** 委派警告列表 */
    delegationWarnings: DelegationWarning[];

    /** 工作流面板可见性 */
    panelVisible: boolean;

    // ═══ Actions ═══

    /** 处理 workflow_phase_update 消息 */
    updateWorkflowPhase: (data: WorkflowPhaseUpdatePayload) => void;

    /** 处理 agent_spawn 消息（从 taskStore 转发） */
    addAgentTask: (data: AgentSpawnPayload) => void;

    /** 处理 agent_update 消息 */
    updateAgentTask: (taskId: string, progress: string) => void;

    /** 处理 agent_complete 消息 */
    completeAgentTask: (taskId: string, result: string) => void;

    /** 添加委派警告 */
    addDelegationWarning: (message: string) => void;

    /** 关闭（隐藏）指定警告 */
    dismissWarning: (id: string) => void;

    /** 清除所有已关闭的警告 */
    clearDismissedWarnings: () => void;

    /** 设置面板可见性 */
    setPanelVisible: (visible: boolean) => void;

    /** 清除所有数据 */
    clearAll: () => void;
}

export const useCoordinatorStore = create<CoordinatorStoreState>()(
    immer((set) => ({
        activeWorkflow: null,
        agentTasks: [],
        delegationWarnings: [],
        panelVisible: false,

        updateWorkflowPhase: (data) => set((state) => {
            const isComplete = data.status === 'COMPLETED' || data.status === 'FAILED' || data.status === 'CANCELLED';

            if (!state.activeWorkflow) {
                // 首次收到 — 初始化工作流
                const phases = DEFAULT_PHASES.map((p) => ({
                    ...p,
                    status: (
                        p.index < data.phaseIndex ? 'completed' as const
                        : p.index === data.phaseIndex ? 'active' as const
                        : 'pending' as const
                    ),
                    prompt: p.name === data.phaseName ? data.phasePrompt : p.prompt,
                    startTime: p.index === data.phaseIndex ? Date.now() : undefined,
                }));

                state.activeWorkflow = {
                    workflowId: data.workflowId,
                    objective: data.objective,
                    status: data.status,
                    currentPhaseIndex: data.phaseIndex,
                    phases,
                    startTime: Date.now(),
                };
                state.panelVisible = true;
            } else {
                // 更新现有工作流
                const wf = state.activeWorkflow;

                wf.status = data.status;
                wf.currentPhaseIndex = data.phaseIndex;

                // 标记之前的阶段为 completed
                for (const phase of wf.phases) {
                    if (phase.index < data.phaseIndex) {
                        if (phase.status !== 'completed') {
                            phase.status = 'completed';
                            phase.endTime = Date.now();
                        }
                    } else if (phase.index === data.phaseIndex) {
                        phase.status = 'active';
                        phase.prompt = data.phasePrompt || phase.prompt;
                        if (!phase.startTime) {
                            phase.startTime = Date.now();
                        }
                    } else if (isComplete) {
                        // 工作流完成时，当前阶段之后的保持 pending
                    }
                }

                // 如果工作流完成，标记当前活跃阶段为 completed
                if (isComplete && data.phaseIndex === -1) {
                    for (const phase of wf.phases) {
                        if (phase.status === 'active') {
                            phase.status = 'completed';
                            phase.endTime = Date.now();
                        }
                    }
                }
            }
        }),

        addAgentTask: (data) => set((state) => {
            state.agentTasks.push({
                taskId: data.taskId,
                agentName: data.agentName,
                agentType: data.agentType,
                description: data.agentName,
                status: 'running',
                startTime: Date.now(),
            });
            // 保持最多 50 个任务
            if (state.agentTasks.length > 50) {
                state.agentTasks = state.agentTasks.slice(-50);
            }
        }),

        updateAgentTask: (taskId, progress) => set((state) => {
            const task = state.agentTasks.find((t) => t.taskId === taskId);
            if (task) {
                task.progress = typeof progress === 'string' ? progress : JSON.stringify(progress);
            }
        }),

        completeAgentTask: (taskId, result) => set((state) => {
            const task = state.agentTasks.find((t) => t.taskId === taskId);
            if (task) {
                task.status = 'completed';
                task.result = typeof result === 'string' ? result : JSON.stringify(result);
            }
        }),

        addDelegationWarning: (message) => set((state) => {
            state.delegationWarnings.push({
                id: crypto.randomUUID(),
                message,
                timestamp: Date.now(),
                dismissed: false,
            });
            // 保持最多 20 个警告
            if (state.delegationWarnings.length > 20) {
                state.delegationWarnings = state.delegationWarnings.slice(-20);
            }
        }),

        dismissWarning: (id) => set((state) => {
            const warning = state.delegationWarnings.find((w) => w.id === id);
            if (warning) {
                warning.dismissed = true;
            }
        }),

        clearDismissedWarnings: () => set((state) => {
            state.delegationWarnings = state.delegationWarnings.filter((w) => !w.dismissed);
        }),

        setPanelVisible: (visible) => set((state) => {
            state.panelVisible = visible;
        }),

        clearAll: () => set((state) => {
            state.activeWorkflow = null;
            state.agentTasks = [];
            state.delegationWarnings = [];
            state.panelVisible = false;
        }),
    }))
);
