/**
 * ComplexityStore — 代码复杂度数据管理
 * 管理代码复杂度分析结果、钻取导航、过滤状态
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';

// ── 类型定义（基于 Python API 响应） ──

export interface ComplexityNode {
  name: string;
  type: 'project' | 'directory' | 'file' | 'class' | 'method';
  loc: number;
  cc: number;
  mi: number;
  risk_level: 'A' | 'B' | 'C' | 'D' | 'E';
  children?: ComplexityNode[];
  file_path?: string;
  language?: string;
}

export interface ComplexityStats {
  total_files: number;
  avg_cc: number;
  high_risk_count: number;
  analysis_time_ms: number;
}

interface ComplexityApiResponse {
  success: boolean;
  data: {
    root: ComplexityNode;
    stats: ComplexityStats;
    cached: boolean;
  } | null;
  error_message?: string;
  elapsed_ms?: number;
}

// ── Store 状态 ──

export interface ComplexityState {
  complexityTree: ComplexityNode | null;
  stats: ComplexityStats | null;
  isLoading: boolean;
  error: string | null;
  cached: boolean;

  // 钻取导航状态
  currentDrillPath: ComplexityNode[];   // 面包屑路径栈
  currentNode: ComplexityNode | null;   // 当前显示的节点

  // 过滤状态
  languageFilter: string | null;
  riskLevelFilter: string[] | null;     // e.g., ['C', 'D', 'E']

  // Actions
  fetchComplexity: (projectRoot: string, targetPath?: string, languages?: string[]) => Promise<void>;
  drillDown: (node: ComplexityNode) => void;
  drillUp: (index?: number) => void;    // 面包屑导航，index 可跳到指定层
  setLanguageFilter: (language: string | null) => void;
  setRiskLevelFilter: (levels: string[] | null) => void;
  reset: () => void;
}

export const useComplexityStore = create<ComplexityState>()(
  subscribeWithSelector(immer((set, _get) => ({
    complexityTree: null,
    stats: null,
    isLoading: false,
    error: null,
    cached: false,

    currentDrillPath: [],
    currentNode: null,

    languageFilter: null,
    riskLevelFilter: null,

    fetchComplexity: async (projectRoot, targetPath, languages) => {
      set(d => { d.isLoading = true; d.error = null; });
      try {
        const resp = await fetch('/api/code-quality/complexity', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            project_root: projectRoot,
            ...(targetPath ? { target_path: targetPath } : {}),
            ...(languages && languages.length > 0 ? { languages } : {}),
          }),
        });
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const json: ComplexityApiResponse = await resp.json();
        if (!json.success || !json.data) {
          throw new Error(json.error_message ?? '分析失败');
        }
        set(d => {
          d.complexityTree = json.data!.root;
          d.stats = json.data!.stats;
          d.cached = json.data!.cached;
          d.isLoading = false;
          d.currentNode = json.data!.root;
          d.currentDrillPath = [json.data!.root];
        });
      } catch (e) {
        set(d => {
          d.error = e instanceof Error ? e.message : String(e);
          d.isLoading = false;
        });
      }
    },

    drillDown: (node) => {
      if (!node.children || node.children.length === 0) return;
      set(d => {
        d.currentDrillPath.push(node);
        d.currentNode = node;
      });
    },

    drillUp: (index) => {
      set(d => {
        if (index !== undefined && index >= 0 && index < d.currentDrillPath.length) {
          d.currentDrillPath = d.currentDrillPath.slice(0, index + 1);
          d.currentNode = d.currentDrillPath[index];
        } else if (d.currentDrillPath.length > 1) {
          d.currentDrillPath.pop();
          d.currentNode = d.currentDrillPath[d.currentDrillPath.length - 1];
        }
      });
    },

    setLanguageFilter: (language) => {
      set(d => { d.languageFilter = language; });
    },

    setRiskLevelFilter: (levels) => {
      set(d => { d.riskLevelFilter = levels; });
    },

    reset: () => {
      set(d => {
        d.complexityTree = null;
        d.stats = null;
        d.isLoading = false;
        d.error = null;
        d.cached = false;
        d.currentDrillPath = [];
        d.currentNode = null;
        d.languageFilter = null;
        d.riskLevelFilter = null;
      });
    },
  })))
);
