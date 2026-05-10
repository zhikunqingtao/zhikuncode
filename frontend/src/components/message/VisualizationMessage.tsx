/**
 * VisualizationMessage — 结构化可视化消息独立渲染器
 *
 * 对齐 ZhikunCode 差异化升级方案 v1.5 §4.5 C（升级项 C.4 前端接入）+ 修订版混合分发。
 *
 * 路由：MessageItem.renderMessage → case 'visualization' → 本组件
 * 数据源：后端 VisualizationPayloadBuilder 推送的 { type: 'visualization', viewType, props }
 *
 * 设计（修订版）：
 * - 独立消息路线（不是 assistant ContentBlock 分支）— v1.4 BLK-R4-1 校准
 * - 混合分发策略（基于 6 组件 props 签名实测）：
 *   · 直渲染类（接受 props）：git-timeline、schema-viewer、mermaid
 *   · Hint 卡片 + 跳转类（4 自治组件）：change-impact-graph / code-path-tracer /
 *     code-complexity-treemap / api-sequence-diagram
 *   · 兼容保留：text / json
 * - 所有具体视图组件 lazy 以减小初始 bundle
 */

import React, { Suspense, lazy, useCallback, useMemo } from 'react';
import type { Message } from '@/types';
import { AlertTriangle, ExternalLink } from 'lucide-react';
import { useAppUiStore } from '@/store/appUiStore';
import { useChangeImpactStore } from '@/store/changeImpactStore';
import { useCodePathStore } from '@/store/codePathStore';
import { useComplexityStore } from '@/store/complexityStore';
import { useApiContractStore } from '@/store/apiContractStore';
import type { SchemaObject } from '@/store/apiContractStore';

// 复用现有 shared/backend 可视化组件（懒加载）
const MermaidBlock = lazy(() => import('@/components/visualization/shared/MermaidBlock'));
const GitTimeline = lazy(() =>
    import('@/components/visualization/shared/GitTimeline').then((m) => ({ default: m.GitTimeline })),
);
const SchemaViewer = lazy(() => import('@/components/visualization/backend/SchemaViewer'));

interface VisualizationMessageProps {
    message: Extract<Message, { type: 'visualization' }>;
}

const VisualizationMessage: React.FC<VisualizationMessageProps> = ({ message }) => {
    const { viewType, props } = message;
    const body = useMemo(
        () => renderByViewType(viewType, props),
        [viewType, props],
    );

    return (
        <div className="px-4 py-2 my-1">
            <div className="rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)]/30 overflow-hidden">
                <div className="px-3 py-1.5 bg-[var(--bg-secondary)] border-b border-[var(--border)] text-xs text-[var(--text-secondary)] font-mono flex items-center gap-2">
                    <span className="opacity-60">visualization</span>
                    <span className="opacity-40">·</span>
                    <span>{viewType}</span>
                </div>
                <div className="p-3">
                    <Suspense fallback={<LoadingSkeleton />}>{body}</Suspense>
                </div>
            </div>
        </div>
    );
};

/** 按 viewType 分派；未识别降级为 JSON/文本视图。 */
function renderByViewType(
    viewType: string,
    props: Record<string, unknown>,
): React.ReactNode {
    const vt = (viewType || '').toLowerCase();

    switch (vt) {
        // ========== 直渲染类：接受 props 的组件 ==========
        case 'git-timeline': {
            const repoPath = pickString(props, 'repoPath', 'repo') || '.';
            return <GitTimeline repoPath={repoPath} />;
        }

        case 'schema-viewer': {
            const schema = props['schema'];
            const name = pickString(props, 'name');
            if (!schema || typeof schema !== 'object') {
                return <EmptyHint what="Schema 对象" />;
            }
            return (
                <SchemaViewer
                    schema={schema as SchemaObject}
                    name={name || undefined}
                />
            );
        }

        case 'mermaid': {
            const code = pickString(props, 'source', 'content', 'code');
            if (!code.trim()) return <EmptyHint what="Mermaid 源码" />;
            return <MermaidBlock code={code} />;
        }

        // ========== Hint 卡片 + 跳转类：4 个自治组件 ==========
        case 'change-impact-graph':
            return (
                <HintCard
                    title="变更影响链路"
                    description="已识别变更影响，可在影响分析面板查看完整链路"
                    targetTab="impact"
                    viewType={vt}
                    props={props}
                />
            );

        case 'code-path-tracer':
            return (
                <HintCard
                    title="代码路径追踪"
                    description="已识别调用链入口，可在代码路径面板交互式追踪"
                    targetTab="code-path"
                    viewType={vt}
                    props={props}
                />
            );

        case 'code-complexity-treemap':
            return (
                <HintCard
                    title="代码复杂度热度图"
                    description="可在复杂度面板查看 Treemap 钻取"
                    targetTab="complexity"
                    viewType={vt}
                    props={props}
                />
            );

        case 'api-sequence-diagram':
            return (
                <HintCard
                    title="API 调用序列图"
                    description="可在序列图面板查看完整 API 交互"
                    targetTab="sequence"
                    viewType={vt}
                    props={props}
                />
            );

        // ========== 兼容分支（v1.4 保留） ==========
        case 'text': {
            const content = pickString(props, 'content');
            if (!content.trim()) return <EmptyHint what="文本内容" />;
            return (
                <pre className="whitespace-pre-wrap text-sm text-[var(--text-primary)] font-mono">
                    {content}
                </pre>
            );
        }

        case 'json':
            return <JsonView props={props} />;

        default:
            return (
                <div>
                    <div className="flex items-center gap-1.5 text-xs text-amber-500 dark:text-amber-400 mb-2">
                        <AlertTriangle size={12} />
                        <span>未识别的 viewType &quot;{viewType}&quot;，降级为 JSON 视图</span>
                    </div>
                    <JsonView props={props} />
                </div>
            );
    }
}

