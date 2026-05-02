/**
 * APISequenceDiagram — API 调用序列图面板组件
 *
 * 从 messageStore 提取工具调用数据，构建 Mermaid sequenceDiagram，
 * 复用 MermaidBlock 渲染。支持工具类型过滤和详情查看。
 */

import React, { useState, useMemo, useCallback } from 'react';
import { RefreshCw, Filter, ArrowDownUp, X, ChevronDown } from 'lucide-react';
import { useMessageStore } from '@/store/messageStore';
import {
    extractToolCalls,
    buildSequenceDiagram,
    getUniqueToolNames,
    type ToolCallRecord,
} from '@/utils/sequence-diagram-builder';
import MermaidBlock from '@/components/visualization/shared/MermaidBlock';

/** 工具过滤器下拉组件 */
const ToolFilterDropdown: React.FC<{
    toolNames: string[];
    selected: string[];
    onChange: (selected: string[]) => void;
}> = ({ toolNames, selected, onChange }) => {
    const [open, setOpen] = useState(false);

    const toggle = useCallback((name: string) => {
        onChange(
            selected.includes(name)
                ? selected.filter(n => n !== name)
                : [...selected, name]
        );
    }, [selected, onChange]);

    const clearAll = useCallback(() => onChange([]), [onChange]);

    return (
        <div className="relative">
            <button
                onClick={() => setOpen(o => !o)}
                className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-xs
                    border border-[var(--border)] bg-[var(--bg-primary)]
                    hover:bg-[var(--bg-hover)] text-[var(--text-secondary)]
                    transition-colors"
            >
                <Filter size={13} />
                <span>过滤工具</span>
                {selected.length > 0 && (
                    <span className="px-1.5 py-0.5 rounded-full bg-blue-500/20 text-blue-500 text-[10px] font-medium">
                        {selected.length}
                    </span>
                )}
                <ChevronDown size={12} className={`transition-transform ${open ? 'rotate-180' : ''}`} />
            </button>

            {open && (
                <>
                    <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} />
                    <div className="absolute top-full left-0 mt-1 z-20 min-w-[200px] max-h-[240px] overflow-y-auto
                        rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] shadow-lg">
                        {/* Header */}
                        <div className="flex items-center justify-between px-3 py-2 border-b border-[var(--border)]">
                            <span className="text-xs text-[var(--text-muted)]">选择工具类型</span>
                            {selected.length > 0 && (
                                <button
                                    onClick={clearAll}
                                    className="text-[10px] text-blue-500 hover:underline"
                                >
                                    清除全部
                                </button>
                            )}
                        </div>
                        {/* Options */}
                        {toolNames.map(name => (
                            <label
                                key={name}
                                className="flex items-center gap-2 px-3 py-1.5 hover:bg-[var(--bg-hover)]
                                    cursor-pointer text-xs text-[var(--text-primary)]"
                            >
                                <input
                                    type="checkbox"
                                    checked={selected.includes(name)}
                                    onChange={() => toggle(name)}
                                    className="rounded border-gray-400 text-blue-500 focus:ring-blue-500"
                                />
                                {name}
                            </label>
                        ))}
                        {toolNames.length === 0 && (
                            <div className="px-3 py-2 text-xs text-[var(--text-muted)]">
                                无可用工具
                            </div>
                        )}
                    </div>
                </>
            )}
        </div>
    );
};

/** 工具调用详情弹出 */
const ToolCallDetail: React.FC<{
    record: ToolCallRecord;
    onClose: () => void;
}> = ({ record, onClose }) => {
    return (
        <div className="border-t border-[var(--border)] bg-[var(--bg-primary)]">
            <div className="flex items-center justify-between px-3 py-2 border-b border-[var(--border)]">
                <span className="text-xs font-medium text-[var(--text-primary)]">
                    {record.toolName} 详情
                </span>
                <button
                    onClick={onClose}
                    className="p-1 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)]"
                >
                    <X size={14} />
                </button>
            </div>
            <div className="p-3 space-y-2 max-h-[200px] overflow-y-auto">
                <div>
                    <span className="text-[10px] uppercase tracking-wider text-[var(--text-muted)]">Input</span>
                    <pre className="mt-1 p-2 rounded bg-[var(--bg-secondary)] text-xs text-[var(--text-secondary)] overflow-x-auto whitespace-pre-wrap font-mono">
                        {JSON.stringify(record.input, null, 2)}
                    </pre>
                </div>
                {record.result !== undefined && (
                    <div>
                        <span className="text-[10px] uppercase tracking-wider text-[var(--text-muted)]">
                            Result {record.isError && <span className="text-red-400">(Error)</span>}
                        </span>
                        <pre className="mt-1 p-2 rounded bg-[var(--bg-secondary)] text-xs text-[var(--text-secondary)] overflow-x-auto whitespace-pre-wrap font-mono max-h-[120px]">
                            {record.result}
                        </pre>
                    </div>
                )}
            </div>
        </div>
    );
};

