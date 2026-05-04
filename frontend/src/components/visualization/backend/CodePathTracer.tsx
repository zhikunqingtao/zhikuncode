/**
 * CodePathTracer — 代码路径追踪可视化组件
 * F40: 从 API 端点出发，追踪代码调用路径并以分层流图展示
 * 使用 @xyflow/react + dagre 渲染分层调用链路
 */

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
  Zap,
  Box,
  X,
  Loader2,
  AlertTriangle,
  Search,
  Play,
  Network,
  type LucideIcon,
} from 'lucide-react';
import { computeDAGLayout } from '@/utils/dag-layout';
import {
  useCodePathStore,
  type PathNode,
  type PathEdge,
  type ApiEndpointItem,
} from '@/store/codePathStore';

// ── 层级颜色 & 图标配置 ──

const LAYER_CONFIG: Record<string, { color: string; icon: LucideIcon; label: string }> = {
  controller: { color: '#3b82f6', icon: Globe,    label: 'Controller' },
  service:    { color: '#22c55e', icon: Cog,      label: 'Service' },
  repository: { color: '#a855f7', icon: Database, label: 'Repository' },
  database:   { color: '#f97316', icon: Database, label: 'Database' },
  external:   { color: '#ef4444', icon: Zap,      label: 'External' },
  utility:    { color: '#6b7280', icon: Box,      label: 'Utility' },
};

const METHOD_COLORS: Record<string, string> = {
  GET: '#22c55e',
  POST: '#3b82f6',
  PUT: '#f59e0b',
  DELETE: '#ef4444',
  PATCH: '#a855f7',
};

// ── 数据转换 ──

interface LayerNodeData {
  label: string;
  layer: string;
  className: string;
  filePath: string;
  lineRange: number[];
  annotations: string[];
  parameters: Array<{ name: string; type: string; annotation?: string }>;
  returnType: string;
  nodeType: string;
  [key: string]: unknown;
}

function convertToFlowElements(
  pathNodes: PathNode[],
  pathEdges: PathEdge[]
): { nodes: Node[]; edges: Edge[] } {
  const flowNodes: Node[] = pathNodes.map(n => ({
    id: n.id,
    type: 'layerNode',
    position: { x: 0, y: 0 },
    data: {
      label: n.name,
      layer: n.layer,
      className: n.className,
      filePath: n.filePath,
      lineRange: n.lineRange,
      annotations: n.annotations,
      parameters: n.parameters,
      returnType: n.returnType,
      nodeType: n.nodeType,
    } satisfies LayerNodeData,
  }));

  const flowEdges: Edge[] = pathEdges.map((e, i) => {
    const label = e.parameterMapping
      ? Object.entries(e.parameterMapping).map(([k, v]) => `${k}→${v}`).join(', ')
      : e.callType;
    return {
      id: `e-${i}-${e.source}-${e.target}`,
      source: e.source,
      target: e.target,
      type: 'smoothstep',
      animated: false,
      label,
      style: { stroke: '#6b7280', strokeWidth: 1.5 },
    };
  });

  return { nodes: flowNodes, edges: flowEdges };
}

function layoutElements(nodes: Node[], edges: Edge[]): { nodes: Node[]; edges: Edge[] } {
  if (nodes.length === 0) return { nodes, edges };

  const rawNodes = nodes.map(n => ({ id: n.id, width: 220, height: 90 }));
  const rawEdges = edges.map(e => ({ source: e.source, target: e.target }));

  const layout = computeDAGLayout(rawNodes, rawEdges, 'TB');
  const posMap = new Map(layout.nodes.map(n => [n.id, { x: n.x, y: n.y }]));

  const layoutedNodes = nodes.map(node => {
    const pos = posMap.get(node.id) || { x: 0, y: 0 };
    return { ...node, position: { x: pos.x - 110, y: pos.y - 45 } };
  });

  return { nodes: layoutedNodes, edges };
}

// ── 自定义 LayerNode 组件 ──

