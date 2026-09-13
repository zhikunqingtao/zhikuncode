/**
 * ToolCallBlock — 工具调用卡片组件
 *
 * SPEC: §8.2.1 ToolCallBlock, §8.2.2 14 种结果渲染器, §8.2.4D TOOL_RESULT_RENDERERS
 * 每个工具调用显示为可展开/折叠的卡片:
 * - Header: 工具图标 + 工具名 + 状态 + 耗时
 * - Input: 工具输入参数 (JSON, 可折叠)
 * - Result: 工具执行结果 (按工具类型选择渲染器)
 * - Progress: 执行中的进度指示
 *
 * §7.2 工具卡（Demo-B 精华）：折叠态一行（图标 + 名称 600 + 文件 chip
 * (mono sunken) + 状态）；Edit 类带 +n/−n diff chip（ok/err soft 底）；
 * 运行中 spin；完成 ✓ ok；耗时 tabular-nums；展开态进 Card 配方
 * （surface + hairline + rounded-panel + shadow-e2）。
 */

import React, { useState, useCallback, useEffect, useMemo } from 'react';
import {
    ChevronRight, Wrench, Loader2,
    Check, XCircle, ShieldAlert,
} from 'lucide-react';
import type { ToolCallState } from '@/types';
import CodeBlock from './CodeBlock';
import { TerminalRenderer } from './renderers/TerminalRenderer';
import { DiffRenderer } from './renderers/DiffRenderer';
import { SearchResultRenderer } from './renderers/SearchResultRenderer';
import { FileListRenderer } from './renderers/FileListRenderer';
import ExternalResourceRenderer from './renderers/ExternalResourceRenderer';
import ToolProgressBar from '../visualization/shared/ToolProgressBar';
import MiniLogViewer from '../visualization/shared/MiniLogViewer';
import { parseExternalResourceResult, structuredResultSchema } from '@/utils/structuredToolResult';

interface ToolCallBlockProps {
    toolUseId: string;
    toolCall: ToolCallState;
}

const STATUS_CONFIG = {
    pending:           { icon: Loader2, color: 'text-t4',      label: 'Pending',    spin: false },
    running:           { icon: Loader2, color: 'text-accent2', label: 'Running',    spin: true  },
    completed:         { icon: Check,   color: 'text-ok',      label: 'Completed',  spin: false },
    error:             { icon: XCircle, color: 'text-err',     label: 'Error',      spin: false },
    permission_needed: { icon: ShieldAlert, color: 'text-warn', label: 'Permission', spin: false },
} as const;

/** Edit 类工具名（带 +n/−n diff chip） */
const EDIT_TOOL_NAMES = new Set(['FileEditTool', 'FileEdit', 'Edit', 'MultiEdit', 'FileWriteTool', 'FileWrite', 'Write', 'NotebookEdit']);

/** 从工具输入提取主文件路径（file chip 数据源） */
function extractFilePath(input: unknown): string | null {
    if (!input || typeof input !== 'object' || Array.isArray(input)) return null;
    const record = input as Record<string, unknown>;
    const candidate = record.file_path ?? record.path ?? record.notebook_path ?? record.pattern;
    return typeof candidate === 'string' && candidate.length > 0 ? candidate : null;
}

/** Edit 类工具的 +n/−n 统计：优先 input 的 old/new_string，其次 result 的 unified diff 行 */
function computeDiffStats(toolCall: ToolCallState): { added: number; removed: number } | null {
    if (!EDIT_TOOL_NAMES.has(toolCall.toolName)) return null;
    const input = toolCall.input;
    if (input && typeof input === 'object' && !Array.isArray(input)) {
        const record = input as Record<string, unknown>;
        if (typeof record.old_string === 'string' && typeof record.new_string === 'string') {
            return {
                added: countLines(record.new_string),
                removed: countLines(record.old_string),
            };
        }
        if (Array.isArray(record.edits)) {
            let added = 0;
            let removed = 0;
            for (const edit of record.edits as Record<string, unknown>[]) {
                if (typeof edit?.new_string === 'string') added += countLines(edit.new_string);
                if (typeof edit?.old_string === 'string') removed += countLines(edit.old_string);
            }
            if (added > 0 || removed > 0) return { added, removed };
        }
        if (typeof record.content === 'string' && (toolCall.toolName.includes('Write') || toolCall.toolName === 'FileWriteTool')) {
            return { added: countLines(record.content), removed: 0 };
        }
    }
    const content = toolCall.result?.content;
    if (content && /^[+-]{1}(?![+-])/m.test(content)) {
        let added = 0;
        let removed = 0;
        for (const line of content.split('\n')) {
            if (line.startsWith('+') && !line.startsWith('+++')) added++;
            else if (line.startsWith('-') && !line.startsWith('---')) removed++;
        }
        if (added > 0 || removed > 0) return { added, removed };
    }
    return null;
}

