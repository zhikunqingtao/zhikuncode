/**
 * DAG Layout Utility — 基于 dagre 的有向无环图布局计算
 * 用于 Agent 协作 DAG 可视化
 */

import dagre from 'dagre';

interface DAGNode {
  id: string;
  width?: number;
  height?: number;
}

interface DAGEdge {
  source: string;
  target: string;
}

interface LayoutResult {
  nodes: Array<{ id: string; x: number; y: number }>;
  edges: Array<{ source: string; target: string; points?: Array<{ x: number; y: number }> }>;
}

export function computeDAGLayout(
  nodes: DAGNode[],
  edges: DAGEdge[],
  direction: 'TB' | 'LR' = 'TB'
): LayoutResult {
  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({
    rankdir: direction,
    nodesep: 50,
    ranksep: 80,
    marginx: 20,
    marginy: 20,
  });

  nodes.forEach((node) => {
    g.setNode(node.id, { width: node.width || 220, height: node.height || 80 });
  });

  edges.forEach((edge) => {
    g.setEdge(edge.source, edge.target);
  });

  dagre.layout(g);

  return {
    nodes: nodes.map((n) => {
      const pos = g.node(n.id);
      return { id: n.id, x: pos.x, y: pos.y };
    }),
    edges: edges.map((e) => ({
      source: e.source,
      target: e.target,
    })),
  };
}