function LayerNodeComponent({ data }: NodeProps) {
  const d = data as unknown as LayerNodeData;
  const config = LAYER_CONFIG[d.layer] || LAYER_CONFIG.utility;
  const Icon = config.icon;

  return (
    <div
      className="w-[220px] min-h-[80px] rounded-lg px-3 py-2.5 bg-white dark:bg-gray-900 transition-shadow"
      style={{
        border: `2px solid ${config.color}`,
        boxShadow: `0 0 6px ${config.color}20`,
      }}
    >
      <Handle type="target" position={Position.Top} className="!bg-gray-400 !w-2 !h-2" />

      {/* Header: layer badge */}
      <div className="flex items-center gap-1.5 mb-1">
        <Icon className="w-3.5 h-3.5 flex-shrink-0" style={{ color: config.color }} />
        <span
          className="text-[10px] px-1.5 py-0.5 rounded font-medium"
          style={{ backgroundColor: `${config.color}15`, color: config.color }}
        >
          {config.label}
        </span>
      </div>

      {/* Name */}
      <p className="text-sm font-semibold text-gray-800 dark:text-gray-200 truncate leading-tight mb-0.5">
        {d.label}
      </p>

      {/* Class name */}
      {d.className && (
        <p className="text-[10px] text-gray-500 dark:text-gray-400 truncate">
          {d.className}
        </p>
      )}

      <Handle type="source" position={Position.Bottom} className="!bg-gray-400 !w-2 !h-2" />
    </div>
  );
}

const LayerNode = memo(LayerNodeComponent);
const nodeTypes = { layerNode: LayerNode };

// ── 端点列表面板 ──

