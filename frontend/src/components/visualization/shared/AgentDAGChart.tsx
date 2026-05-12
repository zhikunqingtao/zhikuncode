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
import { useStompSubscription } from '@/hooks/useStompSubscription';
import { computeDAGLayout } from '@/utils/dag-layout';
import { AgentDAGNode, type AgentDAGNodeData } from './AgentDAGNode';
import type { AgentTask, SwarmInfo, WorkerInfo, CoordinatorEventEnvelope } from '@/types';
import type { CollaborationEdge, MailboxWriteEvent } from '@/types/apos';

const nodeTypes = { agentNode: AgentDAGNode };

/** 边样式映射 */
const EDGE_STYLES: Record<CollaborationEdge['type'], { stroke: string; strokeDasharray?: string }> = {
  explicit_dependency: { stroke: '#3B82F6' },
  mailbox_communication: { stroke: '#10B981' },
  time_inferred: { stroke: '#9CA3AF', strokeDasharray: '5,5' },
};

/** 简单网格布局（dagre 失败时的回退方案） */
function fallbackGridLayout(
  rawNodes: Array<{ id: string; width: number; height: number }>
): Map<string, { x: number; y: number }> {
  const cols = Math.ceil(Math.sqrt(rawNodes.length));
  const posMap = new Map<string, { x: number; y: number }>();
  rawNodes.forEach((n, i) => {
    const col = i % cols;
    const row = Math.floor(i / cols);
    posMap.set(n.id, { x: col * 280 + 140, y: row * 140 + 70 });
  });
  return posMap;
}

/** 协作边中间结构 */
interface RawCollaborationEdge {
  source: string;
  target: string;
  type: CollaborationEdge['type'];
  dataSize?: number;
  contentType?: string;
}

