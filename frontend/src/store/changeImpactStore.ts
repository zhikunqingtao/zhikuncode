/**
 * ChangeImpactStore — 变更影响链路数据管理
 * 管理代码变更影响分析的请求与结果状态
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type { AggregatedFileChange, RiskSummary } from '@/types/apos';

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
  /** Auto-Routing 写入的预填提示（v1.5 升级项 C Beta） */
  lastHint: Record<string, unknown> | null;

  fetchChangeImpact: (filePath: string, changedLines: number[], projectRoot: string, depth?: number) => Promise<void>;
  setSelectedNode: (node: ChangeImpactNode | null) => void;
  applyVisualizationHint: (props: Record<string, unknown>) => void;
  reset: () => void;
}

export const useChangeImpactStore = create<ChangeImpactState>()(
  subscribeWithSelector(immer((set) => ({
    impactData: null,
    isLoading: false,
    error: null,
    selectedNode: null,
    elapsedMs: null,
    lastHint: null,

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

    applyVisualizationHint: (props) => set(d => { d.lastHint = props ?? null; }),

    reset: () => set(d => {
      d.impactData = null;
      d.isLoading = false;
      d.error = null;
      d.selectedNode = null;
      d.elapsedMs = null;
      d.lastHint = null;
    }),
  })))
);

// ══════════════════════════════════════════════════════════════
// Phase 2: 聚合变更影响 Store — 会话级文件变更聚合与风险评估
// ══════════════════════════════════════════════════════════════

interface ChangeImpactAggStoreState {
    aggregatedChanges: AggregatedFileChange[];
    riskSummary: RiskSummary;
    selectedFilePath: string | null;

    aggregateSessionChanges: (activities: Array<{ changedFiles?: Array<{ path: string; additions: number; deletions: number; changeType: string }> }>) => void;
    selectFile: (filePath: string | null) => void;
    clearAll: () => void;
}

const RISK_ORDER: Record<string, number> = { danger: 0, warning: 1, review: 2, safe: 3 };

export const useChangeImpactAggStore = create<ChangeImpactAggStoreState>()(
    immer((set) => ({
        aggregatedChanges: [],
        riskSummary: { totalFiles: 0, highRiskCount: 0, testCoverageGapCount: 0, indirectImpactCount: 0 },
        selectedFilePath: null,

        aggregateSessionChanges: (activities) => set(state => {
            const fileMap = new Map<string, AggregatedFileChange>();

            for (const activity of activities) {
                if (!activity.changedFiles) continue;
                for (const file of activity.changedFiles) {
                    const existing = fileMap.get(file.path);
                    if (existing) {
                        existing.totalAdditions += file.additions;
                        existing.totalDeletions += file.deletions;
                        existing.touchCount += 1;
                    } else {
                        fileMap.set(file.path, {
                            filePath: file.path,
                            changeType: (file.changeType as AggregatedFileChange['changeType']) || 'modified',
                            totalAdditions: file.additions,
                            totalDeletions: file.deletions,
                            riskLevel: 'safe',
                            testCoverageGap: false,
                            touchCount: 1,
                            indirectImpacts: [],
                        });
                    }
                }
            }

            // 计算 riskLevel
            for (const [, file] of fileMap) {
                if (file.touchCount >= 3 || file.indirectImpacts.some(i => i.severity === 'high')) {
                    file.riskLevel = 'danger';
                } else if (file.touchCount >= 2 || file.indirectImpacts.length > 0) {
                    file.riskLevel = 'warning';
                } else if (file.totalAdditions + file.totalDeletions > 50) {
                    file.riskLevel = 'review';
                } else {
                    file.riskLevel = 'safe';
                }
            }

            // 按 riskLevel 降序排列
            const sorted = Array.from(fileMap.values()).sort(
                (a, b) => (RISK_ORDER[a.riskLevel] ?? 99) - (RISK_ORDER[b.riskLevel] ?? 99)
            );

            state.aggregatedChanges = sorted;
            state.riskSummary = {
                totalFiles: sorted.length,
                highRiskCount: sorted.filter(f => f.riskLevel === 'danger' || f.riskLevel === 'warning').length,
                testCoverageGapCount: sorted.filter(f => f.testCoverageGap).length,
                indirectImpactCount: sorted.reduce((sum, f) => sum + f.indirectImpacts.length, 0),
            };
        }),

        selectFile: (filePath) => set(state => { state.selectedFilePath = filePath; }),
        clearAll: () => set(state => {
            state.aggregatedChanges = [];
            state.riskSummary = { totalFiles: 0, highRiskCount: 0, testCoverageGapCount: 0, indirectImpactCount: 0 };
            state.selectedFilePath = null;
        }),
    }))
);