/** API 序列图面板 */
export const APISequenceDiagram: React.FC = () => {
    const messages = useMessageStore(s => s.messages);
    const [selectedTools, setSelectedTools] = useState<string[]>([]);
    const [selectedRecord, setSelectedRecord] = useState<ToolCallRecord | null>(null);
    const [refreshKey, setRefreshKey] = useState(0);

    // 提取工具调用记录
    const toolCalls = useMemo(
        () => extractToolCalls(messages),
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [messages, refreshKey]
    );

    // 可用工具名列表
    const toolNames = useMemo(() => getUniqueToolNames(toolCalls), [toolCalls]);

    // 生成 Mermaid 语法
    const diagramCode = useMemo(() => {
        if (toolCalls.length === 0) return '';
        return buildSequenceDiagram(toolCalls, {
            toolFilter: selectedTools.length > 0 ? selectedTools : undefined,
        });
    }, [toolCalls, selectedTools]);

    const handleRefresh = useCallback(() => {
        setRefreshKey(k => k + 1);
    }, []);

    const handleSelectRecord = useCallback((record: ToolCallRecord) => {
        setSelectedRecord(prev => prev?.toolUseId === record.toolUseId ? null : record);
    }, []);

    // 空状态
    if (toolCalls.length === 0) {
        return (
            <div className="flex flex-col items-center justify-center py-12 px-4 text-center">
                <ArrowDownUp className="w-10 h-10 text-[var(--text-muted)] mb-3 opacity-40" />
                <p className="text-sm text-[var(--text-muted)]">当前会话暂无工具调用</p>
                <p className="text-xs text-[var(--text-muted)] mt-1 opacity-60">
                    发送消息后，工具调用序列图将在此显示
                </p>
            </div>
        );
    }

    return (
        <div className="flex flex-col h-full">
            {/* 工具栏 */}
            <div className="flex items-center gap-2 px-3 py-2 border-b border-[var(--border)] shrink-0">
                <ToolFilterDropdown
                    toolNames={toolNames}
                    selected={selectedTools}
                    onChange={setSelectedTools}
                />
                <button
                    onClick={handleRefresh}
                    className="p-1.5 rounded-md border border-[var(--border)]
                        hover:bg-[var(--bg-hover)] text-[var(--text-secondary)] transition-colors"
                    title="刷新"
                >
                    <RefreshCw size={13} />
                </button>
                <span className="ml-auto text-[10px] text-[var(--text-muted)]">
                    {toolCalls.length} 次调用
                </span>
            </div>

            {/* 序列图 */}
            <div className="flex-1 overflow-y-auto p-3">
                {diagramCode ? (
                    <MermaidBlock code={diagramCode} />
                ) : (
                    <div className="flex items-center justify-center py-8 text-sm text-[var(--text-muted)]">
                        过滤后无匹配的工具调用
                    </div>
                )}
            </div>

            {/* 调用记录列表（可点击查看详情） */}
            <div className="border-t border-[var(--border)] max-h-[180px] overflow-y-auto shrink-0">
                <div className="px-3 py-1.5 text-[10px] uppercase tracking-wider text-[var(--text-muted)] border-b border-[var(--border)] bg-[var(--bg-secondary)]">
                    调用记录
                </div>
                {toolCalls
                    .filter(tc => selectedTools.length === 0 || selectedTools.includes(tc.toolName))
                    .map(tc => (
                        <button
                            key={tc.toolUseId}
                            onClick={() => handleSelectRecord(tc)}
                            className={`w-full flex items-center gap-2 px-3 py-1.5 text-left text-xs
                                hover:bg-[var(--bg-hover)] transition-colors border-b border-[var(--border)]/50
                                ${selectedRecord?.toolUseId === tc.toolUseId ? 'bg-blue-500/5' : ''}`}
                        >
                            <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${tc.isError ? 'bg-red-400' : 'bg-green-400'}`} />
                            <span className="font-medium text-[var(--text-primary)] truncate">{tc.toolName}</span>
                            <span className="text-[var(--text-muted)] truncate flex-1">
                                {Object.keys(tc.input).slice(0, 2).join(', ')}
                            </span>
                        </button>
                    ))}
            </div>

            {/* 详情面板 */}
            {selectedRecord && (
                <ToolCallDetail
                    record={selectedRecord}
                    onClose={() => setSelectedRecord(null)}
                />
            )}
        </div>
    );
};

export default APISequenceDiagram;
