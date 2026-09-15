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
        <div className="rounded-[14px] border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-sunken)] p-4 space-y-3">
            <div className="flex items-center gap-2">
                <span className="text-lg">🗜️</span>
                <span className="font-semibold text-[var(--v2-text-1)]">Context Compacted</span>
            </div>
            {/* Token 对比条 */}
            <div className="space-y-1">
                <div className="flex justify-between text-[13px] text-[var(--v2-text-2)]">
                    <span>压缩前: {data.beforeTokens.toLocaleString()} tokens</span>
                    <span>压缩后: {data.afterTokens.toLocaleString()} tokens</span>
                </div>
                <div className="h-2 bg-gray-700 rounded-full overflow-hidden flex">
                    <div className="h-full bg-blue-500 rounded-full" style={{ width: `${100 - savedPct}%` }} />
                    <div className="h-full bg-oksoft rounded-full" style={{ width: `${savedPct}%` }} />
                </div>
            </div>
            {/* 统计卡片 */}
            <div className="grid grid-cols-3 gap-2">
                <div className="bg-[var(--bg-tertiary)] rounded-md p-2 text-center">
                    <div className="text-lg font-bold text-ok">{data.savedTokens.toLocaleString()}</div>
                    <div className="text-[13px] text-[var(--v2-text-2)]">tokens 释放</div>
                </div>
                <div className="bg-[var(--bg-tertiary)] rounded-md p-2 text-center">
                    <div className="text-lg font-bold text-accent2-ink">{savedPct}%</div>
                    <div className="text-[13px] text-[var(--v2-text-2)]">压缩率</div>
                </div>
                <div className="bg-[var(--bg-tertiary)] rounded-md p-2 text-center">
                    <div className="text-lg font-bold text-[var(--v2-text-1)]">
                        {data.compactedMessageCount}/{data.originalMessageCount}
                    </div>
                    <div className="text-[13px] text-[var(--v2-text-2)]">消息数</div>
                </div>
            </div>
            {data.instruction && (
                <div className="text-[13px] text-[var(--v2-text-2)] italic">🎯 Focus: {data.instruction}</div>
            )}
        </div>
    );
};
