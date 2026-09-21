/**
 * CodePathTracer — 代码路径追踪可视化组件
 * F40: 从 API 端点出发，追踪代码调用路径并以分层流图展示
 * 使用 @xyflow/react + dagre 渲染分层调用链路
 */

import { useMemo, useState, useCallback, useEffect, memo } from 'react';
import { useReducedMotion } from 'framer-motion';
import { getChartColors, resolveTheme } from '@/styles/design-tokens';
import { useConfigStore } from '@/store/configStore';
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

/** 当前主题模式 + 强调色的图表色板（§4.1 动态版；glass 归一为 light） */
function useChartColors(): string[] {
  const mode = useConfigStore(s => s.theme.mode);
  const accentColor = useConfigStore(s => s.theme.accentColor);
  return useMemo(() => getChartColors(resolveTheme(mode), accentColor), [mode, accentColor]);
}

function getLayerConfig(colors: string[]): Record<string, { color: string; icon: LucideIcon; label: string }> {
  return {
    controller: { color: colors[4], icon: Globe,    label: 'Controller' },
    service:    { color: colors[1], icon: Cog,      label: 'Service' },
    repository: { color: colors[5], icon: Database, label: 'Repository' },
    database:   { color: colors[2], icon: Database, label: 'Database' },
    external:   { color: colors[3], icon: Zap,      label: 'External' },
    utility:    { color: colors[7], icon: Box,      label: 'Utility' },
  };
}

function getMethodColors(colors: string[]): Record<string, string> {
  return {
    GET: colors[1],
    POST: colors[4],
    PUT: colors[2],
    DELETE: colors[3],
    PATCH: colors[5],
  };
}

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
      style: { stroke: 'var(--v2-text-2)', strokeWidth: 1.5 },
      labelStyle: { fill: 'var(--v2-text-1)', fontSize: 13 },
      labelBgStyle: { fill: 'var(--v2-bg-surface)' },
    };
  });

  return { nodes: flowNodes, edges: flowEdges };
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

// ── 自定义 LayerNode 组件 ──