function EndpointListPanel({
  endpoints,
  loading,
  searchText,
  onSearchChange,
  onEndpointClick,
  selectedEndpoint,
}: {
  endpoints: ApiEndpointItem[];
  loading: boolean;
  searchText: string;
  onSearchChange: (text: string) => void;
  onEndpointClick: (ep: ApiEndpointItem) => void;
  selectedEndpoint: ApiEndpointItem | null;
}) {
  const filtered = useMemo(() => {
    if (!searchText.trim()) return endpoints;
    const q = searchText.toLowerCase();
    return endpoints.filter(
      ep =>
        ep.path.toLowerCase().includes(q) ||
        ep.handlerFunction.toLowerCase().includes(q) ||
        ep.httpMethod.toLowerCase().includes(q)
    );
  }, [endpoints, searchText]);

  const grouped = useMemo(() => {
    const map = new Map<string, ApiEndpointItem[]>();
    for (const ep of filtered) {
      const method = ep.httpMethod.toUpperCase();
      if (!map.has(method)) map.set(method, []);
      map.get(method)!.push(ep);
    }
    return map;
  }, [filtered]);

  return (
    <div className="flex flex-col h-full border-r border-[var(--border)] bg-[var(--bg-secondary)]" style={{ width: 260, minWidth: 260 }}>
      {/* Search */}
      <div className="p-2 border-b border-[var(--border)]">
        <div className="relative">
          <Search className="absolute left-2 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-[var(--text-muted)]" />
          <input
            type="text"
            value={searchText}
            onChange={e => onSearchChange(e.target.value)}
            placeholder="搜索端点..."
            className="w-full pl-7 pr-2 py-1.5 text-xs rounded border border-[var(--border)]
              bg-[var(--bg-primary)] text-[var(--text-primary)]
              placeholder:text-[var(--text-muted)] focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
        </div>
      </div>

      {/* List */}
      <div className="flex-1 overflow-y-auto p-1">
        {loading ? (
          <div className="flex items-center justify-center py-8">
            <Loader2 className="w-5 h-5 animate-spin text-blue-500" />
          </div>
        ) : filtered.length === 0 ? (
          <div className="py-8 text-center text-xs text-[var(--text-muted)]">
            {endpoints.length === 0 ? '点击扫描加载端点' : '无匹配端点'}
          </div>
        ) : (
          Array.from(grouped.entries()).map(([method, eps]) => (
            <div key={method} className="mb-2">
              <div className="px-2 py-1 text-[10px] font-semibold uppercase tracking-wider"
                style={{ color: METHOD_COLORS[method] || '#6b7280' }}>
                {method} ({eps.length})
              </div>
              {eps.map((ep, i) => {
                const isSelected =
                  selectedEndpoint?.path === ep.path &&
                  selectedEndpoint?.httpMethod === ep.httpMethod;
                return (
                  <button
                    key={`${method}-${i}`}
                    onClick={() => onEndpointClick(ep)}
                    className={`w-full text-left px-2 py-1.5 rounded text-xs transition-colors
                      ${isSelected
                        ? 'bg-blue-500/10 border border-blue-500/30'
                        : 'hover:bg-[var(--bg-hover)] border border-transparent'}`}
                  >
                    <span className="font-mono text-[var(--text-primary)] truncate block">
                      {ep.path}
                    </span>
                    <span className="text-[10px] text-[var(--text-muted)] truncate block">
                      {ep.handlerClass}.{ep.handlerFunction}
                    </span>
                  </button>
                );
              })}
            </div>
          ))
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
  node: PathNode | null;
  onClose: () => void;
}) {
  if (!node) return null;
  const config = LAYER_CONFIG[node.layer] || LAYER_CONFIG.utility;
  const Icon = config.icon;

  return (
    <div className="absolute right-0 top-0 bottom-0 w-72 bg-white dark:bg-gray-900 border-l border-gray-200 dark:border-gray-700 shadow-lg z-20 overflow-y-auto">
      <div className="flex items-center justify-between p-3 border-b border-gray-200 dark:border-gray-700">
        <span className="text-sm font-semibold text-gray-800 dark:text-gray-200">节点详情</span>
        <button onClick={onClose} className="p-1 rounded hover:bg-gray-100 dark:hover:bg-gray-800">
          <X className="w-4 h-4 text-gray-500" />
        </button>
      </div>
      <div className="p-3 space-y-3">
        <div>
          <label className="text-[10px] uppercase font-semibold text-gray-500">方法名</label>
          <p className="text-sm font-medium text-gray-800 dark:text-gray-200">{node.name}</p>
        </div>
        <div>
          <label className="text-[10px] uppercase font-semibold text-gray-500">类名</label>
          <p className="text-xs text-gray-600 dark:text-gray-400">{node.className}</p>
        </div>
        <div>
          <label className="text-[10px] uppercase font-semibold text-gray-500">层级</label>
          <div className="flex items-center gap-1.5 mt-0.5">
            <Icon className="w-3.5 h-3.5" style={{ color: config.color }} />
            <span className="text-xs text-gray-600 dark:text-gray-400">{config.label}</span>
          </div>
        </div>
        <div>
          <label className="text-[10px] uppercase font-semibold text-gray-500">返回类型</label>
          <p className="text-xs text-gray-600 dark:text-gray-400 font-mono">{node.returnType}</p>
        </div>
        {node.parameters.length > 0 && (
          <div>
            <label className="text-[10px] uppercase font-semibold text-gray-500">参数</label>
            <div className="mt-1 space-y-1">
              {node.parameters.map((p, i) => (
                <div key={i} className="text-xs text-gray-600 dark:text-gray-400 font-mono">
                  {p.name}: {p.type}
                  {p.annotation && <span className="text-blue-500 ml-1">@{p.annotation}</span>}
                </div>
              ))}
            </div>
          </div>
        )}
        {node.annotations.length > 0 && (
          <div>
            <label className="text-[10px] uppercase font-semibold text-gray-500">注解</label>
            <div className="mt-1 flex flex-wrap gap-1">
              {node.annotations.map((a, i) => (
                <span key={i} className="text-[10px] px-1.5 py-0.5 rounded bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400">
                  @{a}
                </span>
              ))}
            </div>
          </div>
        )}
        <div>
          <label className="text-[10px] uppercase font-semibold text-gray-500">文件</label>
          <p className="text-xs text-gray-600 dark:text-gray-400 break-all">{node.filePath}</p>
        </div>
        {node.lineRange.length >= 2 && (
          <div>
            <label className="text-[10px] uppercase font-semibold text-gray-500">行范围</label>
            <p className="text-xs text-gray-600 dark:text-gray-400 font-mono">
              L{node.lineRange[0]}–{node.lineRange[1]}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}

// ── 底部层级统计栏 ──

function LayerStatsBar({ layers }: { layers: Array<{ layer: string; nodeCount: number; description: string }> }) {
  if (layers.length === 0) return null;
  return (
    <div className="border-t border-[var(--border)] bg-[var(--bg-secondary)] px-3 py-2 flex items-center gap-4 text-xs flex-shrink-0">
      {layers.map(l => {
        const config = LAYER_CONFIG[l.layer] || LAYER_CONFIG.utility;
        return (
          <span key={l.layer} className="flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full inline-block" style={{ backgroundColor: config.color }} />
            <span className="text-[var(--text-secondary)]">{config.label}: {l.nodeCount}</span>
          </span>
        );
      })}
    </div>
  );
}

// ── 主图组件（需在 ReactFlowProvider 内） ──

function CodePathTracerInner() {
  const { fitView } = useReactFlow();
  const pathResult = useCodePathStore(s => s.pathResult);
  const loading = useCodePathStore(s => s.loading);
  const endpointsLoading = useCodePathStore(s => s.endpointsLoading);
  const error = useCodePathStore(s => s.error);
  const endpoints = useCodePathStore(s => s.endpoints);
  const selectedEndpoint = useCodePathStore(s => s.selectedEndpoint);
  const selectedNode = useCodePathStore(s => s.selectedNode);
  const projectRoot = useCodePathStore(s => s.projectRoot);
  const setProjectRoot = useCodePathStore(s => s.setProjectRoot);
  const fetchEndpoints = useCodePathStore(s => s.fetchEndpoints);
  const traceCodePath = useCodePathStore(s => s.traceCodePath);
  const setSelectedEndpoint = useCodePathStore(s => s.setSelectedEndpoint);
  const setSelectedNode = useCodePathStore(s => s.setSelectedNode);

  const [searchText, setSearchText] = useState('');
  const [hoveredNodeId, setHoveredNodeId] = useState<string | null>(null);

  // 布局计算
  const graphData = useMemo(() => {
    if (!pathResult) return { nodes: [], edges: [] };
    const { nodes, edges } = convertToFlowElements(pathResult.nodes, pathResult.edges);
    return layoutElements(nodes, edges);
  }, [pathResult]);

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

  // 悬停高亮：从该节点出发的完整路径
  const highlightedEdges = useMemo(() => {
    if (!hoveredNodeId) return edges;
    // BFS 找出从 hoveredNodeId 出发可达的所有节点
    const reachable = new Set<string>([hoveredNodeId]);
    const queue = [hoveredNodeId];
    while (queue.length > 0) {
      const cur = queue.shift()!;
      for (const e of edges) {
        if (e.source === cur && !reachable.has(e.target)) {
          reachable.add(e.target);
          queue.push(e.target);
        }
      }
    }
    return edges.map(e => {
      const connected = reachable.has(e.source) && reachable.has(e.target);
      return {
        ...e,
        style: {
          ...e.style,
          opacity: connected ? 1 : 0.15,
          strokeWidth: connected ? 2.5 : (e.style?.strokeWidth ?? 1.5),
        },
        animated: connected,
      };
    });
  }, [edges, hoveredNodeId]);

  const highlightedNodes = useMemo(() => {
    if (!hoveredNodeId) return nodes;
    const reachable = new Set<string>([hoveredNodeId]);
    const queue = [hoveredNodeId];
    while (queue.length > 0) {
      const cur = queue.shift()!;
      for (const e of edges) {
        if (e.source === cur && !reachable.has(e.target)) {
          reachable.add(e.target);
          queue.push(e.target);
        }
      }
    }
    return nodes.map(n => ({
      ...n,
      style: { ...n.style, opacity: reachable.has(n.id) ? 1 : 0.2 },
    }));
  }, [nodes, edges, hoveredNodeId]);

  const handleNodeClick = useCallback((_: React.MouseEvent, node: Node) => {
    const pathNode = pathResult?.nodes.find(n => n.id === node.id) ?? null;
    setSelectedNode(pathNode);
  }, [pathResult, setSelectedNode]);

  const handleNodeMouseEnter = useCallback((_: React.MouseEvent, node: Node) => {
    setHoveredNodeId(node.id);
  }, []);

  const handleNodeMouseLeave = useCallback(() => {
    setHoveredNodeId(null);
  }, []);

  const handleEndpointClick = useCallback((ep: ApiEndpointItem) => {
    setSelectedEndpoint(ep);
    traceCodePath(ep.filePath, ep.handlerFunction);
  }, [setSelectedEndpoint, traceCodePath]);

  const handleScan = useCallback(() => {
    fetchEndpoints();
  }, [fetchEndpoints]);

  return (
    <div className="flex flex-col w-full h-full bg-[var(--bg-primary)]">
      {/* 顶部：项目路径 + 扫描 */}
      <div className="flex items-center gap-2 px-3 py-2 border-b border-[var(--border)] flex-shrink-0">
        <label className="text-[10px] uppercase tracking-wider text-[var(--text-muted)] flex-shrink-0">项目路径</label>
        <input
          type="text"
          value={projectRoot}
          onChange={e => setProjectRoot(e.target.value)}
          placeholder="."
          className="flex-1 px-2 py-1 text-xs rounded border border-[var(--border)]
            bg-[var(--bg-primary)] text-[var(--text-primary)]
            placeholder:text-[var(--text-muted)] focus:outline-none focus:ring-1 focus:ring-blue-500"
        />
        <button
          onClick={handleScan}
          disabled={endpointsLoading}
          className="flex items-center gap-1 px-3 py-1 rounded text-xs font-medium
            bg-blue-500 text-white hover:bg-blue-600
            disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          {endpointsLoading ? <Loader2 size={12} className="animate-spin" /> : <Play size={12} />}
          扫描
        </button>
      </div>

      {/* 错误提示 */}
      {error && (
        <div className="mx-3 mt-2 px-3 py-2 rounded border border-red-500/30 bg-red-500/5 text-xs text-red-500 flex items-center gap-2 flex-shrink-0">
          <AlertTriangle size={13} />
          {error}
        </div>
      )}

      {/* 主内容：左端点列表 + 右流图 */}
      <div className="flex flex-1 min-h-0">
        {/* 左侧端点列表 */}
        <EndpointListPanel
          endpoints={endpoints}
          loading={endpointsLoading}
          searchText={searchText}
          onSearchChange={setSearchText}
          onEndpointClick={handleEndpointClick}
          selectedEndpoint={selectedEndpoint}
        />

        {/* 右侧流图区域 */}
        <div className="flex-1 flex flex-col min-w-0">
          {loading ? (
            <div className="flex-1 flex flex-col items-center justify-center">
              <Loader2 className="w-8 h-8 text-blue-500 animate-spin mb-3" />
              <p className="text-sm text-[var(--text-muted)]">正在追踪代码路径...</p>
            </div>
          ) : !pathResult ? (
            <div className="flex-1 flex flex-col items-center justify-center text-center p-6">
              <Network className="w-12 h-12 text-gray-300 dark:text-gray-600 mb-3" />
              <p className="text-sm text-[var(--text-muted)] font-medium">暂无路径数据</p>
              <p className="text-xs text-[var(--text-muted)] mt-1 opacity-60">
                扫描端点后，点击任意端点开始追踪
              </p>
            </div>
          ) : pathResult.nodes.length === 0 ? (
            <div className="flex-1 flex flex-col items-center justify-center text-center p-6">
              <Network className="w-12 h-12 text-green-300 dark:text-green-600 mb-3" />
              <p className="text-sm text-[var(--text-muted)] font-medium">未发现调用路径</p>
              <p className="text-xs text-[var(--text-muted)] mt-1 opacity-60">
                该端点未检测到下游调用链路
              </p>
            </div>
          ) : (
            <>
              <div className="relative flex-1 min-h-0">
                <ReactFlow
                  nodes={hoveredNodeId ? highlightedNodes : nodes}
                  edges={hoveredNodeId ? highlightedEdges : edges}
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
                    className="!bg-gray-50 dark:!bg-gray-800"
                  />
                  <Background variant={BackgroundVariant.Dots} gap={16} size={1} />
                  <Controls
                    showInteractive={false}
                    className="!bg-white/90 dark:!bg-gray-800/90 !border-gray-200 dark:!border-gray-700 !shadow-sm"
                  />
                </ReactFlow>

                <NodeDetailPanel node={selectedNode} onClose={() => setSelectedNode(null)} />
              </div>

              <LayerStatsBar layers={pathResult.layers} />
            </>
          )}
        </div>
      </div>
    </div>
  );
}

/** 对外导出的代码路径追踪组件（包裹 ReactFlowProvider） */
export function CodePathTracer() {
  return (
    <div className="w-full h-full">
      <ReactFlowProvider>
        <CodePathTracerInner />
      </ReactFlowProvider>
    </div>
  );
}
