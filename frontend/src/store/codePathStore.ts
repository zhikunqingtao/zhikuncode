/**
 * CodePathStore — 代码路径追踪状态管理
 * F40: Code Path Tracer
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';

// ── 类型定义 ──

export interface ApiEndpointItem {
  httpMethod: string;
  path: string;
  handlerFunction: string;
  handlerClass: string;
  filePath: string;
  lineNumber: number;
  language: string;
  parameters: Array<{ name: string; type: string; annotation?: string }>;
}

export interface PathNode {
  id: string;
  name: string;
  className: string;
  filePath: string;
  lineRange: number[];
  layer: 'controller' | 'service' | 'repository' | 'database' | 'external' | 'utility';
  nodeType: string;
  annotations: string[];
  parameters: Array<{ name: string; type: string; annotation?: string }>;
  returnType: string;
}

export interface PathEdge {
  source: string;
  target: string;
  callType: string;
  parameterMapping?: Record<string, string>;
}

export interface LayerInfo {
  layer: string;
  nodeCount: number;
  description: string;
}

export interface CodePathResult {
  nodes: PathNode[];
  edges: PathEdge[];
  layers: LayerInfo[];
}

export interface CodePathState {
  // 状态
  endpoints: ApiEndpointItem[];
  pathResult: CodePathResult | null;
  selectedEndpoint: ApiEndpointItem | null;
  selectedNode: PathNode | null;
  loading: boolean;
  endpointsLoading: boolean;
  error: string | null;
  projectRoot: string;

  // Actions
  setProjectRoot: (root: string) => void;
  fetchEndpoints: () => Promise<void>;
  traceCodePath: (entryFile: string, entryFunction: string, maxDepth?: number) => Promise<void>;
  setSelectedEndpoint: (endpoint: ApiEndpointItem | null) => void;
  setSelectedNode: (node: PathNode | null) => void;
  reset: () => void;
}

export const useCodePathStore = create<CodePathState>()(
  immer((set, get) => ({
    endpoints: [],
    pathResult: null,
    selectedEndpoint: null,
    selectedNode: null,
    loading: false,
    endpointsLoading: false,
    error: null,
    projectRoot: '',

    setProjectRoot: (root) => set(d => { d.projectRoot = root; }),

    fetchEndpoints: async () => {
      const { projectRoot } = get();
      set(d => { d.endpointsLoading = true; d.error = null; });
      try {
        const resp = await fetch('/api/code-path/endpoints', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ projectRoot }),
        });
        const json = await resp.json();
        if (json.success === false || json.error) {
          throw new Error(json.error || 'Failed to scan endpoints');
        }
        set(d => { d.endpoints = json.endpoints || []; d.endpointsLoading = false; });
      } catch (e: unknown) {
        const msg = e instanceof Error ? e.message : String(e);
        set(d => { d.error = msg; d.endpointsLoading = false; });
      }
    },

    traceCodePath: async (entryFile, entryFunction, maxDepth = 10) => {
      const { projectRoot } = get();
      set(d => { d.loading = true; d.error = null; d.pathResult = null; });
      try {
        const resp = await fetch('/api/code-path/trace', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ projectRoot, entryFile, entryFunction, maxDepth }),
        });
        const json = await resp.json();
        if (json.success === false || json.error) {
          throw new Error(json.error || 'Failed to trace code path');
        }
        set(d => {
          d.pathResult = {
            nodes: json.nodes || [],
            edges: json.edges || [],
            layers: json.layers || [],
          };
          d.loading = false;
        });
      } catch (e: unknown) {
        const msg = e instanceof Error ? e.message : String(e);
        set(d => { d.error = msg; d.loading = false; });
      }
    },

    setSelectedEndpoint: (endpoint) => set(d => { d.selectedEndpoint = endpoint; }),
    setSelectedNode: (node) => set(d => { d.selectedNode = node; }),
    reset: () => set(d => {
      d.pathResult = null;
      d.selectedEndpoint = null;
      d.selectedNode = null;
      d.error = null;
      d.loading = false;
    }),
  }))
);
