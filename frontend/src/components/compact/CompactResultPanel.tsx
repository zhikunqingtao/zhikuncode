import React from 'react';

export interface CompactResultData {
    originalMessageCount: number;
    compactedMessageCount: number;
    beforeTokens: number;
    afterTokens: number;
    savedTokens: number;
    compressionRatio: number;
    instruction: string;
}

export const CompactResultPanel: React.FC<{ data: CompactResultData; displayText: string }> = ({ data, displayText: _displayText }) => {
    const savedPct = data.beforeTokens > 0 ? Math.round((data.savedTokens / data.beforeTokens) * 100) : 0;

    return (
        <div className="rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] p-4 space-y-3">
            <div className="flex items-center gap-2">
                <span className="text-lg">🗜️</span>
                <span className="font-semibold text-[var(--text-primary)]">Context Compacted</span>
            </div>
            {/* Token 对比条 */}
            <div className="space-y-1">
                <div className="flex justify-between text-xs text-[var(--text-muted)]">
                    <span>压缩前: {data.beforeTokens.toLocaleString()} tokens</span>
                    <span>压缩后: {data.afterTokens.toLocaleString()} tokens</span>
                </div>
                <div className="h-2 bg-gray-700 rounded-full overflow-hidden flex">
                    <div className="h-full bg-blue-500 rounded-full" style={{ width: `${100 - savedPct}%` }} />
                    <div className="h-full bg-green-500/30 rounded-full" style={{ width: `${savedPct}%` }} />
                </div>
            </div>
            {/* 统计卡片 */}
            <div className="grid grid-cols-3 gap-2">
                <div className="bg-[var(--bg-tertiary)] rounded-md p-2 text-center">
                    <div className="text-lg font-bold text-green-400">{data.savedTokens.toLocaleString()}</div>
                    <div className="text-xs text-[var(--text-muted)]">tokens 释放</div>
                </div>
                <div className="bg-[var(--bg-tertiary)] rounded-md p-2 text-center">
                    <div className="text-lg font-bold text-blue-400">{savedPct}%</div>
                    <div className="text-xs text-[var(--text-muted)]">压缩率</div>
                </div>
                <div className="bg-[var(--bg-tertiary)] rounded-md p-2 text-center">
                    <div className="text-lg font-bold text-[var(--text-primary)]">
                        {data.compactedMessageCount}/{data.originalMessageCount}
                    </div>
                    <div className="text-xs text-[var(--text-muted)]">消息数</div>
                </div>
            </div>
            {data.instruction && (
                <div className="text-xs text-[var(--text-muted)] italic">🎯 Focus: {data.instruction}</div>
            )}
        </div>
    );
};
