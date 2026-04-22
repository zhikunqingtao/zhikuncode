/**
 * ToolCallBlock — 工具调用卡片组件
 *
 * SPEC: §8.2.1 ToolCallBlock, §8.2.2 14 种结果渲染器, §8.2.4D TOOL_RESULT_RENDERERS
 * 每个工具调用显示为可展开/折叠的卡片:
 * - Header: 工具图标 + 工具名 + 状态 + 耗时
 * - Input: 工具输入参数 (JSON, 可折叠)
 * - Result: 工具执行结果 (按工具类型选择渲染器)
 * - Progress: 执行中的进度指示
 */

import React, { useState, useCallback, useMemo } from 'react';
import {
    ChevronRight, Wrench, Loader2,
    CheckCircle2, XCircle, ShieldAlert,
} from 'lucide-react';
import type { ToolCallState } from '@/types';
import CodeBlock from './CodeBlock';
import { TerminalRenderer } from './renderers/TerminalRenderer';
import { DiffRenderer } from './renderers/DiffRenderer';
import { SearchResultRenderer } from './renderers/SearchResultRenderer';
import { FileListRenderer } from './renderers/FileListRenderer';

interface ToolCallBlockProps {
    toolUseId: string;
    toolCall: ToolCallState;
}

const STATUS_CONFIG = {
    pending:           { icon: Loader2, color: 'text-gray-400',   label: 'Pending',   spin: false },
    running:           { icon: Loader2, color: 'text-blue-400',   label: 'Running',   spin: true  },
    completed:         { icon: CheckCircle2, color: 'text-green-400', label: 'Completed', spin: false },
    error:             { icon: XCircle, color: 'text-red-400',    label: 'Error',     spin: false },
    permission_needed: { icon: ShieldAlert, color: 'text-yellow-400', label: 'Permission', spin: false },
} as const;

const ToolCallBlock: React.FC<ToolCallBlockProps> = ({ toolUseId, toolCall }) => {
    const [inputExpanded, setInputExpanded] = useState(false);
    const [resultExpanded, setResultExpanded] = useState(true);

    const statusCfg = STATUS_CONFIG[toolCall.status];
    const StatusIcon = statusCfg.icon;

    const toggleInput = useCallback(() => setInputExpanded(prev => !prev), []);
    const toggleResult = useCallback(() => setResultExpanded(prev => !prev), []);

    const formattedDuration = useMemo(() => {
        if (!toolCall.duration) return null;
        if (toolCall.duration < 1000) return `${toolCall.duration}ms`;
        return `${(toolCall.duration / 1000).toFixed(1)}s`;
    }, [toolCall.duration]);

    const inputStr = useMemo(() => {
        try {
            return JSON.stringify(toolCall.input, null, 2);
        } catch {
            return String(toolCall.input);
        }
    }, [toolCall.input]);

    return (
        <div
            className="tool-call-block my-2 rounded-lg border border-gray-700 bg-gray-900/50 overflow-hidden"
            data-tool-use-id={toolUseId}
        >
            {/* Header */}
            <div className="flex items-center gap-2 px-3 py-2 bg-gray-800/50">
                <Wrench size={14} className="text-gray-500" />
                <span className="font-medium text-sm text-gray-200">
                    {toolCall.toolName}
                </span>
                <StatusIcon
                    size={14}
                    className={`${statusCfg.color} ${statusCfg.spin ? 'animate-spin' : ''}`}
                />
                <span className={`text-xs ${statusCfg.color}`}>
                    {statusCfg.label}
                </span>
                {formattedDuration && (
                    <span className="ml-auto text-xs text-gray-500">
                        {formattedDuration}
                    </span>
                )}
            </div>

            {/* Progress */}
            {toolCall.progress && toolCall.status === 'running' && (
                <div className="px-3 py-1 text-xs text-gray-400 border-t border-gray-700/50 bg-blue-900/10">
                    {toolCall.progress}
                </div>
            )}

            {/* Input (collapsible) */}
            <div className="border-t border-gray-700/50">
                <button
                    onClick={toggleInput}
                    className="flex items-center gap-1.5 w-full px-3 py-1.5 text-xs text-gray-500 hover:text-gray-300 transition-colors"
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
                <div className="border-t border-gray-700/50">
                    <button
                        onClick={toggleResult}
                        className="flex items-center gap-1.5 w-full px-3 py-1.5 text-xs text-gray-500 hover:text-gray-300 transition-colors"
                    >
                        <ChevronRight
                            size={12}
                            className={`transition-transform duration-200 ${resultExpanded ? 'rotate-90' : ''}`}
                        />
                        Result
                        {toolCall.result.isError && (
                            <span className="text-red-400 ml-1">(error)</span>
                        )}
                    </button>
                    {resultExpanded && (
                        <div className="px-3 pb-3">
                            <ToolResultRenderer
                                toolName={toolCall.toolName}
                                content={toolCall.result.content}
                                isError={toolCall.result.isError}
                            />
                        </div>
                    )}
                </div>
            )}
        </div>
    );
};

// ==================== Tool Result Renderer ====================

interface ToolResultRendererProps {
    toolName: string;
    content: string;
    isError: boolean;
}

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
}) => {
    if (isError) {
        return (
            <div className="rounded border border-red-700/50 bg-red-900/20 px-3 py-2 text-sm text-red-300">
                <div className="flex items-center gap-1.5 mb-1 font-medium text-red-400">
                    <XCircle size={14} />
                    Error
                </div>
                <pre className="whitespace-pre-wrap text-xs">{content}</pre>
            </div>
        );
    }

    if (!content?.trim()) {
        return (
            <div className="text-xs text-gray-500 italic">No output</div>
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
