/**
 * DiagramStore — 代码→图表自动生成状态管理
 * F35: Code Diagram Generator
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';

// ── 类型定义 ──

export interface DiagramGenerationResult {
  diagramType: string;
  mermaidSyntax: string;
  confidenceScore: number;
  metadata: {
    nodesCount: number;
    edgesCount: number;
    languagesAnalyzed: string[];
    analysisTimeMs: number;
  };
  warnings: string[];
}

export interface DiagramState {
  // 状态
  diagramType: 'sequence' | 'flowchart';
  target: string;
  projectRoot: string;
  depth: number;
  result: DiagramGenerationResult | null;
  loading: boolean;
  error: string | null;

  // 编辑模式
  editedMermaidSyntax: string | null;

  // Actions
  setDiagramType: (type: 'sequence' | 'flowchart') => void;
  setTarget: (target: string) => void;
  setProjectRoot: (root: string) => void;
  setDepth: (depth: number) => void;
  generateDiagram: () => Promise<void>;
  clearDiagram: () => void;
  updateMermaidSyntax: (syntax: string) => void;
}

export const useDiagramStore = create<DiagramState>()(
  immer((set, get) => ({
    diagramType: 'sequence',
    target: '',
    projectRoot: '.',
    depth: 3,
    result: null,
    loading: false,
    error: null,
    editedMermaidSyntax: null,

    setDiagramType: (type) => set(d => { d.diagramType = type; }),
    setTarget: (target) => set(d => { d.target = target; }),
    setProjectRoot: (root) => set(d => { d.projectRoot = root; }),
    setDepth: (depth) => set(d => { d.depth = depth; }),

    generateDiagram: async () => {
      const { diagramType, target, projectRoot, depth } = get();
      if (!target.trim()) {
        set(d => { d.error = '请输入目标路径或方法签名'; });
        return;
      }
      set(d => { d.loading = true; d.error = null; d.editedMermaidSyntax = null; });
      try {
        const resp = await fetch('/api/code-diagrams/generate', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ diagramType, target, projectRoot, depth }),
        });
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const json = await resp.json();
        // API 返回 200 但 success=false 时视为错误
        if (json.success === false || json.error) {
          throw new Error(json.error || 'Unknown error');
        }
        const result: DiagramGenerationResult = json;
        set(d => {
          d.result = result;
          d.loading = false;
        });
      } catch (e) {
        set(d => {
          d.error = e instanceof Error ? e.message : String(e);
          d.loading = false;
        });
      }
    },

    clearDiagram: () => set(d => {
      d.result = null;
      d.error = null;
      d.editedMermaidSyntax = null;
    }),

    updateMermaidSyntax: (syntax) => set(d => {
      d.editedMermaidSyntax = syntax;
    }),
  }))
);
