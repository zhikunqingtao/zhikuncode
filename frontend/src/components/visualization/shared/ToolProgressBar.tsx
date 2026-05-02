/**
 * ToolProgressBar — 工具执行进度条组件
 *
 * 解析后端推送的 progress 字符串，显示可视化进度条 + ETA。
 * 支持三种进度格式：
 * - "30/100 files" → 确定进度 (30%)
 * - "Processing 90%" → 确定进度 (90%)
 * - 其他文本 → 不确定进度 (shimmer 动画)
 */

import React, { useMemo } from 'react';

interface ToolProgressBarProps {
    progress: string;
    startTime?: number;
}

interface ParsedProgress {
    percent: number | null; // null = indeterminate
    raw: string;
}

function parseProgress(progress: string): ParsedProgress {
    // 匹配 "30 / 100" 或 "30/100" 格式
    const fractionMatch = progress.match(/(\d+)\s*\/\s*(\d+)/);
    if (fractionMatch) {
        const current = parseInt(fractionMatch[1], 10);
        const total = parseInt(fractionMatch[2], 10);
        if (total > 0) {
            return { percent: Math.min(100, Math.round((current / total) * 100)), raw: progress };
        }
    }
    // 匹配 "90%" 格式
    const percentMatch = progress.match(/(\d+)\s*%/);
    if (percentMatch) {
        return { percent: Math.min(100, parseInt(percentMatch[1], 10)), raw: progress };
    }
    // 不确定进度
    return { percent: null, raw: progress };
}

function formatEta(remainingMs: number): string {
    if (!isFinite(remainingMs) || remainingMs < 0) return '';
    const totalSec = Math.ceil(remainingMs / 1000);
    if (totalSec < 60) return `预计还需 ${totalSec}s`;
    const min = Math.floor(totalSec / 60);
    const sec = totalSec % 60;
    return `预计还需 ${min}m ${sec}s`;
}

const ToolProgressBar: React.FC<ToolProgressBarProps> = ({ progress, startTime }) => {
    const parsed = useMemo(() => parseProgress(progress), [progress]);

    const eta = useMemo(() => {
        if (parsed.percent == null || parsed.percent <= 0 || !startTime) return '';
        const elapsed = Date.now() - startTime;
        if (elapsed <= 0) return '';
        const rate = parsed.percent / elapsed;
        const remaining = (100 - parsed.percent) / rate;
        return formatEta(remaining);
    }, [parsed.percent, startTime]);

    const barColor =
        parsed.percent != null && parsed.percent > 80
            ? 'bg-green-500'
            : 'bg-blue-500';

    return (
        <div className="flex flex-col gap-0.5" style={{ maxHeight: 40 }}>
            {/* 进度文本 */}
            <div className="text-xs text-gray-400 truncate leading-tight">
                {parsed.raw}
            </div>

            {/* 进度条 */}
            <div className="h-1.5 w-full rounded-full bg-gray-700/60 overflow-hidden">
                {parsed.percent != null ? (
                    <div
                        className={`h-full rounded-full ${barColor} transition-all duration-300 ease-out`}
                        style={{ width: `${parsed.percent}%` }}
                    />
                ) : (
                    <div className="h-full w-full rounded-full bg-gradient-to-r from-transparent via-blue-500/60 to-transparent animate-shimmer" />
                )}
            </div>

            {/* 百分比 + ETA / 处理中 */}
            <div className="text-[10px] text-gray-500 leading-tight">
                {parsed.percent != null ? (
                    <span>{parsed.percent}%{eta ? ` · ${eta}` : ''}</span>
                ) : (
                    <span>处理中...</span>
                )}
            </div>
        </div>
    );
};

export default React.memo(ToolProgressBar);
