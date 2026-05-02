/**
 * ChangeImpactStore — 变更影响链路数据管理
 * 管理代码变更影响分析的请求与结果状态
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';

// ── 类型定义（基于 Python API 响应） ──

export interface ChangeImpactNode {
  id: string;
  type: 'api' | 'service' | 'repository' | 'scheduler' | 'config' | 'function' | 'class';
  name: string;
  file_path: string;
  line_range: number[];
  impact_level: 'direct' | 'indirect' | 'potential';
  confidence: 'high' | 'medium' | 'low';
  language?: string;
}

export interface ChangeImpactEdge {
  source: string;
  target: string;
  type: 'call' | 'dependency' | 'data-flow';
  weight: number;
}

export interface ChangeImpactSummary {
  direct_count: number;
  indirect_count: number;
  potential_count: number;
  affected_apis: string[];
  affected_tasks: string[];
}

export interface ChangeImpactResult {
  changed_file: string;
  changed_lines: number[];
  impact_nodes: ChangeImpactNode[];
  impact_edges: ChangeImpactEdge[];
  summary: ChangeImpactSummary;
}

interface ApiResponse<T = unknown> {
  success: boolean;
  data: T | null;
  error_code?: string | null;
  error_message?: string | null;
  elapsed_ms?: number;
}

// ── Store 状态 ──

export interface ChangeImpactState {
  impactData: ChangeImpactResult | null;
  isLoading: boolean;
  error: string | null;
  selectedNode: ChangeImpactNode | null;
  elapsedMs: number | null;

  fetchChangeImpact: (filePath: string, changedLines: number[], projectRoot: string, depth?: number) => Promise<void>;
  setSelectedNode: (node: ChangeImpactNode | null) => void;
  reset: () => void;
}

export const useChangeImpactStore = create<ChangeImpactState>()(
  subscribeWithSelector(immer((set) => ({
    impactData: null,
    isLoading: false,
    error: null,
    selectedNode: null,
    elapsedMs: null,

    fetchChangeImpact: async (filePath, changedLines, projectRoot, depth = 3) => {
      set(d => { d.isLoading = true; d.error = null; });
      try {
        const resp = await fetch('/api/analysis/change-impact', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            file_path: filePath,
            changed_lines: changedLines,
            project_root: projectRoot,
            depth,
          }),
        });
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const json: ApiResponse<ChangeImpactResult> = await resp.json();
        if (!json.success) throw new Error(json.error_message ?? 'Analysis failed');
        set(d => {
          d.impactData = json.data as ChangeImpactResult;
          d.elapsedMs = json.elapsed_ms ?? null;
          d.isLoading = false;
        });
      } catch (e) {
        set(d => {
          d.error = e instanceof Error ? e.message : String(e);
          d.isLoading = false;
        });
      }
    },

    setSelectedNode: (node) => set(d => { d.selectedNode = node; }),

    reset: () => set(d => {
      d.impactData = null;
      d.isLoading = false;
      d.error = null;
      d.selectedNode = null;
      d.elapsedMs = null;
    }),
  })))
);
