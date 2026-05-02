/**
 * BlameView — Git Blame 视图组件
 * 两栏布局：左栏 blame 信息 + 右栏代码内容，同步滚动
 */

import { useEffect, useRef, useCallback } from 'react';
import { Loader2, AlertCircle } from 'lucide-react';
import { useCodeInsightStore } from '@/store/codeInsightStore';

interface BlameViewProps {
    repoPath: string;
    filePath: string;
    gitRef?: string;
}

// ── 交替背景色组（用于相同 commit 分组） ──
const GROUP_COLORS = [
    'bg-blue-500/5',
    'bg-purple-500/5',
    'bg-green-500/5',
    'bg-orange-500/5',
    'bg-pink-500/5',
    'bg-cyan-500/5',
];

function relativeTime(isoStr: string): string {
    try {
        const diff = Date.now() - new Date(isoStr).getTime();
        const mins = Math.floor(diff / 60000);
        if (mins < 1) return '刚刚';
        if (mins < 60) return `${mins}m`;
        const hours = Math.floor(mins / 60);
        if (hours < 24) return `${hours}h`;
        const days = Math.floor(hours / 24);
        if (days < 30) return `${days}d`;
        const months = Math.floor(days / 30);
        return `${months}mo`;
    } catch {
        return '';
    }
}

export function BlameView({ repoPath, filePath, gitRef }: BlameViewProps) {
    const { activeBlame, blameLoading, fetchGitBlame } = useCodeInsightStore();
    const leftPanelRef = useRef<HTMLDivElement>(null);
    const rightPanelRef = useRef<HTMLDivElement>(null);
    const isSyncing = useRef(false);

    useEffect(() => {
        fetchGitBlame(repoPath, filePath, gitRef);
    }, [repoPath, filePath, gitRef, fetchGitBlame]);

    // ── 同步滚动 ──
    const handleScroll = useCallback((source: 'left' | 'right') => {
        if (isSyncing.current) return;
        isSyncing.current = true;

        const src = source === 'left' ? leftPanelRef.current : rightPanelRef.current;
        const dst = source === 'left' ? rightPanelRef.current : leftPanelRef.current;
        if (src && dst) {
            dst.scrollTop = src.scrollTop;
        }

        requestAnimationFrame(() => { isSyncing.current = false; });
    }, []);

    // ── Loading ──
    if (blameLoading) {
        return (
            <div className="flex items-center justify-center h-full">
                <Loader2 className="w-5 h-5 animate-spin text-[var(--text-muted)]" />
            </div>
        );
    }

    // ── Error / Empty ──
    if (!activeBlame || activeBlame.lines.length === 0) {
        return (
            <div className="flex flex-col items-center justify-center h-full gap-2 text-[var(--text-muted)]">
                <AlertCircle className="w-6 h-6 opacity-50" />
                <p className="text-sm">无法加载 blame 数据</p>
            </div>
        );
    }

    // ── 构建 commit 分组色（相同 SHA 连续行用同色） ──
    const shaColorMap = new Map<string, string>();
    let colorIndex = 0;
    let prevSha = '';
    for (const line of activeBlame.lines) {
        if (line.sha !== prevSha) {
            if (!shaColorMap.has(line.sha)) {
                shaColorMap.set(line.sha, GROUP_COLORS[colorIndex % GROUP_COLORS.length]);
                colorIndex++;
            }
            prevSha = line.sha;
        }
    }

    // ── 判断 blame 行是否为 group 的第一行 ──
    const isGroupStart = activeBlame.lines.map((line, i) =>
        i === 0 || activeBlame.lines[i - 1].sha !== line.sha
    );

    return (
        <div className="flex flex-col h-full">
            {/* 文件路径栏 */}
            <div className="flex items-center px-3 py-1.5 border-b border-[var(--border)] bg-[var(--bg-secondary)]">
                <span className="text-xs font-mono text-[var(--text-secondary)] truncate">
                    {activeBlame.file_path}
                </span>
                <span className="ml-auto text-xs text-[var(--text-muted)]">
                    {activeBlame.total_lines} 行
                </span>
            </div>

            {/* 两栏布局 */}
            <div className="flex flex-1 overflow-hidden">
                {/* 左栏: Blame 信息 */}
                <div
                    ref={leftPanelRef}
                    onScroll={() => handleScroll('left')}
                    className="w-[220px] shrink-0 overflow-y-auto border-r border-[var(--border)] bg-[var(--bg-secondary)] scrollbar-thin"
                >
                    {activeBlame.lines.map((line, i) => {
                        const bgColor = shaColorMap.get(line.sha) ?? '';
                        const showInfo = isGroupStart[i];

                        return (
                            <div
                                key={line.line_no}
                                className={`flex items-center h-[20px] px-2 text-[11px] leading-[20px] ${bgColor} border-b border-[var(--border)]/30`}
                            >
                                {showInfo ? (
                                    <>
                                        <span className="w-[80px] shrink-0 truncate text-[var(--text-muted)]" title={line.author}>
                                            {line.author}
                                        </span>
                                        <span className="w-[36px] shrink-0 text-center text-[var(--text-muted)]">
                                            {relativeTime(line.date)}
                                        </span>
                                        <span
                                            className="ml-auto shrink-0 font-mono text-[var(--text-muted)] cursor-pointer hover:text-blue-400 transition-colors"
                                            title={`Commit ${line.sha}`}
                                        >
                                            {line.sha.slice(0, 7)}
                                        </span>
                                    </>
                                ) : (
                                    <span className="w-full" />
                                )}
                            </div>
                        );
                    })}
                </div>

                {/* 右栏: 代码内容 */}
                <div
                    ref={rightPanelRef}
                    onScroll={() => handleScroll('right')}
                    className="flex-1 overflow-auto scrollbar-thin"
                >
                    {activeBlame.lines.map((line) => {
                        const bgColor = shaColorMap.get(line.sha) ?? '';

                        return (
                            <div
                                key={line.line_no}
                                className={`flex h-[20px] leading-[20px] font-mono text-xs ${bgColor} border-b border-[var(--border)]/30`}
                            >
                                {/* 行号 */}
                                <span className="w-[40px] shrink-0 text-right pr-3 text-[var(--text-muted)] select-none text-[11px]">
                                    {line.line_no}
                                </span>
                                {/* 代码 */}
                                <span className="text-[var(--text-primary)] whitespace-pre pr-4">
                                    {line.content}
                                </span>
                            </div>
                        );
                    })}
                </div>
            </div>
        </div>
    );
}