function countLines(text: string): number {
    return text.length === 0 ? 0 : text.split('\n').length;
}

const ToolCallBlock: React.FC<ToolCallBlockProps> = ({ toolUseId, toolCall }) => {
    // 主折叠开关：默认展开（保持既有可见行为），折叠后为一行
    const [expanded, setExpanded] = useState(true);
    const [inputExpanded, setInputExpanded] = useState(false);
    const [resultExpanded, setResultExpanded] = useState(true);

    const statusCfg = STATUS_CONFIG[toolCall.status];
    const StatusIcon = statusCfg.icon;

    const toggleExpanded = useCallback(() => setExpanded(prev => !prev), []);
    const toggleInput = useCallback(() => setInputExpanded(prev => !prev), []);
    const toggleResult = useCallback(() => setResultExpanded(prev => !prev), []);

    const [now, setNow] = useState(() => Date.now());
    useEffect(() => {
        if (toolCall.status !== 'running') return;
        setNow(Date.now());
        const timer = window.setInterval(() => setNow(Date.now()), 1000);
        return () => window.clearInterval(timer);
    }, [toolCall.status]);

    const effectiveDuration = toolCall.status === 'running'
        ? Math.max(0, now - toolCall.startTime)
        : toolCall.duration;

    const formattedDuration = useMemo(() => {
        if (effectiveDuration == null) return null;
        if (effectiveDuration < 1000) return `${effectiveDuration}ms`;
        const totalSeconds = Math.floor(effectiveDuration / 1000);
        if (totalSeconds < 60) return `${totalSeconds}s`;
        const minutes = Math.floor(totalSeconds / 60);
        const seconds = totalSeconds % 60;
        if (minutes < 60) return `${minutes}m ${seconds}s`;
        const hours = Math.floor(minutes / 60);
        return `${hours}h ${minutes % 60}m`;
    }, [effectiveDuration]);

    const inputStr = useMemo(() => {
        try {
            return JSON.stringify(toolCall.input, null, 2);
        } catch {
            return String(toolCall.input);
        }
    }, [toolCall.input]);

    const filePath = useMemo(() => extractFilePath(toolCall.input), [toolCall.input]);
    const fileChip = filePath ? (filePath.split(/[\\/]/).pop() ?? filePath) : null;
    const diffStats = useMemo(() => computeDiffStats(toolCall), [toolCall]);

    return (
        <div
            className={`tool-call-block my-2 overflow-hidden border border-hairline transition-surface duration-fast
                ${expanded
                    ? 'rounded-panel bg-surfacev2 shadow-e2'
                    : 'rounded-xl bg-surface2'}`}
            data-tool-use-id={toolUseId}
        >
            {/* Header — 折叠态一行：图标 + 名称(600) + 文件 chip + diff chip + 状态 + 耗时 */}
            <button
                type="button"
                onClick={toggleExpanded}
                aria-expanded={expanded}
                className="flex w-full items-center gap-2 px-3 py-2 text-left transition-colors duration-fast hover:bg-hover2"
            >
                <ChevronRight
                    size={13}
                    className={`shrink-0 text-t4 transition-transform duration-200 ${expanded ? 'rotate-90' : ''}`}
                />
                <Wrench size={14} className="shrink-0 text-t3" />
                <span className="font-semibold text-sm text-t1 truncate">
                    {toolCall.toolName}
                </span>
                {fileChip && (
                    <span
                        className="shrink-0 max-w-[40%] truncate rounded-md bg-sunken2 px-2 py-0.5 font-mono text-[11px] text-t2"
                        title={filePath ?? undefined}
                    >
                        {fileChip}
                    </span>
                )}
                {diffStats && diffStats.added > 0 && (
                    <span className="shrink-0 rounded bg-oksoft px-1.5 py-0.5 text-[11px] font-medium tabular-nums text-ok">
                        +{diffStats.added}
                    </span>
                )}
                {diffStats && diffStats.removed > 0 && (
                    <span className="shrink-0 rounded bg-errsoft px-1.5 py-0.5 text-[11px] font-medium tabular-nums text-err">
                        −{diffStats.removed}
                    </span>
                )}
                <span className="ml-auto flex shrink-0 items-center gap-1.5">
                    <StatusIcon
                        size={14}
                        className={`${statusCfg.color} ${statusCfg.spin ? 'animate-spin' : ''}`}
                    />
                    <span className={`text-xs ${statusCfg.color}`}>
                        {statusCfg.label}
                    </span>
                    {formattedDuration && (
                        <span className="text-xs tabular-nums text-t4">
                            {formattedDuration}
                        </span>
                    )}
                </span>
            </button>

            {expanded && (
                <>
                    {/* Progress */}
                    {toolCall.progress && toolCall.status === 'running' && (
                        <div className="px-3 py-2 border-t border-hairline">
                            <ToolProgressBar
                                progress={toolCall.progress}
                                startTime={toolCall.startTime}
                            />
                            {toolCall.progressHistory && toolCall.progressHistory.length > 1 && (
                                <MiniLogViewer
                                    logs={toolCall.progressHistory}
                                    defaultCollapsed={true}
                                />
                            )}
                        </div>
                    )}

                    {/* Input (collapsible) */}
                    <div className="border-t border-hairline">
                        <button
                            onClick={toggleInput}
                            className="flex items-center gap-1.5 w-full px-3 py-1.5 text-xs text-t4 hover:text-t2 transition-colors"
                        >
                            <ChevronRight
                                size={12}
                                className={`transition-transform duration-200 ${inputExpanded ? 'rotate-90' : ''}`}
                            />
                            Input
                        </button>
                        {inputExpanded && (
                            <div className="px-3 pb-2">
                                <CodeBlock code={inputStr} language="json" showLineNumbers={false} maxHeight={200} />
                            </div>
                        )}
                    </div>

                    {/* Result */}
                    {toolCall.result && (
                        <div className="border-t border-hairline">
                            <button
                                onClick={toggleResult}
                                className="flex items-center gap-1.5 w-full px-3 py-1.5 text-xs text-t4 hover:text-t2 transition-colors"
                            >
                                <ChevronRight
                                    size={12}
                                    className={`transition-transform duration-200 ${resultExpanded ? 'rotate-90' : ''}`}
                                />
                                Result
                                {toolCall.result.isError && (
                                    <span className="text-err ml-1">(error)</span>
                                )}
                            </button>
                            {resultExpanded && (
                                <div className="px-3 pb-3">
                                    <ToolResultRenderer
                                        toolName={toolCall.toolName}
                                        content={toolCall.result.content}
                                        isError={toolCall.result.isError}
                                        metadata={toolCall.result.metadata}
                                    />
                                </div>
                            )}
                        </div>
                    )}
                </>
            )}
        </div>
    );
};

