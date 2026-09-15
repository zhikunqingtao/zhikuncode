/**
 * ChangeImpactGraph — 变更影响链路可视化组件
 * 使用 @xyflow/react + dagre 渲染代码变更的影响传播图
 */

import { useReducedMotion } from 'framer-motion';
import { useMemo, useState, useCallback, useEffect, memo } from 'react';
import {
  ReactFlow,
  MiniMap,
  Background,
  Controls,
  useNodesState,
  useEdgesState,
  BackgroundVariant,
  useReactFlow,
  ReactFlowProvider,
  Handle,
  Position,
  type Node,
  type Edge,
  type NodeProps,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import {
  Globe,
  Cog,
  Database,
  Clock,
  Settings,
  Code,
  Box,
  X,
  Loader2,
  AlertTriangle,
  RefreshCw,
  FileCode,
  Network,
  type LucideIcon,
} from 'lucide-react';
import { computeDAGLayout } from '@/utils/dag-layout';
import {
  useChangeImpactStore,
  type ChangeImpactNode,
  type ChangeImpactEdge,
  type ChangeImpactSummary,
} from '@/store/changeImpactStore';

// ── 节点类型配置 ──

const nodeTypeConfig: Record<string, { color: string; icon: LucideIcon; label: string }> = {
  api:        { color: '#3b82f6', icon: Globe,    label: 'API' },
  service:    { color: '#22c55e', icon: Cog,      label: 'Service' },
  repository: { color: '#a855f7', icon: Database, label: 'Repository' },
  scheduler:  { color: '#f97316', icon: Clock,    label: 'Scheduler' },
  config:     { color: '#6b7280', icon: Settings, label: 'Config' },
  function:   { color: '#06b6d4', icon: Code,     label: 'Function' },
  class:      { color: '#8b5cf6', icon: Box,      label: 'Class' },
};

const impactLevelStyles: Record<string, { border: string; shadow: string; dashArray?: string }> = {
  direct:    { border: 'var(--v2-err)', shadow: '0 2px 8px rgba(0,0,0,0.08)' },
  indirect:  { border: 'var(--v2-warn)', shadow: '0 2px 8px rgba(0,0,0,0.06)' },
  potential: { border: 'var(--v2-text-3)', shadow: 'none', dashArray: '4,4' },
};

const confidenceBadge: Record<string, { bg: string; text: string; label: string }> = {
  high:   { bg: 'bg-oksoft', text: 'text-ok', label: '高' },
  medium: { bg: 'bg-warnsoft', text: 'text-warn', label: '中' },
  low:    { bg: 'bg-sunken2', text: 'text-t2', label: '低' },
};

// ── 边样式 ──

function getEdgeStyle(type: string): React.CSSProperties {
  switch (type) {
    case 'call':       return { stroke: '#3b82f6', strokeWidth: 2 };
    case 'dependency': return { stroke: '#6b7280', strokeWidth: 1.5, strokeDasharray: '5,5' };
    case 'data-flow':  return { stroke: '#8b5cf6', strokeWidth: 1.5, strokeDasharray: '2,4' };
    default:           return { stroke: '#9ca3af', strokeWidth: 1 };
  }
}

// ── 数据转换 ──

interface ImpactNodeData {
  label: string;
  nodeType: string;
  impactLevel: string;
  confidence: string;
  filePath: string;
  lineRange: number[];
  language?: string;
  isSource?: boolean;
  [key: string]: unknown;
}

function convertToFlowNodes(impactNodes: ChangeImpactNode[], changedFile: string): Node[] {
  // 添加变更源节点
  const sourceNode: Node = {
    id: '__change_source__',
    type: 'impactNode',
    position: { x: 0, y: 0 },
    data: {
      label: changedFile.split('/').pop() || changedFile,
      nodeType: 'function',
      impactLevel: 'direct',
      confidence: 'high',
      filePath: changedFile,
      lineRange: [],
      isSource: true,
    } satisfies ImpactNodeData,
  };

  const nodes: Node[] = [sourceNode];

  impactNodes.forEach(node => {
    nodes.push({
      id: node.id,
      type: 'impactNode',
      position: { x: 0, y: 0 },
      data: {
        label: node.name,
        nodeType: node.type,
        impactLevel: node.impact_level,
        confidence: node.confidence,
        filePath: node.file_path,
        lineRange: node.line_range,
        language: node.language,
      } satisfies ImpactNodeData,
    });
  });

  return nodes;
}

function convertToFlowEdges(impactEdges: ChangeImpactEdge[], impactNodes: ChangeImpactNode[]): Edge[] {
  const edges: Edge[] = [];

  // 将变更源连接到所有 direct 节点
  const directNodes = impactNodes.filter(n => n.impact_level === 'direct');
  directNodes.forEach((node, i) => {
    edges.push({
      id: `e-src-${i}`,
      source: '__change_source__',
      target: node.id,
      type: 'smoothstep',
      animated: true,
      style: { stroke: 'var(--v2-err)', strokeWidth: 2 },
    });
  });

  // 后端返回的边
  impactEdges.forEach((edge, i) => {
    edges.push({
      id: `e-${i}`,
      source: edge.source,
      target: edge.target,
      type: 'smoothstep',
      animated: edge.type === 'call',
      style: getEdgeStyle(edge.type),
      label: edge.type,
      labelStyle: { fill: 'var(--v2-text-1)', fontSize: 13 },
      labelBgStyle: { fill: 'var(--v2-bg-surface)' },
    });
  });

  return edges;
}

function layoutElements(nodes: Node[], edges: Edge[]): { nodes: Node[]; edges: Edge[] } {
  if (nodes.length === 0) return { nodes, edges };

  const rawNodes = nodes.map(n => ({ id: n.id, width: 220, height: 110 }));
  const rawEdges = edges.map(e => ({ source: e.source, target: e.target }));

  const layout = computeDAGLayout(rawNodes, rawEdges, 'TB');
  const posMap = new Map(layout.nodes.map(n => [n.id, { x: n.x, y: n.y }]));

  const layoutedNodes = nodes.map(node => {
    const pos = posMap.get(node.id) || { x: 0, y: 0 };
    return { ...node, position: { x: pos.x - 110, y: pos.y - 55 } };
  });

  return { nodes: layoutedNodes, edges };
}

// ── 自定义节点组件 ──

function ImpactNodeComponent({ data }: NodeProps) {
  const d = data as unknown as ImpactNodeData;
  const config = nodeTypeConfig[d.nodeType] || nodeTypeConfig.function;
  const impact = impactLevelStyles[d.impactLevel] || impactLevelStyles.potential;
  const badge = confidenceBadge[d.confidence] || confidenceBadge.low;
  const Icon = config.icon;
  const isSource = d.isSource === true;

  const borderStyle = impact.dashArray
    ? `2px dashed ${impact.border}`
    : `2px solid ${impact.border}`;

  return (
    <div
      className="w-[220px] min-h-[100px] rounded-[14px] px-3 py-2.5 bg-surfacev2 transition-shadow"
      style={{
        border: isSource ? '2px solid var(--v2-err)' : borderStyle,
        boxShadow: isSource ? '0 2px 8px rgba(0,0,0,0.08)' : impact.shadow,
      }}
    >
      <Handle type="target" position={Position.Top} className="!bg-t3 !w-2 !h-2" />

      {/* Header: type icon + label */}
      <div className="flex items-center gap-1.5 mb-1">
        {isSource ? (
          <FileCode className="w-3.5 h-3.5 flex-shrink-0" style={{ color: 'var(--v2-err)' }} />
        ) : (
          <Icon className="w-3.5 h-3.5 flex-shrink-0" style={{ color: config.color }} />
        )}
        <span
          className="text-[13px] px-1.5 py-0.5 rounded font-medium"
          style={{
            backgroundColor: 'var(--v2-bg-sunken)',
            color: 'var(--v2-text-2)',
          }}
        >
          {isSource ? '变更源' : config.label}
        </span>
      </div>

      {/* Name */}
      <p className="text-sm font-semibold text-t1 truncate leading-tight mb-1">
        {d.label}
      </p>

      {/* Confidence badge */}
      {!isSource && (
        <span className={`text-[13px] px-1.5 py-0.5 rounded ${badge.bg} ${badge.text}`}>
          置信度：{badge.label}
        </span>
      )}

      <Handle type="source" position={Position.Bottom} className="!bg-t3 !w-2 !h-2" />
    </div>
  );
}

const ImpactNode = memo(ImpactNodeComponent);

const nodeTypes = { impactNode: ImpactNode };

// ── 摘要栏 ──

function SummaryBar({
  changedFile,
  changedLines,
  summary,
  elapsedMs,
}: {
  changedFile: string;
  changedLines: number[];
  summary: ChangeImpactSummary;
  elapsedMs: number | null;
}) {
  return (
    <div className="border-b border-border-hairline bg-surface2 px-4 py-2.5">
      <div className="flex flex-wrap items-center justify-between gap-2 mb-1.5">
        <div className="flex items-center gap-2 min-w-0">
          <FileCode className="w-4 h-4 text-err flex-shrink-0" />
          <span className="text-sm font-medium text-t1 truncate">
            {changedFile}
          </span>
          <span className="text-[13px] text-t2 flex-shrink-0">
            L{changedLines[0]}–{changedLines[changedLines.length - 1]}
          </span>
        </div>
        {elapsedMs != null && (
          <span className="text-[13px] text-t2 flex-shrink-0 font-mono">
            {elapsedMs}ms
          </span>
        )}
      </div>
      <div className="flex items-center gap-x-4 gap-y-2 flex-wrap text-[13px]">
        <span className="flex items-center gap-1">
          <span className="w-2 h-2 rounded-full bg-err inline-block" />
          <span className="text-t2">直接影响: {summary.direct_count}</span>
        </span>
        <span className="flex items-center gap-1">
          <span className="w-2 h-2 rounded-full bg-warn inline-block" />
          <span className="text-t2">间接影响: {summary.indirect_count}</span>
        </span>
        <span className="flex items-center gap-1">
          <span className="w-2 h-2 rounded-full bg-t3 inline-block" />
          <span className="text-t2">潜在风险: {summary.potential_count}</span>
        </span>
        {summary.affected_apis.length > 0 && (
          <span className="text-t2 truncate">
            API: {summary.affected_apis.join(', ')}
          </span>
        )}
      </div>
    </div>
  );
}

// ── 节点详情面板 ──

function NodeDetailPanel({
  node,
  onClose,
}: {
  node: ChangeImpactNode | null;
  onClose: () => void;
}) {
  if (!node) return null;

  const config = nodeTypeConfig[node.type] || nodeTypeConfig.function;
  const badge = confidenceBadge[node.confidence] || confidenceBadge.low;
  const Icon = config.icon;

  return (
    <div className="absolute right-0 top-0 bottom-0 w-72 max-w-full bg-surfacev2 border-l border-border-hairline shadow-lg z-20 overflow-y-auto">
      <div className="flex items-center justify-between p-3 border-b border-border-hairline">
        <span className="text-sm font-semibold text-t1">节点详情</span>
        <button
          aria-label="关闭影响节点详情"
          onClick={onClose}
          className="panel-control p-1 rounded hover:bg-hover2"
        >
          <X className="w-4 h-4 text-t2" />
        </button>
      </div>
      <div className="p-3 space-y-3 [overflow-wrap:anywhere]">
        <div>
          <label className="text-[13px] uppercase font-semibold text-t2">名称</label>
          <p className="text-sm font-medium text-t1">{node.name}</p>
        </div>
        <div>
          <label className="text-[13px] uppercase font-semibold text-t2">类型</label>
          <div className="flex items-center gap-1.5 mt-0.5">
            <Icon className="w-3.5 h-3.5" style={{ color: config.color }} />
            <span className="text-[13px] text-t2">{config.label}</span>
          </div>
        </div>
        <div>
          <label className="text-[13px] uppercase font-semibold text-t2">文件</label>
          <p className="text-[13px] text-t2 break-all">{node.file_path}</p>
        </div>
        {node.line_range.length >= 2 && (
          <div>
            <label className="text-[13px] uppercase font-semibold text-t2">行范围</label>
            <p className="text-[13px] text-t2 font-mono">
              L{node.line_range[0]}–{node.line_range[1]}
            </p>
          </div>
        )}
        <div>
          <label className="text-[13px] uppercase font-semibold text-t2">影响层级</label>
          <div className="flex items-center gap-1.5 mt-0.5">
            <span
              className="w-2 h-2 rounded-full inline-block"
              style={{ backgroundColor: impactLevelStyles[node.impact_level]?.border || '#9ca3af' }}
            />
            <span className="text-[13px] text-t2 capitalize">{node.impact_level}</span>
          </div>
        </div>
        <div>
          <label className="text-[13px] uppercase font-semibold text-t2">置信度</label>
          <span className={`text-[13px] px-1.5 py-0.5 rounded ${badge.bg} ${badge.text}`}>
            置信度：{badge.label}
          </span>
        </div>
        {node.language && (
          <div>
            <label className="text-[13px] uppercase font-semibold text-t2">语言</label>
            <p className="text-[13px] text-t2">{node.language}</p>
          </div>
        )}
      </div>
    </div>
  );
}

// ── 主图组件（需在 ReactFlowProvider 内） ──

function ChangeImpactGraphInner() {
  const reduceMotion = useReducedMotion();
  const { fitView } = useReactFlow();
  const impactData = useChangeImpactStore(s => s.impactData);
  const isLoading = useChangeImpactStore(s => s.isLoading);
  const error = useChangeImpactStore(s => s.error);
  const selectedNode = useChangeImpactStore(s => s.selectedNode);
  const setSelectedNode = useChangeImpactStore(s => s.setSelectedNode);
  const elapsedMs = useChangeImpactStore(s => s.elapsedMs);

  const [hoveredNodeId, setHoveredNodeId] = useState<string | null>(null);

  const graphData = useMemo(() => {
    if (!impactData) return { nodes: [], edges: [] };
    const flowNodes = convertToFlowNodes(impactData.impact_nodes, impactData.changed_file);
    const flowEdges = convertToFlowEdges(impactData.impact_edges, impactData.impact_nodes);
    return layoutElements(flowNodes, flowEdges);
  }, [impactData]);

  const [nodes, setNodes, onNodesChange] = useNodesState(graphData.nodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(graphData.edges);

  useEffect(() => {
    setNodes(graphData.nodes);
    setEdges(graphData.edges);
  }, [graphData, setNodes, setEdges]);

  useEffect(() => {
    if (nodes.length > 0) {
      const timer = setTimeout(() => fitView({ padding: 0.2 }), 50);
      return () => clearTimeout(timer);
    }
  }, [nodes.length, fitView]);

  // 悬停高亮
  const highlightedEdges = useMemo(() => {
    if (!hoveredNodeId) return edges;
    return edges.map(e => {
      const connected = e.source === hoveredNodeId || e.target === hoveredNodeId;
      return {
        ...e,
        style: {
          ...e.style,
          opacity: connected ? 1 : 0.2,
          strokeWidth: connected ? 3 : (e.style?.strokeWidth ?? 1.5),
        },
      };
    });
  }, [edges, hoveredNodeId]);

  const displayEdges = useMemo(() => {
    const visible = hoveredNodeId ? highlightedEdges : edges;
    return reduceMotion ? visible.map(edge => ({ ...edge, animated: false })) : visible;
  }, [hoveredNodeId, highlightedEdges, edges, reduceMotion]);

  const highlightedNodes = useMemo(() => {
    if (!hoveredNodeId) return nodes;
    const connectedIds = new Set<string>([hoveredNodeId]);
    edges.forEach(e => {
      if (e.source === hoveredNodeId) connectedIds.add(e.target);
      if (e.target === hoveredNodeId) connectedIds.add(e.source);
    });
    return nodes.map(n => ({
      ...n,
      style: { ...n.style, opacity: connectedIds.has(n.id) ? 1 : 0.3 },
    }));
  }, [nodes, edges, hoveredNodeId]);

  const handleNodeClick = useCallback((_: React.MouseEvent, node: Node) => {
    if (node.id === '__change_source__') return;
    const impactNode = impactData?.impact_nodes.find(n => n.id === node.id) ?? null;
    setSelectedNode(impactNode);
  }, [impactData, setSelectedNode]);

  const handleNodeMouseEnter = useCallback((_: React.MouseEvent, node: Node) => {
    setHoveredNodeId(node.id);
  }, []);

  const handleNodeMouseLeave = useCallback(() => {
    setHoveredNodeId(null);
  }, []);

  // 加载状态
  if (isLoading) {
    return (
      <div className="flex flex-col items-center justify-center h-full">
        <Loader2 className="w-8 h-8 text-accent2-ink animate-spin mb-3" />
        <p className="text-sm text-t2">正在分析变更影响...</p>
      </div>
    );
  }

  // 错误状态
  if (error) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-center p-6">
        <AlertTriangle className="w-10 h-10 text-err mb-3" />
        <p className="text-sm text-t1 mb-1">分析失败</p>
        <p className="text-[13px] text-t2 mb-3">{error}</p>
        <button
          onClick={() => useChangeImpactStore.getState().reset()}
          className="panel-control flex items-center gap-1.5 px-3 py-1.5 text-[13px] rounded-md bg-accent2-soft text-accent2-ink hover:bg-hover2 transition-colors"
        >
          <RefreshCw className="w-3.5 h-3.5" />
          清除错误
        </button>
      </div>
    );
  }

  // 空数据状态
  if (!impactData) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-center p-6">
        <Network className="w-12 h-12 text-t3 mb-3" />
        <p className="text-sm text-t2 font-medium">暂无影响分析数据</p>
        <p className="text-[13px] text-t2 mt-1">
          选择文件变更后，影响链路将自动显示
        </p>
      </div>
    );
  }

  // 无影响节点
  if (impactData.impact_nodes.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-center p-6">
        <Network className="w-12 h-12 text-ok mb-3" />
        <p className="text-sm text-t2 font-medium">无影响</p>
        <p className="text-[13px] text-t2 mt-1">
          该变更未检测到受影响的依赖方
        </p>
      </div>
    );
  }

  return (
    <div className="impact-graph flex flex-col w-full h-full bg-surfacev2">
      <SummaryBar
        changedFile={impactData.changed_file}
        changedLines={impactData.changed_lines}
        summary={impactData.summary}
        elapsedMs={elapsedMs}
      />

      <div className="relative flex-1 min-h-0">
        <ReactFlow
          nodes={hoveredNodeId ? highlightedNodes : nodes}
          edges={displayEdges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onNodeClick={handleNodeClick}
          onNodeMouseEnter={handleNodeMouseEnter}
          onNodeMouseLeave={handleNodeMouseLeave}
          nodeTypes={nodeTypes}
          fitView
          minZoom={0.2}
          maxZoom={2}
          proOptions={{ hideAttribution: true }}
        >
          <MiniMap
            nodeStrokeWidth={2}
            className="!bg-surface2"
          />
          <Background variant={BackgroundVariant.Dots} gap={16} size={1} />
          <Controls
            showInteractive={false}
            className="!bg-surfacev2 !border-border-hairline !shadow-sm"
          />
        </ReactFlow>

        <NodeDetailPanel node={selectedNode} onClose={() => setSelectedNode(null)} />
      </div>

    </div>
  );
}

/** 对外导出的变更影响图组件（包裹 ReactFlowProvider） */
export function ChangeImpactGraph() {
  return (
    <div className="w-full h-full">
      <ReactFlowProvider>
        <ChangeImpactGraphInner />
      </ReactFlowProvider>
    </div>
  );
}
