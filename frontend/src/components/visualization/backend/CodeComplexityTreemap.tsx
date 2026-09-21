/**
 * CodeComplexityTreemap — 代码复杂度 Treemap 可视化组件
 *
 * 使用 recharts Treemap 组件将 ComplexityNode 树形数据渲染为矩形面积图。
 * 面积 = LOC（代码行数），颜色 = 风险等级（A-E）。
 * 支持钻取导航、面包屑、语言/风险过滤、Tooltip、统计卡片。
 */

import React, { useState, useMemo, useCallback } from 'react';
import { Treemap, ResponsiveContainer, Tooltip } from 'recharts';
import {
  FolderOpen,
  FileCode,
  AlertTriangle,
  Clock,
  BarChart3,
  ChevronRight,
  RefreshCw,
  Filter,
  X,
  Layers,
  ChevronDown,
  FileWarning,
} from 'lucide-react';
import { useComplexityStore, type ComplexityNode } from '@/store/complexityStore';

// ── 风险等级颜色映射 ──

// Solid risk fills keep white labels legible in all three themes.
const RISK_COLORS: Record<string, string> = {
  A: 'var(--v2-ok-strong)', B: 'var(--v2-ok)', C: 'var(--v2-warn)', D: 'var(--v2-warn-strong)', E: 'var(--v2-err)',
};
const RISK_BG_COLORS = RISK_COLORS;

const RISK_LABELS: Record<string, string> = {
  'A': '低风险',
  'B': '中等',
  'C': '较高',
  'D': '高风险',
  'E': '极高',
};

// ── recharts 数据适配 ──

interface RechartsNode {
  name: string;
  size: number;
  cc: number;
  mi: number;
  risk_level: string;
  file_path?: string;
  language?: string;
  type: string;
  hasChildren: boolean;
  originalNode: ComplexityNode;
}

function toRechartsData(nodes: ComplexityNode[]): RechartsNode[] {
  return nodes.map(node => ({
    name: node.name,
    size: Math.max(node.loc, 1),
    cc: node.cc,
    mi: node.mi,
    risk_level: node.risk_level,
    file_path: node.file_path,
    language: node.language,
    type: node.type,
    hasChildren: !!(node.children && node.children.length > 0),
    originalNode: node,
  }));
}

// ── 自定义 Treemap Content 渲染器 ──

interface CustomizedContentProps {
  x?: number;
  y?: number;
  width?: number;
  height?: number;
  name?: string;
  risk_level?: string;
  cc?: number;
  hasChildren?: boolean;
  originalNode?: ComplexityNode;
  onDrill?: (node: ComplexityNode) => void;
}

const CustomizedContent: React.FC<CustomizedContentProps> = (props) => {
  const { x = 0, y = 0, width = 0, height = 0, name, risk_level, cc, hasChildren, originalNode, onDrill } = props;

  const GAP = 2;
  const gx = x + GAP / 2;
  const gy = y + GAP / 2;
  const gw = width - GAP;
  const gh = height - GAP;

  const showCC = gw > 70 && gh > 46;
  const showName = gw > 48 && gh > 24;
  const showBadge = gw > 70 && gh > 70;

  const riskLevel = risk_level ?? 'A';
  const textColor = 'var(--v2-bg-surface)';
  const subtextColor = 'var(--v2-bg-surface)';

  const truncateName = (n: string, maxChars: number) =>
    n.length > maxChars ? n.slice(0, maxChars - 1) + '…' : n;

  const maxChars = Math.max(3, Math.floor((gw - 12) / 8));

  const handleClick = useCallback(() => {
    if (hasChildren && originalNode && onDrill) {
      onDrill(originalNode);
    }
  }, [hasChildren, originalNode, onDrill]);

  if (gw < 2 || gh < 2) return null;

  return (
    <g
      onClick={handleClick}
      role={hasChildren ? 'button' : undefined}
      tabIndex={hasChildren ? 0 : undefined}
      aria-label={hasChildren ? `展开 ${name}` : undefined}
      onKeyDown={event => {
        if (hasChildren && (event.key === 'Enter' || event.key === ' ')) {
          event.preventDefault();
          handleClick();
        }
      }}
      style={{ cursor: hasChildren ? 'pointer' : 'default' }}
    >
      <rect
        x={gx}
        y={gy}
        width={gw}
        height={gh}
        rx={3}
        ry={3}
        fill={RISK_BG_COLORS[riskLevel] ?? RISK_BG_COLORS['A']}
        stroke="rgba(255,255,255,0.2)"
        strokeWidth={0.5}
      />
      {/* hover overlay */}
      <rect
        x={gx}
        y={gy}
        width={gw}
        height={gh}
        rx={3}
        ry={3}
        fill="transparent"
        className="hover:fill-white/10 transition-colors"
      />
      {showName && name && (
        <text
          x={gx + 6}
          y={gy + 19}
          fontSize={13}
          fontWeight={600}
          fill={textColor}
          style={{ pointerEvents: 'none' }}
        >
          {truncateName(name, maxChars)}
        </text>
      )}
      {showCC && cc !== undefined && (
        <text
          x={gx + 6}
          y={gy + 39}
          fontSize={13}
          fill={subtextColor}
          style={{ pointerEvents: 'none' }}
        >
          CC: {cc.toFixed(1)}
        </text>
      )}
      {showBadge && (
        <>
          <rect
            x={gx + 6}
            y={gy + 48}
            width={18}
            height={18}
            rx={2}
            fill="rgba(0,0,0,0.2)"
          />
          <text
            x={gx + 15}
            y={gy + 62}
            fontSize={13}
            fontWeight={700}
            className="fill-white dark:fill-app2"
            textAnchor="middle"
            style={{ pointerEvents: 'none' }}
          >
            {riskLevel}
          </text>
        </>
      )}
      {/* drillable indicator */}
      {hasChildren && gw > 36 && gh > 36 && (
        <text
          x={gx + gw - 14}
          y={gy + gh - 6}
          fontSize={13}
          fill={subtextColor}
          style={{ pointerEvents: 'none' }}
        >
          ▸
        </text>
      )}
    </g>
  );
};

