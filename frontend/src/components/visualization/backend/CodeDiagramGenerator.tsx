/**
 * CodeDiagramGenerator — 代码→图表自动生成组件
 * F35: 从代码结构自动生成 Mermaid 时序图/流程图
 *
 * 功能:
 * - Tab 切换: 时序图 / 流程图
 * - 输入区: API 路径或方法签名、项目根目录、追踪深度
 * - 左侧 Monaco Editor 编辑 Mermaid 源码，右侧 MermaidBlock 实时预览
 * - 置信度指示条、警告展示、导出、元数据展示
 */

import React, { useState, useCallback, useEffect, useRef, useMemo } from 'react';
import {
  Loader2,
  AlertTriangle,
  ChevronDown,
  ChevronRight,
  Copy,
  Check,
  Download,
  Play,
  Trash2,
  Info,
} from 'lucide-react';
import Editor from '@monaco-editor/react';
import MermaidBlock from '@/components/visualization/shared/MermaidBlock';
import { useDiagramStore } from '@/store/diagramStore';
import { useConfigStore } from '@/store/configStore';

// ── 置信度颜色映射 ──

function getConfidenceColor(score: number): { bg: string; bar: string; text: string } {
  if (score >= 0.8) return { bg: 'bg-green-500/10', bar: 'bg-green-500', text: 'text-green-500' };
  if (score >= 0.5) return { bg: 'bg-yellow-500/10', bar: 'bg-yellow-500', text: 'text-yellow-500' };
  return { bg: 'bg-red-500/10', bar: 'bg-red-500', text: 'text-red-500' };
}

// ── 深度选择器 ──

const DepthSelector: React.FC<{ value: number; onChange: (v: number) => void }> = ({ value, onChange }) => (
  <div className="flex items-center gap-1">
    {[1, 2, 3, 4, 5].map(d => (
      <button
        key={d}
        onClick={() => onChange(d)}
        className={`w-7 h-7 rounded text-xs font-medium transition-colors
          ${d === value
            ? 'bg-blue-500 text-white'
            : 'bg-[var(--bg-primary)] text-[var(--text-secondary)] border border-[var(--border)] hover:bg-[var(--bg-hover)]'
          }`}
      >
        {d}
      </button>
    ))}
  </div>
);

// ── 警告列表 ──