/** 将 AgentTask + Swarm Workers + MailboxEvents 转换为 React Flow 的 nodes/edges */
function buildGraph(
  agentTasks: AgentTask[],
  swarms: Map<string, SwarmInfo>,
  _activeWorkflowPhaseIndex: number,
  direction: 'TB' | 'LR',
  mailboxEvents: MailboxWriteEvent[] = []
): { nodes: Node[]; edges: Edge[] } {
  if (agentTasks.length === 0) return { nodes: [], edges: [] };

  const rawNodes: Array<{ id: string; width: number; height: number; data: AgentDAGNodeData }> = [];
  const collabEdges: RawCollaborationEdge[] = [];

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
        collabEdges.push({ source: parentTask.taskId, target: workerId, type: 'explicit_dependency' });
      }
    });
  });

  // 记录已有边连接的节点对（用于策略 3 判断）
  const connectedPairs = new Set<string>();

  // ═══ 策略 1：显式依赖边（explicit_dependency）═══
  // 从 agentTasks 中读取 parentTaskId，有 parentTaskId 的任务创建 parent → child 边
  const taskIdSet = new Set(sortedTasks.map((t) => t.taskId));
  sortedTasks.forEach((task) => {
    if (task.parentTaskId && taskIdSet.has(task.parentTaskId)) {
      const pairKey = `${task.parentTaskId}->${task.taskId}`;
      collabEdges.push({ source: task.parentTaskId, target: task.taskId, type: 'explicit_dependency' });
      connectedPairs.add(pairKey);
    }
    // 也处理 dependencies 数组
    if (task.dependencies) {
      task.dependencies.forEach((depId) => {
        if (taskIdSet.has(depId)) {
          const pairKey = `${depId}->${task.taskId}`;
          if (!connectedPairs.has(pairKey)) {
            collabEdges.push({ source: depId, target: task.taskId, type: 'explicit_dependency' });
            connectedPairs.add(pairKey);
          }
        }
      });
    }
  });

  // ═══ 策略 2：Mailbox 通信边（mailbox_communication）═══
  // 根据 agentName 匹配 taskId
  const agentNameToTaskId = new Map<string, string>();
  sortedTasks.forEach((task) => {
    agentNameToTaskId.set(task.agentName, task.taskId);
  });

  mailboxEvents.forEach((evt) => {
    const sourceId = agentNameToTaskId.get(evt.from);
    const targetId = agentNameToTaskId.get(evt.to);
    if (sourceId && targetId && sourceId !== targetId) {
      const pairKey = `${sourceId}->${targetId}`;
      if (!connectedPairs.has(pairKey)) {
        collabEdges.push({
          source: sourceId,
          target: targetId,
          type: 'mailbox_communication',
          dataSize: evt.messageSize,
          contentType: evt.contentType,
        });
        connectedPairs.add(pairKey);
      }
    }
  });

  // ═══ 策略 3：时间窗口推断边（time_inferred）═══
  // 仅在策略 1 和 2 都无边的节点之间使用
  // 基于 phase 间的顺序关系推断
  for (let i = 1; i < phases.length; i++) {
    const prevPhase = phases[i - 1];
    const currPhase = phases[i];
    prevPhase.forEach((prevTask) => {
      currPhase.forEach((currTask) => {
        const pairKey = `${prevTask.taskId}->${currTask.taskId}`;
        if (!connectedPairs.has(pairKey)) {
          collabEdges.push({ source: prevTask.taskId, target: currTask.taskId, type: 'time_inferred' });
          connectedPairs.add(pairKey);
        }
      });
    });
  }

  // 使用 dagre 计算布局（带容错回退）
  const rawEdgesForLayout = collabEdges.map((e) => ({ source: e.source, target: e.target }));
  let posMap: Map<string, { x: number; y: number }>;

  try {
    const layout = computeDAGLayout(
      rawNodes.map((n) => ({ id: n.id, width: n.width, height: n.height })),
      rawEdgesForLayout,
      direction
    );
    posMap = new Map(layout.nodes.map((n) => [n.id, { x: n.x, y: n.y }]));
  } catch {
    // dagre 布局失败时回退为简单网格布局
    posMap = fallbackGridLayout(rawNodes);
  }

  // 节点 > 20 时自动折叠为摘要视图（仅保留前 20 个节点）
  const isSummaryView = rawNodes.length > 20;
  const displayNodes = isSummaryView ? rawNodes.slice(0, 20) : rawNodes;
  const displayNodeIds = new Set(displayNodes.map((n) => n.id));

  const nodes: Node[] = displayNodes.map((n) => {
    const pos = posMap.get(n.id) || { x: 0, y: 0 };
    return {
      id: n.id,
      type: 'agentNode',
      position: { x: pos.x - n.width / 2, y: pos.y - n.height / 2 },
      data: n.data,
    };
  });

  // 如果是摘要视图，添加一个提示节点
  if (isSummaryView) {
    const lastPos = posMap.get(displayNodes[displayNodes.length - 1]?.id) || { x: 0, y: 0 };
    nodes.push({
      id: '__summary_indicator__',
      type: 'agentNode',
      position: { x: lastPos.x + 100, y: lastPos.y + 120 },
      data: {
        agentName: `+${rawNodes.length - 20} more`,
        agentType: 'summary',
        description: `共 ${rawNodes.length} 个节点，已折叠显示`,
        status: 'pending',
      } as AgentDAGNodeData,
    });
  }

  const edges: Edge[] = collabEdges
    .filter((e) => displayNodeIds.has(e.source) && displayNodeIds.has(e.target))
    .map((e, idx) => {
      const style = EDGE_STYLES[e.type];
      const sourceTask = agentTasks.find((t) => t.taskId === e.source);
      const isActive = sourceTask?.status === 'running';
      return {
        id: `e-${e.source}-${e.target}-${e.type}-${idx}`,
        source: e.source,
        target: e.target,
        animated: isActive && e.type !== 'time_inferred',
        style: {
          stroke: style.stroke,
          strokeWidth: e.type === 'time_inferred' ? 1 : 1.5,
          strokeDasharray: style.strokeDasharray,
        },
        label: e.type === 'mailbox_communication' && e.dataSize
          ? `${e.dataSize}B`
          : undefined,
        markerEnd: { type: 'arrowclosed' as const, color: style.stroke },
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
function AgentDAGChartInner({ liveSessionId }: { liveSessionId?: string }) {
  const [direction, setDirection] = useState<'TB' | 'LR'>('TB');
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [selectedNode, setSelectedNode] = useState<AgentDAGNodeData | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const { fitView } = useReactFlow();

  const agentTasks = useCoordinatorStore((s) => s.agentTasks);
  const activeWorkflow = useCoordinatorStore((s) => s.activeWorkflow);
  const appendCoordinatorEvent = useCoordinatorStore((s) => s.appendCoordinatorEvent);
  const mailboxEvents = useCoordinatorStore((s) => s.mailboxEvents);
  const swarms = useSwarmStore((s) => s.swarms);

  // 方案 B：订阅后端 CoordinatorEventBus 推送的实时事件
  // 仅在 liveSessionId 存在时建立订阅，组件卸载时自动清理。
  const coordinatorTopic = liveSessionId
    ? `/user/queue/coordinator/${liveSessionId}`
    : null;
  useStompSubscription(coordinatorTopic, (msg) => {
    try {
      const envelope = JSON.parse(msg.body) as CoordinatorEventEnvelope;
      if (envelope && envelope.type === 'coordinator_event') {
        appendCoordinatorEvent(envelope);
      }
    } catch (err) {
      // 解析异常静默降级 — 后端 safeSend 端已有兜底，前端此处仅 debug
      console.debug('[AgentDAGChart] coordinator event parse failed:', err);
    }
  });

  const currentPhaseIndex = activeWorkflow?.currentPhaseIndex ?? -1;

  const graphData = useMemo(
    () => buildGraph(agentTasks, swarms, currentPhaseIndex, direction, mailboxEvents),
    [agentTasks, swarms, currentPhaseIndex, direction, mailboxEvents]
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
export interface AgentDAGChartProps {
  /**
   * 方案 B：可选会话 ID — 存在时订阅 /user/queue/coordinator/{sessionId}
   * 接收后端 CoordinatorEventBus 实时事件流。
   */
  liveSessionId?: string;
}

export function AgentDAGChart({ liveSessionId }: AgentDAGChartProps = {}) {
  return (
    <div className="w-full h-full">
      <ReactFlowProvider>
        <AgentDAGChartInner liveSessionId={liveSessionId} />
      </ReactFlowProvider>
    </div>
  );
}