// ── 自定义 Tooltip 组件 ──

interface CustomTooltipProps {
  active?: boolean;
  payload?: Array<{ payload: RechartsNode }>;
}

const CustomTreemapTooltip: React.FC<CustomTooltipProps> = ({ active, payload }) => {
  if (!active || !payload || payload.length === 0) return null;
  const data = payload[0].payload;
  if (!data || !data.originalNode) return null;
  const node = data.originalNode;

  return (
    <div
      className="z-50 px-3 py-2.5 rounded-[14px] shadow-e4\n        border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-surface)]"
      style={{ maxWidth: 280 }}
    >
      <div className="flex items-center gap-1.5 mb-1.5">
        {node.type === 'directory' || node.type === 'project' ? (
          <FolderOpen size={13} className="text-[var(--v2-text-2)]" />
        ) : (
          <FileCode size={13} className="text-[var(--v2-text-2)]" />
        )}
        <span className="text-[13px] font-medium text-[var(--v2-text-1)] truncate">
          {node.name}
        </span>
        <span
          className="ml-auto px-1.5 py-0.5 rounded text-[13px] font-bold text-white dark:text-app2"
          style={{ backgroundColor: RISK_COLORS[node.risk_level] }}
        >
          {node.risk_level}
        </span>
      </div>
      {node.file_path && (
        <p className="text-[13px] text-[var(--v2-text-2)] mb-1.5 truncate">{node.file_path}</p>
      )}
      <div className="grid grid-cols-3 gap-x-3 gap-y-1 text-[13px]">
        <div>
          <span className="text-[var(--v2-text-2)]">LOC</span>
          <p className="font-medium text-[var(--v2-text-1)]">{node.loc.toLocaleString()}</p>
        </div>
        <div>
          <span className="text-[var(--v2-text-2)]">CC</span>
          <p className="font-medium text-[var(--v2-text-1)]">{node.cc.toFixed(1)}</p>
        </div>
        <div>
          <span className="text-[var(--v2-text-2)]">MI</span>
          <p className="font-medium text-[var(--v2-text-1)]">{node.mi.toFixed(1)}</p>
        </div>
      </div>
      {node.language && (
        <p className="text-[13px] text-[var(--v2-text-2)] mt-1">
          语言: <span className="text-[var(--v2-text-2)]">{node.language}</span>
        </p>
      )}
    </div>
  );
};

// ── 风险等级过滤下拉 ──