const WarningList: React.FC<{ warnings: string[] }> = ({ warnings }) => {
  const [expanded, setExpanded] = useState(false);
  if (warnings.length === 0) return null;

  return (
    <div className="border border-yellow-500/30 rounded-md bg-yellow-500/5">
      <button
        onClick={() => setExpanded(e => !e)}
        className="w-full flex items-center gap-2 px-3 py-1.5 text-xs text-yellow-600 dark:text-yellow-400"
      >
        <AlertTriangle size={13} />
        <span>{warnings.length} 个警告</span>
        {expanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
      </button>
      {expanded && (
        <div className="px-3 pb-2 space-y-1">
          {warnings.map((w, i) => (
            <div key={i} className="text-xs text-yellow-600 dark:text-yellow-400 pl-5">
              • {w}
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

// ── 元数据展示 ──

const MetadataBar: React.FC<{
  metadata: { nodesCount: number; edgesCount: number; languagesAnalyzed: string[]; analysisTimeMs: number };
}> = ({ metadata }) => (
  <div className="flex items-center gap-3 text-[10px] text-[var(--text-muted)]">
    <span>节点: {metadata.nodesCount}</span>
    <span>边: {metadata.edgesCount}</span>
    <span>语言: {metadata.languagesAnalyzed.join(', ')}</span>
    <span>耗时: {metadata.analysisTimeMs.toFixed(0)}ms</span>
  </div>
);

// ── 主组件 ──

export const CodeDiagramGenerator: React.FC = () => {
  const diagramType = useDiagramStore(s => s.diagramType);
  const target = useDiagramStore(s => s.target);
  const projectRoot = useDiagramStore(s => s.projectRoot);
  const depth = useDiagramStore(s => s.depth);
  const result = useDiagramStore(s => s.result);
  const loading = useDiagramStore(s => s.loading);
  const error = useDiagramStore(s => s.error);
  const editedMermaidSyntax = useDiagramStore(s => s.editedMermaidSyntax);

  const setDiagramType = useDiagramStore(s => s.setDiagramType);
  const setTarget = useDiagramStore(s => s.setTarget);
  const setProjectRoot = useDiagramStore(s => s.setProjectRoot);
  const setDepth = useDiagramStore(s => s.setDepth);
  const generateDiagram = useDiagramStore(s => s.generateDiagram);
  const clearDiagram = useDiagramStore(s => s.clearDiagram);
  const updateMermaidSyntax = useDiagramStore(s => s.updateMermaidSyntax);

  const theme = useConfigStore(s => s.theme);
  const isDark = useMemo(() => {
    return theme.mode === 'dark' || theme.mode === 'glass' ||
      (theme.mode === 'system' && typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches);
  }, [theme.mode]);

  // Debounced preview update
  const debounceRef = useRef<ReturnType<typeof setTimeout>>();
  const [previewSyntax, setPreviewSyntax] = useState<string>('');

  // Sync preview with result or edited syntax
  useEffect(() => {
    const syntax = editedMermaidSyntax ?? result?.mermaidSyntax ?? '';
    setPreviewSyntax(syntax);
  }, [result?.mermaidSyntax, editedMermaidSyntax]);

  const handleEditorChange = useCallback((value: string | undefined) => {
    if (value === undefined) return;
    updateMermaidSyntax(value);
    // Debounced preview
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      setPreviewSyntax(value);
    }, 500);
  }, [updateMermaidSyntax]);

  const handleGenerate = useCallback(() => {
    generateDiagram();
  }, [generateDiagram]);

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      handleGenerate();
    }
  }, [handleGenerate]);

  // Export handlers
  const [svgCopied, setSvgCopied] = useState(false);
  const handleCopySvg = useCallback(async () => {
    const svgEl = document.querySelector('.diagram-preview-container svg');
    if (svgEl) {
      await navigator.clipboard.writeText(svgEl.outerHTML);
      setSvgCopied(true);
      setTimeout(() => setSvgCopied(false), 2000);
    }
  }, []);

  const handleDownloadPng = useCallback(() => {
    const svgEl = document.querySelector('.diagram-preview-container svg');
    if (!svgEl) return;
    const svgData = svgEl.outerHTML;
    const svgBlob = new Blob([svgData], { type: 'image/svg+xml;charset=utf-8' });
    const url = URL.createObjectURL(svgBlob);
    const img = new Image();
    img.onload = () => {
      const scale = 2;
      const canvas = document.createElement('canvas');
      canvas.width = img.naturalWidth * scale;
      canvas.height = img.naturalHeight * scale;
      const ctx = canvas.getContext('2d');
      if (ctx) {
        ctx.scale(scale, scale);
        ctx.drawImage(img, 0, 0);
      }
      const pngUrl = canvas.toDataURL('image/png');
      const a = document.createElement('a');
      a.href = pngUrl;
      a.download = `code-diagram-${diagramType}.png`;
      a.click();
      URL.revokeObjectURL(url);
    };
    img.src = url;
  }, [diagramType]);

  const editorValue = editedMermaidSyntax ?? result?.mermaidSyntax ?? '';
  const confidenceColor = result ? getConfidenceColor(result.confidenceScore) : null;

  const placeholder = diagramType === 'sequence'
    ? '/api/sessions/create'
    : 'SessionService.createSession';

  return (
    <div className="flex flex-col h-full" onKeyDown={handleKeyDown}>
      {/* Tab 切换 */}
      <div className="flex items-center gap-1 px-3 py-2 border-b border-[var(--border)] shrink-0">
        {(['sequence', 'flowchart'] as const).map(t => (
          <button
            key={t}
            onClick={() => setDiagramType(t)}
            className={`px-3 py-1 rounded text-xs font-medium transition-colors
              ${diagramType === t
                ? 'bg-blue-500 text-white'
                : 'bg-[var(--bg-primary)] text-[var(--text-secondary)] border border-[var(--border)] hover:bg-[var(--bg-hover)]'
              }`}
          >
            {t === 'sequence' ? '时序图' : '流程图'}
          </button>
        ))}
        {result && (
          <button
            onClick={clearDiagram}
            className="ml-auto p-1 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)]"
            title="清除结果"
          >
            <Trash2 size={13} />
          </button>
        )}
      </div>

      {/* 输入区 */}
      <div className="px-3 py-2 border-b border-[var(--border)] space-y-2 shrink-0">
        <div>
          <label className="text-[10px] uppercase tracking-wider text-[var(--text-muted)] mb-1 block">
            {diagramType === 'sequence' ? 'API 路径' : '方法签名'}
          </label>
          <input
            type="text"
            value={target}
            onChange={e => setTarget(e.target.value)}
            placeholder={placeholder}
            className="w-full px-2.5 py-1.5 text-sm rounded-md border border-[var(--border)]
              bg-[var(--bg-primary)] text-[var(--text-primary)]
              placeholder:text-[var(--text-muted)] focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
        </div>
        <div>
          <label className="text-[10px] uppercase tracking-wider text-[var(--text-muted)] mb-1 block">
            项目路径
          </label>
          <input
            type="text"
            value={projectRoot}
            onChange={e => setProjectRoot(e.target.value)}
            placeholder="."
            className="w-full px-2.5 py-1.5 text-sm rounded-md border border-[var(--border)]
              bg-[var(--bg-primary)] text-[var(--text-primary)]
              placeholder:text-[var(--text-muted)] focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
        </div>
        <div className="flex items-center justify-between gap-2">
          <div>
            <label className="text-[10px] uppercase tracking-wider text-[var(--text-muted)] mb-1 block">
              追踪深度
            </label>
            <DepthSelector value={depth} onChange={setDepth} />
          </div>
          <button
            onClick={handleGenerate}
            disabled={loading || !target.trim()}
            className="flex items-center gap-1.5 px-4 py-2 rounded-md text-sm font-medium
              bg-blue-500 text-white hover:bg-blue-600
              disabled:opacity-50 disabled:cursor-not-allowed transition-colors mt-3"
          >
            {loading ? (
              <Loader2 size={14} className="animate-spin" />
            ) : (
              <Play size={14} />
            )}
            {loading ? '生成中...' : '生成图表'}
          </button>
        </div>
      </div>

      {/* 错误提示 */}
      {error && (
        <div className="mx-3 mt-2 px-3 py-2 rounded-md border border-red-500/30 bg-red-500/5 text-xs text-red-500 flex items-center gap-2">
          <AlertTriangle size={13} />
          {error}
        </div>
      )}

      {/* 编辑+预览区 */}
      {result && (
        <div className="flex-1 min-h-0 flex flex-col">
          <div className="flex-1 min-h-0 flex flex-col lg:flex-row">
            {/* Monaco Editor */}
            <div className="flex-1 min-h-[200px] border-b lg:border-b-0 lg:border-r border-[var(--border)]">
              <div className="px-3 py-1 border-b border-[var(--border)] text-[10px] uppercase tracking-wider text-[var(--text-muted)]">
                Mermaid 源码
              </div>
              <Editor
                height="100%"
                defaultLanguage="markdown"
                value={editorValue}
                onChange={handleEditorChange}
                theme={isDark ? 'vs-dark' : 'light'}
                options={{
                  minimap: { enabled: false },
                  fontSize: 12,
                  lineNumbers: 'on',
                  wordWrap: 'on',
                  scrollBeyondLastLine: false,
                  automaticLayout: true,
                  padding: { top: 8 },
                }}
              />
            </div>

            {/* Mermaid Preview */}
            <div className="flex-1 min-h-[200px] overflow-auto">
              <div className="px-3 py-1 border-b border-[var(--border)] text-[10px] uppercase tracking-wider text-[var(--text-muted)]">
                图表预览
              </div>
              <div className="p-3 diagram-preview-container">
                {previewSyntax ? (
                  <MermaidBlock code={previewSyntax} />
                ) : (
                  <div className="flex items-center justify-center py-8 text-sm text-[var(--text-muted)]">
                    暂无预览内容
                  </div>
                )}
              </div>
            </div>
          </div>

          {/* 底部状态栏 */}
          <div className="px-3 py-2 border-t border-[var(--border)] space-y-2 shrink-0">
            {/* 置信度 + 导出 */}
            <div className="flex items-center gap-3">
              {confidenceColor && (
                <div className="flex items-center gap-2 flex-1 min-w-0">
                  <span className={`text-xs font-medium ${confidenceColor.text}`}>
                    置信度: {Math.round(result.confidenceScore * 100)}%
                  </span>
                  <div className="flex-1 h-1.5 rounded-full bg-[var(--bg-primary)] overflow-hidden max-w-[120px]">
                    <div
                      className={`h-full rounded-full ${confidenceColor.bar} transition-all`}
                      style={{ width: `${result.confidenceScore * 100}%` }}
                    />
                  </div>
                </div>
              )}
              <div className="flex items-center gap-1 shrink-0">
                <button
                  onClick={handleCopySvg}
                  className="flex items-center gap-1 px-2 py-1 rounded text-[10px] border border-[var(--border)]
                    hover:bg-[var(--bg-hover)] text-[var(--text-secondary)] transition-colors"
                  title="复制 SVG"
                >
                  {svgCopied ? <Check size={11} /> : <Copy size={11} />}
                  SVG
                </button>
                <button
                  onClick={handleDownloadPng}
                  className="flex items-center gap-1 px-2 py-1 rounded text-[10px] border border-[var(--border)]
                    hover:bg-[var(--bg-hover)] text-[var(--text-secondary)] transition-colors"
                  title="下载 PNG"
                >
                  <Download size={11} />
                  PNG
                </button>
              </div>
            </div>

            {/* 警告 */}
            <WarningList warnings={result.warnings} />

            {/* 元数据 */}
            <MetadataBar metadata={result.metadata} />
          </div>
        </div>
      )}

      {/* 空状态 */}
      {!result && !loading && !error && (
        <div className="flex-1 flex flex-col items-center justify-center py-12 px-4 text-center">
          <Info className="w-10 h-10 text-[var(--text-muted)] mb-3 opacity-40" />
          <p className="text-sm text-[var(--text-muted)]">输入目标并点击生成图表</p>
          <p className="text-xs text-[var(--text-muted)] mt-1 opacity-60">
            {diagramType === 'sequence'
              ? '输入 API 路径（如 /api/sessions/create）生成调用时序图'
              : '输入方法签名（如 SessionService.createSession）生成流程图'}
          </p>
        </div>
      )}

      {/* Loading 状态 */}
      {loading && !result && (
        <div className="flex-1 flex flex-col items-center justify-center">
          <Loader2 className="w-8 h-8 text-blue-500 animate-spin mb-3" />
          <p className="text-sm text-[var(--text-muted)]">正在分析代码结构...</p>
        </div>
      )}
    </div>
  );
};

export default CodeDiagramGenerator;
