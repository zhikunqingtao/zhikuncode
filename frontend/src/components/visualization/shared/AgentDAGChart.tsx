/**
 * AgentDAGChart — Agent 协作 DAG 可视化主组件
 * 使用 @xyflow/react 渲染 Agent 任务的有向无环图
 * 订阅 coordinatorStore 和 swarmStore 实时更新节点状态
 */

import { useMemo, useState, useCallback, useRef, useEffect } from 'react';
import {
  ReactFlow,
  MiniMap,
  Background,
  Controls,
  useNodesState,
  useEdgesState,
  BackgroundVariant,
  type Node,
  type Edge,
  useReactFlow,
  ReactFlowProvider,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import {
  ArrowDownFromLine,
  ArrowRightFromLine,
  Maximize2,
  Minimize2,
  X,
  Network,
} from 'lucide-react';
import { useCoordinatorStore } from '@/store/coordinatorStore';
import { useSwarmStore } from '@/store/swarmStore';
import { computeDAGLayout } from '@/utils/dag-layout';
import { AgentDAGNode, type AgentDAGNodeData } from './AgentDAGNode';
import type { AgentTask, SwarmInfo, WorkerInfo } from '@/types';

const nodeTypes = { agentNode: AgentDAGNode };

/** 将 AgentTask + Swarm Workers 转换为 React Flow 的 nodes/edges */
function buildGraph(
  agentTasks: AgentTask[],
  swarms: Map<string, SwarmInfo>,
  _activeWorkflowPhaseIndex: number,
  direction: 'TB' | 'LR'
): { nodes: Node[]; edges: Edge[] } {
  if (agentTasks.length === 0) return { nodes: [], edges: [] };

  const rawNodes: Array<{ id: string; width: number; height: number; data: AgentDAGNodeData }> = [];
  const rawEdges: Array<{ source: string; target: string }> = [];

  // 按 startTime 排序任务
  const sortedTasks = [...agentTasks].sort((a, b) => a.startTime - b.startTime);

  // 根据时间窗口推断并行/串行关系
  // 时间间隔 < 2s 的认为是同一 phase（并行），否则串行
  const phases: AgentTask[][] = [];
  let currentPhase: AgentTask[] = [];

  sortedTasks.forEach((task, i) => {
    if (i === 0) {
      currentPhase.push(task);
    } else {
      const timeDiff = task.startTime - sortedTasks[i - 1].startTime;
      if (timeDiff < 2000) {
        currentPhase.push(task);
      } else {
        phases.push(currentPhase);
        currentPhase = [task];
      }
    }
  });
  if (currentPhase.length > 0) phases.push(currentPhase);

  // 为每个任务创建节点
  sortedTasks.forEach((task) => {
    rawNodes.push({
      id: task.taskId,
      width: 220,
      height: 80,
      data: {
        agentName: task.agentName,
        agentType: task.agentType,
        description: task.description,
        status: task.status,
        progress: task.progress,
        result: task.result,
        startTime: task.startTime,
      },
    });
  });

  // Swarm Workers 作为额外节点
  swarms.forEach((swarm) => {
    Object.values(swarm.workers).forEach((worker: WorkerInfo) => {
      const workerId = `swarm-${swarm.swarmId}-${worker.workerId}`;
      const workerStatus =
        worker.status === 'WORKING' ? 'running' :
        worker.status === 'IDLE' ? 'completed' :
        worker.status === 'TERMINATED' ? 'failed' : 'pending';

      rawNodes.push({
        id: workerId,
        width: 220,
        height: 80,
        data: {
          agentName: `Worker ${worker.workerId}`,
          agentType: 'swarm-worker',
          description: worker.currentTask || 'Idle',
          status: workerStatus as AgentDAGNodeData['status'],
        },
      });

      // 找到对应的 Agent 任务（匹配 swarmId）并建立边
      const parentTask = sortedTasks.find((t) =>
        t.agentName.toLowerCase().includes('swarm') ||
        t.agentType.toLowerCase().includes('swarm')
      );
      if (parentTask) {
        rawEdges.push({ source: parentTask.taskId, target: workerId });
      }
    });
  });

  // 建立 phase 间的串行边
  for (let i = 1; i < phases.length; i++) {
    const prevPhase = phases[i - 1];
    const currPhase = phases[i];
    // 前一 phase 的所有任务 → 当前 phase 的第一个任务
    prevPhase.forEach((prevTask) => {
      rawEdges.push({ source: prevTask.taskId, target: currPhase[0].taskId });
    });
    // 同一 phase 内如果有多个任务，第一个任务连到后续任务
    if (currPhase.length > 1) {
      for (let j = 1; j < currPhase.length; j++) {
        rawEdges.push({ source: currPhase[0].taskId, target: currPhase[j].taskId });
      }
    }
  }

  // 使用 dagre 计算布局
  const layout = computeDAGLayout(
    rawNodes.map((n) => ({ id: n.id, width: n.width, height: n.height })),
    rawEdges,
    direction
  );

  const posMap = new Map(layout.nodes.map((n) => [n.id, { x: n.x, y: n.y }]));

  const nodes: Node[] = rawNodes.map((n) => {
    const pos = posMap.get(n.id) || { x: 0, y: 0 };
    return {
      id: n.id,
      type: 'agentNode',
      position: { x: pos.x - n.width / 2, y: pos.y - n.height / 2 },
      data: n.data,
    };
  });

  const edges: Edge[] = rawEdges.map((e, idx) => {
    const sourceTask = agentTasks.find((t) => t.taskId === e.source);
    const isActive = sourceTask?.status === 'running';
    return {
      id: `e-${e.source}-${e.target}-${idx}`,
      source: e.source,
      target: e.target,
      animated: isActive,
      style: {
        stroke: isActive ? '#3b82f6' : '#9ca3af',
        strokeWidth: isActive ? 2 : 1.5,
      },
    };
  });

  return { nodes, edges };
}

/** 节点详情侧面板 */
function NodeDetailPanel({
  nodeData,
  onClose,
}: {
  nodeData: AgentDAGNodeData | null;
  onClose: () => void;
}) {
  if (!nodeData) return null;

  return (
    <div className="absolute right-0 top-0 bottom-0 w-72 bg-white dark:bg-gray-900 border-l border-gray-200 dark:border-gray-700 shadow-lg z-20 overflow-y-auto">
      <div className="flex items-center justify-between p-3 border-b border-gray-200 dark:border-gray-700">
        <span className="text-sm font-semibold text-gray-800 dark:text-gray-200">
          节点详情
        </span>
        <button
          onClick={onClose}
          className="p-1 rounded hover:bg-gray-100 dark:hover:bg-gray-800"
        >
          <X className="w-4 h-4 text-gray-500" />
        </button>
      </div>
      <div className="p-3 space-y-3">
        <div>
          <label className="text-[10px] uppercase font-semibold text-gray-500">Agent</label>
          <p className="text-sm font-medium text-gray-800 dark:text-gray-200">{nodeData.agentName}</p>
        </div>
        <div>
          <label className="text-[10px] uppercase font-semibold text-gray-500">类型</label>
          <p className="text-xs text-gray-600 dark:text-gray-400">{nodeData.agentType}</p>
        </div>
        <div>
          <label className="text-[10px] uppercase font-semibold text-gray-500">状态</label>
          <p className="text-xs text-gray-600 dark:text-gray-400">{nodeData.status}</p>
        </div>
        <div>
          <label className="text-[10px] uppercase font-semibold text-gray-500">描述</label>
          <p className="text-xs text-gray-600 dark:text-gray-400 whitespace-pre-wrap break-all">
            {nodeData.description}
          </p>
        </div>
        {nodeData.progress && (
          <div>
            <label className="text-[10px] uppercase font-semibold text-gray-500">进度</label>
            <p className="text-xs text-gray-600 dark:text-gray-400 whitespace-pre-wrap break-all max-h-40 overflow-y-auto">
              {nodeData.progress}
            </p>
          </div>
        )}
        {nodeData.result && (
          <div>
            <label className="text-[10px] uppercase font-semibold text-gray-500">结果</label>
            <p className="text-xs text-gray-600 dark:text-gray-400 whitespace-pre-wrap break-all max-h-40 overflow-y-auto">
              {nodeData.result}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}

/** 内部 DAG 组件（需要在 ReactFlowProvider 内部） */
function AgentDAGChartInner() {
  const [direction, setDirection] = useState<'TB' | 'LR'>('TB');
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [selectedNode, setSelectedNode] = useState<AgentDAGNodeData | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const { fitView } = useReactFlow();

  const agentTasks = useCoordinatorStore((s) => s.agentTasks);
  const activeWorkflow = useCoordinatorStore((s) => s.activeWorkflow);
  const swarms = useSwarmStore((s) => s.swarms);

  const currentPhaseIndex = activeWorkflow?.currentPhaseIndex ?? -1;

  const graphData = useMemo(
    () => buildGraph(agentTasks, swarms, currentPhaseIndex, direction),
    [agentTasks, swarms, currentPhaseIndex, direction]
  );

  const [nodes, setNodes, onNodesChange] = useNodesState(graphData.nodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(graphData.edges);

  // 当 graphData 变化时同步更新
  useEffect(() => {
    setNodes(graphData.nodes);
    setEdges(graphData.edges);
  }, [graphData, setNodes, setEdges]);

  // 布局变化后自动 fitView
  useEffect(() => {
    if (nodes.length > 0) {
      setTimeout(() => fitView({ padding: 0.2 }), 50);
    }
  }, [direction, nodes.length, fitView]);

  const handleNodeClick = useCallback((_: React.MouseEvent, node: Node) => {
    setSelectedNode(node.data as unknown as AgentDAGNodeData);
  }, []);

  const toggleFullscreen = useCallback(() => {
    if (!containerRef.current) return;
    if (!document.fullscreenElement) {
      containerRef.current.requestFullscreen().catch(() => {});
      setIsFullscreen(true);
    } else {
      document.exitFullscreen().catch(() => {});
      setIsFullscreen(false);
    }
  }, []);

  useEffect(() => {
    const handler = () => setIsFullscreen(!!document.fullscreenElement);
    document.addEventListener('fullscreenchange', handler);
    return () => document.removeEventListener('fullscreenchange', handler);
  }, []);

  // 空状态
  if (agentTasks.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-center p-6">
        <Network className="w-12 h-12 text-gray-300 dark:text-gray-600 mb-3" />
        <p className="text-sm text-gray-500 dark:text-gray-400 font-medium">
          暂无 Agent 任务
        </p>
        <p className="text-xs text-gray-400 dark:text-gray-500 mt-1">
          当 Agent 协作任务开始时，DAG 将自动显示
        </p>
      </div>
    );
  }

  return (
    <div ref={containerRef} className="relative w-full h-full bg-white dark:bg-gray-900">
      {/* 工具栏 */}
      <div className="absolute top-2 left-2 z-10 flex items-center gap-1 bg-white/90 dark:bg-gray-800/90 backdrop-blur-sm rounded-lg shadow-sm border border-gray-200 dark:border-gray-700 p-1">
        <button
          onClick={() => setDirection(direction === 'TB' ? 'LR' : 'TB')}
          className="p-1.5 rounded hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors"
          title={direction === 'TB' ? '切换为从左到右' : '切换为从上到下'}
        >
          {direction === 'TB' ? (
            <ArrowRightFromLine className="w-3.5 h-3.5 text-gray-600 dark:text-gray-400" />
          ) : (
            <ArrowDownFromLine className="w-3.5 h-3.5 text-gray-600 dark:text-gray-400" />
          )}
        </button>
        <button
          onClick={() => fitView({ padding: 0.2 })}
          className="p-1.5 rounded hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors"
          title="适应视图"
        >
          <Maximize2 className="w-3.5 h-3.5 text-gray-600 dark:text-gray-400" />
        </button>
        <button
          onClick={toggleFullscreen}
          className="p-1.5 rounded hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors"
          title={isFullscreen ? '退出全屏' : '全屏'}
        >
          {isFullscreen ? (
            <Minimize2 className="w-3.5 h-3.5 text-gray-600 dark:text-gray-400" />
          ) : (
            <Maximize2 className="w-3.5 h-3.5 text-gray-600 dark:text-gray-400" />
          )}
        </button>
      </div>

      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onNodeClick={handleNodeClick}
        nodeTypes={nodeTypes}
        fitView
        minZoom={0.2}
        maxZoom={2}
        proOptions={{ hideAttribution: true }}
      >
        <MiniMap
          nodeStrokeWidth={2}
          className="!bg-gray-50 dark:!bg-gray-800"
        />
        <Background variant={BackgroundVariant.Dots} gap={16} size={1} />
        <Controls
          showInteractive={false}
          className="!bg-white/90 dark:!bg-gray-800/90 !border-gray-200 dark:!border-gray-700 !shadow-sm"
        />
      </ReactFlow>

      {/* 节点详情面板 */}
      <NodeDetailPanel nodeData={selectedNode} onClose={() => setSelectedNode(null)} />
    </div>
  );
}

/** 对外导出的 DAG 组件（包裹 ReactFlowProvider） */
export function AgentDAGChart() {
  return (
    <div className="w-full h-full">
      <ReactFlowProvider>
        <AgentDAGChartInner />
      </ReactFlowProvider>
    </div>
  );
}
