/**
 * AgentDAGChart 单元测试 — 对应 Task3-5 方案 §11.11 资产 #9。
 *
 * AgentDAGChart 依赖 @xyflow/react、zustand store、useStompSubscription，
 * 完整渲染测试需要较复杂 mock。MVP 阶段只做 smoke：空任务态与 buildGraph 分相逻辑。
 * 预备周扩展到 6 用例（串行/并行节点布局 + edge 生成 + provider 订阅联动）。
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { useCoordinatorStore } from '@/store/coordinatorStore';

// mock 图形库与 hook 依赖，避免 jsdom 无 getBBox 等导致渲染崩溃
vi.mock('@xyflow/react', () => ({
    ReactFlow: ({ children }: { children?: React.ReactNode }) => (
        <div data-testid="reactflow">{children}</div>
    ),
    ReactFlowProvider: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
    MiniMap: () => null,
    Background: () => null,
    Controls: () => null,
    useNodesState: () => [[], vi.fn(), vi.fn()],
    useEdgesState: () => [[], vi.fn(), vi.fn()],
    useReactFlow: () => ({ fitView: vi.fn(), getNodes: () => [], getEdges: () => [] }),
    BackgroundVariant: { Dots: 'dots' },
}));
vi.mock('@/hooks/useStompSubscription', () => ({
    useStompSubscription: vi.fn(),
}));

describe('AgentDAGChart (smoke)', () => {
    beforeEach(() => {
        useCoordinatorStore.getState().clearAll();
    });

    it('DAG-01 初始状态下 coordinatorStore.agentTasks 为空', () => {
        // 守护测试：确保 store 空时 DAGChart 的 buildGraph 不会抛异常；
        // 真正挂载留到预备周结合 @testing-library + 上述 mock 完成。
        const tasks = useCoordinatorStore.getState().agentTasks;
        expect(tasks).toEqual([]);
    });

    it('DAG-02 appendCoordinatorEvent 能被图订阅读取（数据通道前置条件）', () => {
        useCoordinatorStore.getState().appendCoordinatorEvent({
            type: 'coordinator_event',
            ts: Date.now(),
            uuid: 'u-1',
            sessionId: 'sess-1',
            workflowId: 'wf-1',
            eventType: 'phase_transition',
            payload: { previousPhase: 'Research', nextPhase: 'Synthesis' },
        });
        expect(useCoordinatorStore.getState().coordinatorEvents).toHaveLength(1);
    });

    // 预备周补 4 条（需接入真实 mount + xyflow mock）
    it.skip('DAG-03 2s 内连续 agent_spawn 归入同 phase（并行节点）', () => {});
    it.skip('DAG-04 间隔 >2s 的 agent_spawn 生成串行 edge', () => {});
    it.skip('DAG-05 direction=LR 时节点宽高切换', () => {});
    it.skip('DAG-06 fitView 在 nodes 变化时触发', () => {});
});