// ==================== Tool Result Renderer ====================

interface ToolResultRendererProps {
    toolName: string;
    content: string;
    isError: boolean;
    metadata?: Record<string, unknown>;
}

type StructuredResultRenderer = (
    metadata: Record<string, unknown>,
) => React.ReactNode | null;

const STRUCTURED_RESULT_RENDERERS: Record<string, StructuredResultRenderer> = {
    'external-resource/v1': (metadata) => {
        const resource = parseExternalResourceResult(metadata);
        return resource ? <ExternalResourceRenderer resource={resource} /> : null;
    },
};

/**
 * selectRenderer —  渲染器选择逻辑
 * 根据工具名选择合适的渲染模式。
 * 复杂渲染器 (DiffView, TerminalOutput 等) 将在后续 Round 中实现，
 * 此处使用 CodeBlock 作为基础渲染。
 */
const ToolResultRenderer: React.FC<ToolResultRendererProps> = ({
    toolName,
    content,
    isError,
    metadata,
}) => {
    if (isError) {
        return (
            <div className="rounded-xl border border-err bg-errsoft px-3 py-2 text-sm text-err">
                <div className="flex items-center gap-1.5 mb-1 font-medium">
                    <XCircle size={14} />
                    Error
                </div>
                <pre className="whitespace-pre-wrap text-xs">{content}</pre>
            </div>
        );
    }

    const schema = structuredResultSchema(metadata);
    if (schema) {
        const renderer = STRUCTURED_RESULT_RENDERERS[schema];
        const rendered = renderer?.(metadata ?? {});
        if (rendered) return rendered;
    }

    if (!content?.trim()) {
        return (
            <div className="text-xs text-t4 italic">No output</div>
        );
    }

    // 根据工具类型选择专用渲染器
    switch (toolName) {
        case 'BashTool':
        case 'Bash':
            return <TerminalRenderer content={content} isError={isError} />;
        case 'FileEditTool':
        case 'FileEdit':
            return <DiffRenderer content={content} />;
        case 'GrepTool':
        case 'Grep':
            return <SearchResultRenderer content={content} />;
        case 'GlobTool':
        case 'Glob':
            return <FileListRenderer content={content} />;
        default: {
            const lang = getResultLanguage(toolName);
            return <CodeBlock code={content} language={lang} showLineNumbers={false} maxHeight={400} />;
        }
    }
};

function getResultLanguage(toolName: string): string {
    switch (toolName) {
        case 'BashTool':
        case 'REPLTool':
            return 'bash';
        case 'FileReadTool':
        case 'FileEditTool':
        case 'FileWriteTool':
            return 'text';
        case 'GrepTool':
        case 'GlobTool':
            return 'text';
        case 'Config':
            return 'json';
        default:
            return 'text';
    }
}

export default React.memo(ToolCallBlock);
