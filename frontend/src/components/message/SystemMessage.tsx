/**
 * SystemMessage — 系统消息渲染组件
 *
 * SPEC: §8.2.4A v1.65.0 M-04: 按 subtype 分发渲染
 * 子类型:
 * - compact_boundary / microcompact_boundary → 分割线+摘要
 * - snip_boundary / snip_marker → 上下文截断标记
 * - local_command → 本地命令结果
 * - default → 普通系统文本
 */

import React from 'react';
import { Info, Scissors, Terminal, Minimize2, Loader2 } from 'lucide-react';
import type { Message } from '@/types';
import { GitDiffPanel } from '@/components/git/GitDiffPanel';
import { GitCommitPanel } from '@/components/git/GitCommitPanel';
import { DiagnosticPanel } from '@/components/doctor/DiagnosticPanel';
import { HelpPanel } from '@/components/help/HelpPanel';
import { CompactResultPanel, type CompactResultData } from '@/components/compact/CompactResultPanel';
import { sendSlashCommand } from '@/api/stompClient';

interface SystemMessageProps {
    message: Extract<Message, { type: 'system' }>;
}

const SystemMessage: React.FC<SystemMessageProps> = ({ message }) => {
    const subtype = message.subtype;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const metadata = (message as any).metadata as Record<string, unknown> | undefined;

    // JSX result — route by metadata.action
    if (subtype === 'jsx_result' && metadata) {
        const action = metadata.action as string;

        if (action === 'gitDiffView') {
            return (
                <div className="system-message px-4 py-2 my-1">
                    <GitDiffPanel data={{
                        staged: metadata.staged as boolean,
                        stat: metadata.stat as string,
                        diff: metadata.diff as string,
                        fileCount: metadata.fileCount as number,
                    }} />
                </div>
            );
        }

        if (action === 'gitCommitPreview') {
            return (
                <div className="system-message px-4 py-2 my-1">
                    <GitCommitPanel
                        data={{
                            status: metadata.status as string,
                            stagedDiff: metadata.stagedDiff as string,
                            detailedDiff: metadata.detailedDiff as string,
                            changedFiles: metadata.changedFiles as string[],
                            fileCount: metadata.fileCount as number,
                        }}
                        onCommit={(msg) => sendSlashCommand('commit', `"${msg}"`)}
                    />
                </div>
            );
        }

        if (action === 'helpCommandList') {
            return (
                <div className="system-message px-4 py-2 my-1">
                    <HelpPanel
                        groups={metadata.groups as Array<{ title: string; titleZh: string; commands: Array<{ name: string; description: string; aliases: string[] }> }>}
                        total={metadata.total as number}
                    />
                </div>
            );
        }

        if (action === 'diagnosticReport') {
            return (
                <div className="system-message px-4 py-2 my-1">
                    <DiagnosticPanel
                        checks={metadata.checks as Array<{ category: string; name: string; value: string; status: 'ok' | 'warn' | 'error'; hint?: string }>}
                        summary={metadata.summary as { ok: number; warn: number; error: number; total: number }}
                    />
                </div>
            );
        }
    }

    // Compact result — /compact command visualization
    if (subtype === 'compact_result' && metadata) {
        return (
            <div className="system-message px-4 py-2 my-1">
                <CompactResultPanel
                    data={metadata as unknown as CompactResultData}
                    displayText={(metadata.displayText as string) ?? ''}
                />
            </div>
        );
    }

    // Compact boundary — context compaction divider
    if (subtype === 'compact_boundary' || subtype === 'microcompact_boundary') {
        return (
            <div className="system-message flex items-center gap-2 px-4 py-2 my-1">
                <div className="flex-1 h-px bg-gray-700" />
                <div className="flex items-center gap-1.5 text-xs text-gray-500">
                    <Minimize2 size={12} />
                    <span>{message.content || 'Context compacted'}</span>
                </div>
                <div className="flex-1 h-px bg-gray-700" />
            </div>
        );
    }

    // Snip boundary — context truncation marker
    if (subtype === 'snip_boundary' || subtype === 'snip_marker') {
        return (
            <div className="system-message flex items-center gap-2 px-4 py-2 my-1">
                <div className="flex-1 h-px bg-yellow-700/50" />
                <div className="flex items-center gap-1.5 text-xs text-yellow-600">
                    <Scissors size={12} />
                    <span>{message.content || 'Context truncated'}</span>
                </div>
                <div className="flex-1 h-px bg-yellow-700/50" />
            </div>
        );
    }

    // Command execution indicator (created by App.tsx before sending WS message)
    if (subtype === 'command') {
        return (
            <div className="system-message px-4 py-1 my-0.5">
                <div className="flex items-center gap-1.5 text-xs text-gray-500">
                    <Terminal size={12} className="text-gray-600" />
                    <span>{message.content}</span>
                </div>
            </div>
        );
    }

    // Command text result (LOCAL commands, non-PROMPT)
    if (subtype === 'command_result') {
        return (
            <div className="system-message px-4 py-2 my-1">
                <div className="flex items-start gap-2 px-3 py-2 rounded-lg bg-gray-800/50 border border-gray-700/50">
                    <Terminal size={14} className="text-gray-500 flex-shrink-0 mt-0.5" />
                    <pre className="text-xs text-gray-400 whitespace-pre-wrap flex-1">
                        {message.content}
                    </pre>
                </div>
            </div>
        );
    }

    // PROMPT command executing indicator
    if (subtype === 'prompt_executing') {
        return (
            <div className="system-message px-4 py-1 my-0.5">
                <div className="flex items-center gap-1.5 text-xs text-blue-400/70">
                    <Loader2 size={12} className="animate-spin" />
                    <span className="text-gray-500">{message.content}</span>
                </div>
            </div>
        );
    }

    // Local command result
    if (subtype === 'local_command') {
        return (
            <div className="system-message px-4 py-2 my-1">
                <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-gray-800/50 border border-gray-700/50">
                    <Terminal size={14} className="text-gray-500 flex-shrink-0" />
                    <pre className="text-xs text-gray-400 whitespace-pre-wrap flex-1">
                        {message.content}
                    </pre>
                </div>
            </div>
        );
    }

    // Error message
    if (message.errorCode) {
        return (
            <div className="system-message px-4 py-2 my-1">
                <div className="flex items-start gap-2 px-3 py-2 rounded-lg bg-red-900/20 border border-red-700/50">
                    <Info size={14} className="text-red-400 flex-shrink-0 mt-0.5" />
                    <div className="flex-1 min-w-0">
                        <div className="text-xs text-red-400 font-medium mb-0.5">
                            Error: {message.errorCode}
                        </div>
                        <div className="text-xs text-red-300 whitespace-pre-wrap">
                            {message.content}
                        </div>
                        {message.retryable && (
                            <div className="text-xs text-red-400/60 mt-1 italic">
                                This error may be retryable
                            </div>
                        )}
                    </div>
                </div>
            </div>
        );
    }

    // Default system text
    return (
        <div className="system-message flex items-center gap-2 px-4 py-2 my-1">
            <div className="flex-1 h-px bg-gray-700/50" />
            <div className="flex items-center gap-1.5 text-xs text-gray-500 max-w-md text-center">
                <Info size={12} className="flex-shrink-0" />
                <span>{message.content}</span>
            </div>
            <div className="flex-1 h-px bg-gray-700/50" />
        </div>
    );
};

export default React.memo(SystemMessage);
