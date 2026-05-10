/**
 * CoordinatorStore 单元测试 — 对应 Task3-5 方案 §11.11 资产 #9。
 *
 * MVP 5 用例，预备周补到 12 用例（coordinatorStore 核心动作全覆盖）。
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { useCoordinatorStore } from '@/store/coordinatorStore';
import type {
    WorkflowPhaseUpdatePayload,
    AgentSpawnPayload,
    CoordinatorEventEnvelope,
} from '@/types';

function resetStore() {
    useCoordinatorStore.getState().clearAll();
}

function phaseUpdate(overrides: Partial<WorkflowPhaseUpdatePayload> = {}): WorkflowPhaseUpdatePayload {
    return {
        type: 'workflow_phase_update',
        workflowId: 'wf-1',
        phaseName: 'Research',
        status: 'RUNNING',
        phaseIndex: 0,
        totalPhases: 4,
        phasePrompt: 'initial research',
        objective: 'build a thing',
        ...overrides,
    };
}

function spawnPayload(overrides: Partial<AgentSpawnPayload> = {}): AgentSpawnPayload {
    return {
        type: 'agent_spawn',
        taskId: 't-1',
        agentName: 'researcher',
        agentType: 'research',
        ...overrides,
    };
}

describe('CoordinatorStore', () => {
    beforeEach(() => {
        resetStore();
    });

    it('CS-01 首次收到 phase_update 初始化 activeWorkflow 并展开面板', () => {
        useCoordinatorStore.getState().updateWorkflowPhase(phaseUpdate());

        const state = useCoordinatorStore.getState();
        expect(state.activeWorkflow?.workflowId).toBe('wf-1');
        expect(state.activeWorkflow?.phases).toHaveLength(4);
        expect(state.activeWorkflow?.phases[0].status).toBe('active');
        expect(state.panelVisible).toBe(true);
    });

    it('CS-02 phaseIndex 推进时之前阶段标记 completed', () => {
        const s = useCoordinatorStore.getState();
        s.updateWorkflowPhase(phaseUpdate({ phaseIndex: 0, phaseName: 'Research' }));
        s.updateWorkflowPhase(phaseUpdate({ phaseIndex: 2, phaseName: 'Implementation' }));

        const wf = useCoordinatorStore.getState().activeWorkflow!;
        expect(wf.phases[0].status).toBe('completed');
        expect(wf.phases[1].status).toBe('completed');
        expect(wf.phases[2].status).toBe('active');
    });

    it('CS-03 agent_spawn 添加任务且保持最近 50 条上限', () => {
        const s = useCoordinatorStore.getState();
        for (let i = 0; i < 60; i++) {
            s.addAgentTask(spawnPayload({ taskId: `t-${i}`, agentName: `agent-${i}` }));
        }

        const tasks = useCoordinatorStore.getState().agentTasks;
        expect(tasks).toHaveLength(50);
        expect(tasks[0].taskId).toBe('t-10');
    });

    it('CS-04 appendCoordinatorEvent 环形缓冲最多 200 条', () => {
        const s = useCoordinatorStore.getState();
        const envelope = (ts: number): CoordinatorEventEnvelope => ({
            type: 'coordinator_event',
            ts,
            uuid: `u-${ts}`,
            sessionId: 'sess-1',
            workflowId: 'wf-1',
            eventType: 'phase_transition',
            payload: {},
        });
        for (let i = 0; i < 250; i++) s.appendCoordinatorEvent(envelope(i));

        const evs = useCoordinatorStore.getState().coordinatorEvents;
        expect(evs).toHaveLength(200);
        expect(evs[0].ts).toBe(50);
    });

    it('CS-05 dismissWarning 标记警告 dismissed=true，clearDismissedWarnings 清理', () => {
        const s = useCoordinatorStore.getState();
        s.addDelegationWarning('test warning');
        const id = useCoordinatorStore.getState().delegationWarnings[0].id;

        s.dismissWarning(id);
        expect(useCoordinatorStore.getState().delegationWarnings[0].dismissed).toBe(true);

        s.clearDismissedWarnings();
        expect(useCoordinatorStore.getState().delegationWarnings).toHaveLength(0);
    });

    // 预备周补 7 条
    it.skip('CS-06 completeAgentTask 把对应任务 status=completed 并写入 result', () => {});
    it.skip('CS-07 updateAgentTask 写入 progress 字符串（对象序列化）', () => {});
    it.skip('CS-08 isComplete && phaseIndex=-1 时清理 active 阶段为 completed', () => {});
    it.skip('CS-09 clearCoordinatorEvents 仅清事件不动工作流', () => {});
    it.skip('CS-10 addDelegationWarning 超 20 条 FIFO', () => {});
    it.skip('CS-11 setPanelVisible(false) 手动收起面板', () => {});
    it.skip('CS-12 clearAll 完全复位全部字段', () => {});
});