/** 从 props 中按顺序取第一个非空字符串字段。 */
function pickString(props: Record<string, unknown>, ...keys: string[]): string {
    for (const key of keys) {
        const v = props[key];
        if (typeof v === 'string') return v;
    }
    return '';
}

// ==================== HintCard：自治组件跳转卡片 ====================

interface HintCardProps {
    title: string;
    description: string;
    /** Sidebar tab id（对齐 TabType） */
    targetTab: 'impact' | 'code-path' | 'complexity' | 'sequence';
    viewType: string;
    props: Record<string, unknown>;
}

const HintCard: React.FC<HintCardProps> = ({ title, description, targetTab, viewType, props }) => {
    const requestVisualizationTab = useAppUiStore((s) => s.requestVisualizationTab);
    const applyChangeImpactHint = useChangeImpactStore((s) => s.applyVisualizationHint);
    const applyCodePathHint = useCodePathStore((s) => s.applyVisualizationHint);
    const applyComplexityHint = useComplexityStore((s) => s.applyVisualizationHint);
    const applyApiContractHint = useApiContractStore((s) => s.applyVisualizationHint);

    const handleOpen = useCallback(() => {
        // 1) 写入对应 store hint（4 个自治组件各自消费）
        switch (viewType) {
            case 'change-impact-graph':
                applyChangeImpactHint(props);
                break;
            case 'code-path-tracer':
                applyCodePathHint(props);
                break;
            case 'code-complexity-treemap':
                applyComplexityHint(props);
                break;
            case 'api-sequence-diagram':
                applyApiContractHint(props);
                break;
        }
        // 2) 请求 Sidebar 切到对应 tab
        requestVisualizationTab(targetTab);
    }, [
        viewType,
        props,
        targetTab,
        applyChangeImpactHint,
        applyCodePathHint,
        applyComplexityHint,
        applyApiContractHint,
        requestVisualizationTab,
    ]);

    const summary = summarizeProps(props);

    return (
        <div className="flex items-start gap-3 p-2 rounded border border-[var(--border)] bg-[var(--bg-primary)]/60">
            <div className="flex-1 min-w-0">
                <div className="text-sm font-medium text-[var(--text-primary)]">{title}</div>
                <div className="text-xs text-[var(--text-secondary)] mt-0.5">{description}</div>
                {summary && (
                    <div className="text-[11px] text-[var(--text-muted)] mt-1 font-mono truncate">
                        {summary}
                    </div>
                )}
            </div>
            <button
                type="button"
                onClick={handleOpen}
                className="flex items-center gap-1 text-xs px-2 py-1 rounded bg-blue-500/15 hover:bg-blue-500/25 text-blue-600 dark:text-blue-400 border border-blue-500/30 transition-colors flex-shrink-0"
            >
                <ExternalLink size={12} />
                <span>在可视化面板查看</span>
            </button>
        </div>
    );
};

/** 生成紧凑的 props 摘要（仅前 3 个字符串/数字类字段，避免卡片过长）。 */
function summarizeProps(props: Record<string, unknown>): string {
    const parts: string[] = [];
    for (const [k, v] of Object.entries(props)) {
        if (parts.length >= 3) break;
        if (v == null) continue;
        if (typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') {
            const sv = String(v);
            parts.push(`${k}=${sv.length > 40 ? sv.slice(0, 40) + '…' : sv}`);
        }
    }
    return parts.join(' · ');
}

// ==================== JsonView / fallback ====================

const JsonView: React.FC<{ props: Record<string, unknown> }> = ({ props }) => {
    const keys = Object.keys(props);
    if (keys.length === 1 && keys[0] === 'content' && typeof props.content === 'string') {
        return (
            <pre className="whitespace-pre-wrap text-sm text-[var(--text-primary)] font-mono">
                {props.content}
            </pre>
        );
    }
    return (
        <pre className="text-xs text-[var(--text-secondary)] font-mono overflow-x-auto whitespace-pre">
            {safeStringify(props)}
        </pre>
    );
};

function safeStringify(value: unknown): string {
    try {
        return JSON.stringify(value, null, 2);
    } catch {
        return String(value);
    }
}

const LoadingSkeleton: React.FC = () => (
    <div className="flex items-center justify-center py-6">
        <div className="w-4 h-4 rounded-full bg-blue-400 animate-pulse" />
    </div>
);

const EmptyHint: React.FC<{ what: string }> = ({ what }) => (
    <div className="text-xs text-[var(--text-muted)] italic">（空 {what}）</div>
);

export default React.memo(VisualizationMessage);