const RiskFilterDropdown: React.FC<{
  selected: string[];
  onChange: (levels: string[]) => void;
}> = ({ selected, onChange }) => {
  const [open, setOpen] = useState(false);
  const levels = ['A', 'B', 'C', 'D', 'E'];

  const toggle = useCallback((level: string) => {
    onChange(
      selected.includes(level)
        ? selected.filter(l => l !== level)
        : [...selected, level]
    );
  }, [selected, onChange]);

  return (
    <div className="relative min-w-[160px]" onKeyDown={event => { if (event.key === 'Escape') setOpen(false); }}>
      <button
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
        className="panel-control relative z-20 w-full flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[13px]
          border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-surface)]
          hover:bg-[var(--v2-bg-hover)] text-[var(--v2-text-2)] transition-colors"
      >
        <AlertTriangle size={13} />
        <span>风险等级</span>
        {selected.length > 0 && (
          <span className="px-1.5 py-0.5 rounded-full bg-warnsoft text-warn text-[13px] font-medium">
            {selected.length}
          </span>
        )}
        <ChevronDown size={12} className={`transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} />
          <div className="absolute top-full left-0 mt-1 z-20 min-w-[160px]
            rounded-[10px] border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-surface)] shadow-e3">
            <div className="flex items-center justify-between px-3 py-2 border-b border-[var(--v2-border-hairline)]">
              <span className="text-[13px] text-[var(--v2-text-2)]">选择风险等级</span>
              {selected.length > 0 && (
                <button onClick={() => onChange([])} className="panel-control text-[13px] text-accent2-ink hover:underline">
                  清除
                </button>
              )}
            </div>
            {levels.map(level => (
              <label
                key={level}
                className="flex items-center gap-2 px-3 py-1.5 hover:bg-[var(--v2-bg-hover)]
                  max-md:min-h-11 cursor-pointer text-[13px] text-[var(--v2-text-1)]"
              >
                <input
                  type="checkbox"
                  checked={selected.includes(level)}
                  onChange={() => toggle(level)}
                  className="rounded border-border-hairline text-accent2-ink focus:ring-accent2"
                />
                <span
                  className="w-2 h-2 rounded-full"
                  style={{ backgroundColor: RISK_COLORS[level] }}
                />
                <span>{level} - {RISK_LABELS[level]}</span>
              </label>
            ))}
          </div>
        </>
      )}
    </div>
  );
};

// ── 语言过滤下拉 ──

const LanguageFilterDropdown: React.FC<{
  languages: string[];
  selected: string | null;
  onChange: (lang: string | null) => void;
}> = ({ languages, selected, onChange }) => {
  const [open, setOpen] = useState(false);

  return (
    <div className="relative min-w-[140px]" onKeyDown={event => { if (event.key === 'Escape') setOpen(false); }}>
      <button
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
        className="panel-control relative z-20 w-full flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[13px]
          border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-surface)]
          hover:bg-[var(--v2-bg-hover)] text-[var(--v2-text-2)] transition-colors"
      >
        <Filter size={13} />
        <span>{selected ?? '所有语言'}</span>
        <ChevronDown size={12} className={`transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} />
          <div className="absolute top-full left-0 mt-1 z-20 min-w-[140px]
            rounded-[10px] border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-surface)] shadow-e3">
            <button
              onClick={() => { onChange(null); setOpen(false); }}
              className={`panel-control w-full text-left px-3 py-1.5 text-[13px] hover:bg-[var(--v2-bg-hover)]
                ${!selected ? 'text-accent2-ink font-medium' : 'text-[var(--v2-text-1)]'}`}
            >
              所有语言
            </button>
            {languages.map(lang => (
              <button
                key={lang}
                onClick={() => { onChange(lang); setOpen(false); }}
                className={`panel-control w-full text-left px-3 py-1.5 text-[13px] hover:bg-[var(--v2-bg-hover)]
                  ${selected === lang ? 'text-accent2-ink font-medium' : 'text-[var(--v2-text-1)]'}`}
              >
                {lang}
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
};

// ── 统计卡片 ──

const StatsCards: React.FC<{ stats: { total_files: number; avg_cc: number; high_risk_count: number; analysis_time_ms: number }; cached: boolean }> = ({ stats, cached }) => (
  <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
    <div className="flex items-center gap-1.5 text-[13px] text-[var(--v2-text-2)]">
      <Layers size={12} className="text-[var(--v2-text-2)]" />
      <span>{stats.total_files} 文件</span>
    </div>
    <div className="flex items-center gap-1.5 text-[13px] text-[var(--v2-text-2)]">
      <BarChart3 size={12} className="text-[var(--v2-text-2)]" />
      <span>CC {stats.avg_cc.toFixed(1)}</span>
    </div>
    <div className="flex items-center gap-1.5 text-[13px]">
      <FileWarning size={12} className={stats.high_risk_count > 0 ? 'text-warn' : 'text-[var(--v2-text-2)]'} />
      <span className={stats.high_risk_count > 0 ? 'text-warn font-medium' : 'text-[var(--v2-text-2)]'}>
        {stats.high_risk_count} 高风险
      </span>
    </div>
    <div className="flex items-center gap-1.5 text-[13px] text-[var(--v2-text-2)]">
      <Clock size={12} />
      <span>{stats.analysis_time_ms}ms</span>
      {cached && (
        <span className="px-1 py-0.5 rounded bg-accent2-soft text-accent2-ink text-[13px]">缓存</span>
      )}
    </div>
  </div>
);

// ── 骨架屏 ──

const TreemapSkeleton: React.FC = () => (
  <div className="w-full h-full p-1 grid grid-cols-4 grid-rows-3 gap-1 animate-pulse">
    {Array.from({ length: 12 }).map((_, i) => (
      <div
        key={i}
        className="rounded bg-[var(--v2-bg-sunken)]"
        style={{
          gridColumn: i === 0 ? 'span 2' : i === 3 ? 'span 2' : undefined,
          gridRow: i === 0 ? 'span 2' : undefined,
        }}
      />
    ))}
  </div>
);

// ── 过滤逻辑 ──

function filterChildren(
  children: ComplexityNode[] | undefined,
  languageFilter: string | null,
  riskLevelFilter: string[] | null
): ComplexityNode[] {
  if (!children) return [];
  return children.filter(child => {
    if (languageFilter && child.language && child.language !== languageFilter) return false;
    if (riskLevelFilter && riskLevelFilter.length > 0 && !riskLevelFilter.includes(child.risk_level)) return false;
    return true;
  });
}

function collectLanguages(node: ComplexityNode | null): string[] {
  if (!node) return [];
  const set = new Set<string>();
  const walk = (n: ComplexityNode) => {
    if (n.language) set.add(n.language);
    n.children?.forEach(walk);
  };
  walk(node);
  return Array.from(set).sort();
}

// ── 主组件 ──

export const CodeComplexityTreemap: React.FC = () => {
  const {
    complexityTree,
    stats,
    isLoading,
    error,
    cached,
    currentNode,
    currentDrillPath,
    languageFilter,
    riskLevelFilter,
    drillDown,
    drillUp,
    setLanguageFilter,
    setRiskLevelFilter,
    fetchComplexity,
  } = useComplexityStore();

  // 可用语言列表
  const languages = useMemo(() => collectLanguages(complexityTree), [complexityTree]);

  // 过滤后的子节点
  const displayChildren = useMemo(() => {
    return filterChildren(currentNode?.children, languageFilter, riskLevelFilter ?? null);
  }, [currentNode, languageFilter, riskLevelFilter]);

  // 转换为 recharts 数据格式
  const rechartsData = useMemo(() => {
    if (displayChildren.length === 0) return [];
    return toRechartsData(displayChildren);
  }, [displayChildren]);

  const handleDrill = useCallback((node: ComplexityNode) => {
    drillDown(node);
  }, [drillDown]);

  const handleRetry = useCallback(() => {
    if (complexityTree) {
      fetchComplexity(complexityTree.name);
    }
  }, [complexityTree, fetchComplexity]);

  // 自定义 content 渲染器（携带 onDrill 回调）
  const contentElement = useMemo(
    () => <CustomizedContent onDrill={handleDrill} />,
    [handleDrill]
  );

  // ── 错误状态 ──
  if (error) {
    return (
      <div className="flex flex-col items-center justify-center h-full p-6 text-center">
        <AlertTriangle className="w-10 h-10 text-err mb-3" />
        <p className="text-sm text-[var(--v2-text-1)] font-medium mb-1">分析失败</p>
        <p className="text-[13px] text-[var(--v2-text-2)] mb-4 max-w-xs">{error}</p>
        <button
          onClick={handleRetry}
          className="panel-control flex items-center gap-1.5 px-3 py-1.5 rounded-md text-[13px]
            bg-accent2-strong text-white hover:bg-accent2-hover transition-colors"
        >
          <RefreshCw size={12} />
          重试
        </button>
      </div>
    );
  }

  // ── 加载中 ──
  if (isLoading) {
    return (
      <div className="flex flex-col h-full">
        <div className="px-3 py-2 border-b border-[var(--v2-border-hairline)] shrink-0">
          <div className="h-4 w-48 bg-[var(--v2-bg-sunken)] rounded animate-pulse" />
        </div>
        <div className="flex-1 p-2">
          <TreemapSkeleton />
        </div>
      </div>
    );
  }

  // ── 空状态 ──
  if (!complexityTree || !currentNode) {
    return (
      <div className="flex flex-col items-center justify-center h-full p-6 text-center">
        <BarChart3 className="w-10 h-10 text-[var(--v2-text-2)] mb-3 opacity-40" />
        <p className="text-sm text-[var(--v2-text-2)]">暂无复杂度数据</p>
        <p className="text-[13px] text-[var(--v2-text-2)] mt-1">
          请先选择项目路径进行代码复杂度分析
        </p>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full">
      {/* 顶部工具栏 */}
      <div className="flex items-center gap-2 px-3 py-2 border-b border-[var(--v2-border-hairline)] shrink-0 flex-wrap">
        {/* 面包屑导航 */}
        <nav className="flex items-center gap-0.5 text-[13px] mr-auto min-w-0 overflow-hidden">
          {currentDrillPath.map((pathNode, idx) => (
            <React.Fragment key={`${pathNode.name}-${idx}`}>
              {idx > 0 && <ChevronRight size={12} className="text-[var(--v2-text-2)] shrink-0" />}
              <button
                onClick={() => drillUp(idx)}
                className={`panel-control truncate max-w-[120px] px-1 py-0.5 rounded transition-colors
                  ${idx === currentDrillPath.length - 1
                    ? 'text-[var(--v2-text-1)] font-medium'
                    : 'text-[var(--v2-text-2)] hover:text-[var(--v2-text-1)] hover:bg-[var(--v2-bg-hover)]'
                  }`}
              >
                {pathNode.type === 'project' || pathNode.type === 'directory' ? (
                  <span className="inline-flex items-center gap-1">
                    <FolderOpen size={11} className="shrink-0" />
                    {pathNode.name}
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-1">
                    <FileCode size={11} className="shrink-0" />
                    {pathNode.name}
                  </span>
                )}
              </button>
            </React.Fragment>
          ))}
        </nav>

        {/* 过滤器 */}
        {languages.length > 1 && (
          <LanguageFilterDropdown
            languages={languages}
            selected={languageFilter}
            onChange={setLanguageFilter}
          />
        )}
        <RiskFilterDropdown
          selected={riskLevelFilter ?? []}
          onChange={(levels) => setRiskLevelFilter(levels.length > 0 ? levels : null)}
        />

        {/* 统计信息 */}
        {stats && <StatsCards stats={stats} cached={cached} />}
      </div>

      {/* Treemap 区域 */}
      <div className="flex-1 min-h-0 overflow-hidden p-1">
        {rechartsData.length > 0 ? (
          <ResponsiveContainer width="100%" height="100%">
            <Treemap
              data={rechartsData}
              dataKey="size"
              aspectRatio={4 / 3}
              stroke="#fff"
              animationDuration={300}
              content={contentElement}
              isAnimationActive={false}
            >
              <Tooltip content={<CustomTreemapTooltip />} />
            </Treemap>
          </ResponsiveContainer>
        ) : (
          <div className="flex flex-col items-center justify-center h-full text-center">
            <X className="w-8 h-8 text-[var(--v2-text-2)] mb-2 opacity-40" />
            <p className="text-[13px] text-[var(--v2-text-2)]">
              {(languageFilter || (riskLevelFilter && riskLevelFilter.length > 0))
                ? '当前过滤条件下无匹配文件'
                : '该节点无子项'}
            </p>
          </div>
        )}
      </div>

      {/* 底部图例 */}
      <div className="flex flex-wrap items-center gap-3 px-3 py-2 border-t border-[var(--v2-border-hairline)] shrink-0">
        <span className="text-[13px] text-[var(--v2-text-2)] uppercase tracking-wider">风险等级</span>
        {Object.entries(RISK_COLORS).map(([level, color]) => (
          <div key={level} className="flex items-center gap-1">
            <span className="w-2.5 h-2.5 rounded-sm" style={{ backgroundColor: color }} />
            <span className="text-[13px] text-[var(--v2-text-2)]">{level}</span>
          </div>
        ))}
        <span className="ml-auto text-[13px] text-[var(--v2-text-2)]">
          面积 = 代码行数 (LOC) · 颜色 = 风险等级
        </span>
      </div>
    </div>
  );
};

export default CodeComplexityTreemap;