function LayerNodeComponent({ data }: NodeProps) {
  const d = data as unknown as LayerNodeData;
  const colors = useChartColors();
  const layerConfig = getLayerConfig(colors);
  const config = layerConfig[d.layer] || layerConfig.utility;
  const Icon = config.icon;

  return (
    <div
      className="w-[220px] min-h-[100px] rounded-[14px] px-3 py-2.5 bg-surfacev2 transition-shadow"
      style={{
        border: `2px solid ${config.color}`,
        boxShadow: `0 0 6px ${config.color}20`,
      }}
    >
      <Handle type="target" position={Position.Top} className="!bg-t3 !w-2 !h-2" />

      {/* Header: layer badge */}
      <div className="flex items-center gap-1.5 mb-1">
        <Icon className="w-3.5 h-3.5 flex-shrink-0" style={{ color: config.color }} />
        <span
          className="text-[13px] px-1.5 py-0.5 rounded font-medium"
          style={{ backgroundColor: 'var(--v2-bg-sunken)', color: 'var(--v2-text-2)' }}
        >
          {config.label}
        </span>
      </div>

      {/* Name */}
      <p className="text-sm font-semibold text-t1 truncate leading-tight mb-0.5">
        {d.label}
      </p>

      {/* Class name */}
      {d.className && (
        <p className="text-[13px] text-t2 truncate">
          {d.className}
        </p>
      )}

      <Handle type="source" position={Position.Bottom} className="!bg-t3 !w-2 !h-2" />
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
  const colors = useChartColors();
  const methodColors = getMethodColors(colors);
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
    <div className="flex flex-col h-full border-r border-[var(--v2-border-hairline)] bg-[var(--v2-bg-surface-2)] w-[260px] min-w-[260px] max-md:w-full max-md:min-w-0 max-md:h-[min(240px,35%)] max-md:shrink-0 max-md:border-r-0 max-md:border-b">
      {/* Search */}
      <div className="p-2 border-b border-[var(--v2-border-hairline)]">
        <div className="relative">
          <Search className="absolute left-2 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-[var(--v2-text-2)]" />
          <input
            type="text"
            value={searchText}
            onChange={e => onSearchChange(e.target.value)}
            aria-label="搜索端点"
            placeholder="搜索端点..."
            className="panel-control w-full pl-7 pr-2 py-1.5 text-[13px] rounded border border-[var(--v2-border-hairline)]
              bg-[var(--v2-bg-surface)] text-[var(--v2-text-1)]
              placeholder:text-[var(--v2-text-2)] focus:outline-none focus:ring-1 focus:ring-accent2"
          />
        </div>
      </div>

      {/* List */}
      <div className="flex-1 overflow-y-auto p-1">
        {loading ? (
          <div className="flex items-center justify-center py-8">
            <Loader2 className="w-5 h-5 animate-spin text-accent2-ink" />
          </div>
        ) : filtered.length === 0 ? (
          <div className="py-8 text-center text-[13px] text-[var(--v2-text-2)]">
            {endpoints.length === 0 ? '点击扫描加载端点' : '无匹配端点'}
          </div>
        ) : (
          Array.from(grouped.entries()).map(([method, eps]) => (
            <div key={method} className="mb-2">
              <div className="px-2 py-1 text-[13px] font-semibold uppercase tracking-wider"
                style={{ color: 'var(--v2-text-2)', borderLeft: `3px solid ${methodColors[method] || colors[7]}` }}>
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
                    className={`panel-control w-full text-left px-2 py-1.5 rounded text-[13px] transition-colors
                      ${isSelected
                        ? 'bg-accent2-soft border border-accent2-ring'
                        : 'hover:bg-[var(--v2-bg-hover)] border border-transparent'}`}
                  >
                    <span className="font-mono text-[var(--v2-text-1)] truncate block">
                      {ep.path}
                    </span>
                    <span className="text-[13px] text-[var(--v2-text-2)] truncate block">
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
  const colors = useChartColors();
  if (!node) return null;
  const layerConfig = getLayerConfig(colors);
  const config = layerConfig[node.layer] || layerConfig.utility;
  const Icon = config.icon;

  return (
    <div className="absolute right-0 top-0 bottom-0 w-72 max-w-full bg-surfacev2 border-l border-border-hairline shadow-e3 z-20 overflow-y-auto">
      <div className="flex items-center justify-between p-3 border-b border-border-hairline">
        <span className="text-sm font-semibold text-t1">节点详情</span>
        <button aria-label="关闭节点详情" onClick={onClose} className="panel-control p-1 rounded hover:bg-hover2">
          <X className="w-4 h-4 text-t2" />
        </button>
      </div>
      <div className="p-3 space-y-3 break-words [overflow-wrap:anywhere]">
        <div>
          <label className="text-[13px] uppercase font-semibold text-t2">方法名</label>
          <p className="text-sm font-medium text-t1">{node.name}</p>
        </div>
        <div>
          <label className="text-[13px] uppercase font-semibold text-t2">类名</label>
          <p className="text-[13px] text-t2">{node.className}</p>
        </div>
        <div>
          <label className="text-[13px] uppercase font-semibold text-t2">层级</label>
          <div className="flex items-center gap-1.5 mt-0.5">
            <Icon className="w-3.5 h-3.5" style={{ color: config.color }} />
            <span className="text-[13px] text-t2">{config.label}</span>
          </div>
        </div>
        <div>
          <label className="text-[13px] uppercase font-semibold text-t2">返回类型</label>
          <p className="text-[13px] text-t2 font-mono">{node.returnType}</p>
        </div>
        {node.parameters.length > 0 && (
          <div>
            <label className="text-[13px] uppercase font-semibold text-t2">参数</label>
            <div className="mt-1 space-y-1">
              {node.parameters.map((p, i) => (
                <div key={i} className="text-[13px] text-t2 font-mono">
                  {p.name}: {p.type}
                  {p.annotation && <span className="text-accent2-ink ml-1">@{p.annotation}</span>}
                </div>
              ))}
            </div>
          </div>
        )}
        {node.annotations.length > 0 && (
          <div>
            <label className="text-[13px] uppercase font-semibold text-t2">注解</label>
            <div className="mt-1 flex flex-wrap gap-1">
              {node.annotations.map((a, i) => (
                <span key={i} className="text-[13px] px-1.5 py-0.5 rounded bg-accent2-soft text-accent2-ink">
                  @{a}
                </span>
              ))}
            </div>
          </div>
        )}
        <div>
          <label className="text-[13px] uppercase font-semibold text-t2">文件</label>
          <p className="text-[13px] text-t2 break-all">{node.filePath}</p>
        </div>
        {node.lineRange.length >= 2 && (
          <div>
            <label className="text-[13px] uppercase font-semibold text-t2">行范围</label>
            <p className="text-[13px] text-t2 font-mono">
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
  const colors = useChartColors();
  if (layers.length === 0) return null;
  const layerConfig = getLayerConfig(colors);
  return (
    <div className="border-t border-[var(--v2-border-hairline)] bg-[var(--v2-bg-surface-2)] px-3 py-2 flex items-center gap-x-4 gap-y-2 flex-wrap text-[13px] flex-shrink-0">
      {layers.map(l => {
        const config = layerConfig[l.layer] || layerConfig.utility;
        return (
          <span key={l.layer} className="flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full inline-block" style={{ backgroundColor: config.color }} />
            <span className="text-[var(--v2-text-2)]">{config.label}: {l.nodeCount}</span>
          </span>
        );
      })}
    </div>
  );
}

// ── 主图组件（需在 ReactFlowProvider 内） ──

function CodePathTracerInner() {
  const reduceMotion = useReducedMotion();
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
        animated: connected && !reduceMotion,
      };
    });
  }, [edges, hoveredNodeId, reduceMotion]);

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
    <div className="code-path-tracer flex flex-col w-full h-full bg-[var(--v2-bg-surface)]">
      {/* 顶部：项目路径 + 扫描 */}
      <div className="flex items-center gap-2 px-3 py-2 border-b border-[var(--v2-border-hairline)] flex-shrink-0">
        <label className="text-[13px] uppercase tracking-wider text-[var(--v2-text-2)] flex-shrink-0">项目路径</label>
        <input
          type="text"
          value={projectRoot}
          onChange={e => setProjectRoot(e.target.value)}
          aria-label="项目路径"
          placeholder="."
          className="panel-control flex-1 min-w-0 px-2 py-1 text-[13px] rounded border border-[var(--v2-border-hairline)]
            bg-[var(--v2-bg-surface)] text-[var(--v2-text-1)]
            placeholder:text-[var(--v2-text-2)] focus:outline-none focus:ring-1 focus:ring-accent2"
        />
        <button
          onClick={handleScan}
          disabled={endpointsLoading}
          className="panel-control flex items-center gap-1 px-3 py-1 rounded text-[13px] font-medium
            bg-accent2-strong text-white hover:bg-accent2-hover
            disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          {endpointsLoading ? <Loader2 size={12} className="animate-spin" /> : <Play size={12} />}
          扫描
        </button>
      </div>

      {/* 错误提示 */}
      {error && (
        <div className="mx-3 mt-2 px-3 py-2 rounded border border-[color:color-mix(in_srgb,var(--v2-err)_30%,transparent)] bg-errsoft text-[13px] text-err flex items-center gap-2 flex-shrink-0">
          <AlertTriangle size={13} />
          {error}
        </div>
      )}

      {/* 主内容：左端点列表 + 右流图 */}
      <div className="flex flex-1 min-h-0 max-md:flex-col">
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
        <div className="flex-1 flex flex-col min-w-0 min-h-0">
          {loading ? (
            <div className="flex-1 flex flex-col items-center justify-center">
              <Loader2 className="w-8 h-8 text-accent2-ink animate-spin mb-3" />
              <p className="text-sm text-[var(--v2-text-2)]">正在追踪代码路径...</p>
            </div>
          ) : !pathResult ? (
            <div className="flex-1 flex flex-col items-center justify-center text-center p-6">
              <Network className="w-12 h-12 text-t3 mb-3" />
              <p className="text-sm text-[var(--v2-text-2)] font-medium">暂无路径数据</p>
              <p className="text-[13px] text-[var(--v2-text-2)] mt-1">
                扫描端点后，点击任意端点开始追踪
              </p>
            </div>
          ) : pathResult.nodes.length === 0 ? (
            <div className="flex-1 flex flex-col items-center justify-center text-center p-6">
              <Network className="w-12 h-12 text-ok mb-3" />
              <p className="text-sm text-[var(--v2-text-2)] font-medium">未发现调用路径</p>
              <p className="text-[13px] text-[var(--v2-text-2)] mt-1">
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
                    className="!bg-surface2"
                  />
                  <Background variant={BackgroundVariant.Dots} gap={16} size={1} />
                  <Controls
                    showInteractive={false}
                    className="!bg-surfacev2 !border-border-hairline !shadow-e1"
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
