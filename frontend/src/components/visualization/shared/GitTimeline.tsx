/**
 * GitTimeline — 垂直时间线组件
 * 展示 Git commit 历史，支持展开文件列表、查看 diff 和 blame
 */

import { useState, useEffect, useCallback, useMemo } from 'react';
import {
    Loader2,
    ChevronDown,
    ChevronRight,
    Copy,
    Check,
    FileText,
    GitCommitHorizontal,
    Eye,
    AlertCircle,
} from 'lucide-react';
import { useCodeInsightStore } from '@/store/codeInsightStore';
import type { GitCommit } from '@/store/codeInsightStore';
import { BlameView } from './BlameView';

interface GitTimelineProps {
    repoPath?: string;
}

// ── Commit 类型 → 颜色映射 ──
type CommitColor = { dot: string; border: string; label: string };

const COMMIT_COLORS: Record<string, CommitColor> = {
    feat:     { dot: 'bg-accent2',   border: 'border-l-accent2',   label: 'text-accent2-ink' },
    fix:      { dot: 'bg-warn', border: 'border-l-warn', label: 'text-warn' },
    refactor: { dot: 'bg-accent2', border: 'border-l-accent2', label: 'text-accent2-ink' },
    test:     { dot: 'bg-ok',  border: 'border-l-ok',  label: 'text-ok' },
    docs:     { dot: 'bg-t3',   border: 'border-l-hairline',   label: 'text-t2' },
    default:  { dot: 'bg-t3',   border: 'border-l-hairline',   label: 'text-t2' },
};

const TYPE_PATTERNS: [RegExp, string][] = [
    [/^(feat|feature|add)/i, 'feat'],
    [/^(fix|bugfix|hotfix)/i, 'fix'],
    [/^(refactor|refact)/i, 'refactor'],
    [/^(test|spec)/i, 'test'],
    [/^(docs|chore|ci)/i, 'docs'],
];

function getCommitType(message: string): string {
    for (const [pattern, type] of TYPE_PATTERNS) {
        if (pattern.test(message)) return type;
    }
    return 'default';
}

function getCommitColor(message: string): CommitColor {
    return COMMIT_COLORS[getCommitType(message)] ?? COMMIT_COLORS.default;
}

// ── 相对时间 ──
function relativeTime(isoStr: string): string {
    try {
        const diff = Date.now() - new Date(isoStr).getTime();
        const mins = Math.floor(diff / 60000);
        if (mins < 1) return '刚刚';
        if (mins < 60) return `${mins} 分钟前`;
        const hours = Math.floor(mins / 60);
        if (hours < 24) return `${hours} 小时前`;
        const days = Math.floor(hours / 24);
        if (days < 30) return `${days} 天前`;
        const months = Math.floor(days / 30);
        return `${months} 个月前`;
    } catch {
        return '';
    }
}

// ── 主组件 ──
export function GitTimeline({ repoPath = '.' }: GitTimelineProps) {
    const {
        gitCommits, gitLoading, gitError, gitTotal,
        activeDiff, diffLoading,
        fetchGitLog, fetchMoreGitLog, fetchGitDiff, clearDiff,
    } = useCodeInsightStore();

    const [expandedCommit, setExpandedCommit] = useState<string | null>(null);
    const [copiedSha, setCopiedSha] = useState<string | null>(null);
    const [activeDiffRef, setActiveDiffRef] = useState<string | null>(null);
    const [blameTarget, setBlameTarget] = useState<{ filePath: string; ref: string } | null>(null);

    useEffect(() => {
        fetchGitLog(repoPath, 20);
    }, [repoPath, fetchGitLog]);

    const handleCopySha = useCallback((sha: string, e: React.MouseEvent) => {
        e.stopPropagation();
        navigator.clipboard.writeText(sha).then(() => {
            setCopiedSha(sha);
            setTimeout(() => setCopiedSha(null), 2000);
        });
    }, []);

    const handleToggleCommit = useCallback((sha: string) => {
        setExpandedCommit(prev => prev === sha ? null : sha);
        setActiveDiffRef(null);
        clearDiff();
    }, [clearDiff]);

    const handleViewDiff = useCallback((commit: GitCommit, e: React.MouseEvent) => {
        e.stopPropagation();
        const ref = commit.sha;
        if (activeDiffRef === ref) {
            setActiveDiffRef(null);
            clearDiff();
        } else {
            setActiveDiffRef(ref);
            fetchGitDiff(repoPath, `${ref}~1`, ref);
        }
    }, [repoPath, activeDiffRef, clearDiff, fetchGitDiff]);

    const handleViewBlame = useCallback((filePath: string, ref: string, e: React.MouseEvent) => {
        e.stopPropagation();
        setBlameTarget({ filePath, ref });
    }, []);

    const handleLoadMore = useCallback(() => {
        fetchMoreGitLog(repoPath, 20);
    }, [repoPath, fetchMoreGitLog]);

    const hasMore = useMemo(() => gitCommits.length < gitTotal, [gitCommits.length, gitTotal]);

    // ── Blame 视图 ──
    if (blameTarget) {
        return (
            <div className="flex flex-col h-full">
                <div className="flex items-center gap-2 px-3 py-2 border-b border-[var(--v2-border-hairline)] bg-[var(--v2-bg-sunken)]">
                    <button
                        onClick={() => setBlameTarget(null)}
                        className="panel-control text-[13px] px-2 py-1 rounded bg-[var(--v2-bg-hover)] text-[var(--v2-text-2)] hover:text-[var(--v2-text-1)] transition-colors"
                    >
                        ← 返回时间线
                    </button>
                    <span className="text-[13px] text-[var(--v2-text-2)] truncate">
                        {blameTarget.filePath}
                    </span>
                </div>
                <div className="flex-1 overflow-hidden">
                    <BlameView
                        repoPath={repoPath}
                        filePath={blameTarget.filePath}
                        gitRef={blameTarget.ref}
                    />
                </div>
            </div>
        );
    }

    // ── Loading 骨架屏 ──
    if (gitLoading && gitCommits.length === 0) {
        return (
            <div className="p-4 space-y-4">
                {[1, 2, 3, 4].map(i => (
                    <div key={i} className="flex gap-3 animate-pulse">
                        <div className="w-3 h-3 rounded-full bg-[var(--v2-border-hairline)] mt-1.5 shrink-0" />
                        <div className="flex-1 space-y-2">
                            <div className="h-4 bg-[var(--v2-border-hairline)] rounded w-3/4" />
                            <div className="h-3 bg-[var(--v2-border-hairline)] rounded w-1/2" />
                            <div className="h-3 bg-[var(--v2-border-hairline)] rounded w-1/3" />
                        </div>
                    </div>
                ))}
            </div>
        );
    }

    // ── Error ──
    if (gitError) {
        return (
            <div className="p-4 flex flex-col items-center gap-2 text-[var(--v2-text-2)]">
                <AlertCircle className="w-8 h-8 text-err" />
                <p className="text-sm text-center">{gitError}</p>
                <button
                    onClick={() => fetchGitLog(repoPath, 20)}
                    className="panel-control text-[13px] px-3 py-1.5 rounded bg-[var(--v2-bg-hover)] hover:bg-[var(--v2-bg-surface)] transition-colors"
                >
                    重试
                </button>
            </div>
        );
    }

    // ── 空状态 ──
    if (gitCommits.length === 0) {
        return (
            <div className="p-8 flex flex-col items-center gap-3 text-[var(--v2-text-2)]">
                <GitCommitHorizontal className="w-10 h-10 opacity-40" />
                <p className="text-sm">暂无 commit 记录</p>
                <p className="text-[13px]">请确认仓库路径是否正确</p>
            </div>
        );
    }

    // ── 时间线 ──
    return (
        <div className="flex flex-col h-full">
            <div className="flex-1 overflow-y-auto p-3">
                <div className="relative ml-1.5">
                    {/* 垂直线 */}
                    <div className="absolute left-0 top-0 bottom-0 w-0.5 bg-[var(--v2-border-hairline)]" />

                    {gitCommits.map((commit) => {
                        const color = getCommitColor(commit.message);
                        const isExpanded = expandedCommit === commit.sha;
                        const isDiffActive = activeDiffRef === commit.sha;

                        return (
                            <div key={commit.sha} className="relative pl-6 pb-4">
                                {/* 圆点 */}
                                <div className={`absolute left-0 top-2.5 w-2.5 h-2.5 rounded-full ${color.dot} -translate-x-[4px] ring-3 ring-[var(--v2-bg-sunken)]`} />

                                {/* Commit 卡片 */}
                                <div
                                    className={`rounded-[10px] border border-l-2 ${color.border} bg-[var(--v2-bg-sunken)] hover:bg-[var(--v2-bg-hover)] transition-colors cursor-pointer`}
                                    onClick={() => handleToggleCommit(commit.sha)}
                                >
                                    <div className="p-2.5">
                                        {/* SHA + 操作按钮 */}
                                        <div className="flex items-center gap-1.5 mb-1">
                                            <button
                                                onClick={(e) => handleCopySha(commit.sha, e)}
                                                className="panel-control flex items-center gap-1 text-[13px] font-mono text-[var(--v2-text-2)] hover:text-[var(--v2-text-1)] transition-colors"
                                                title="复制 SHA"
                                            >
                                                {copiedSha === commit.sha
                                                    ? <Check className="w-3 h-3 text-ok" />
                                                    : <Copy className="w-3 h-3" />
                                                }
                                                {commit.sha.slice(0, 7)}
                                            </button>
                                            <button
                                                onClick={(e) => handleViewDiff(commit, e)}
                                                className={`panel-control ml-auto text-[13px] px-1.5 py-0.5 rounded transition-colors ${
                                                    isDiffActive
                                                        ? 'bg-accent2-soft text-accent2-ink'
                                                        : 'text-[var(--v2-text-2)] hover:bg-[var(--v2-bg-surface)]'
                                                }`}
                                                title="查看 Diff"
                                            >
                                                <Eye className="w-3 h-3" />
                                            </button>
                                        </div>

                                        {/* Commit message */}
                                        <p className="text-sm font-medium text-[var(--v2-text-1)] truncate leading-snug">
                                            {commit.message.split('\n')[0]}
                                        </p>

                                        {/* Author + time + file count */}
                                        <div className="flex items-center gap-2 mt-1.5 text-[13px] text-[var(--v2-text-2)]">
                                            <span className="truncate max-w-[100px]">{commit.author}</span>
                                            <span>·</span>
                                            <span>{relativeTime(commit.date)}</span>
                                            {commit.files && commit.files.length > 0 && (
                                                <>
                                                    <span>·</span>
                                                    <span className="flex items-center gap-0.5">
                                                        <FileText className="w-3 h-3" />
                                                        {commit.files.length}
                                                    </span>
                                                </>
                                            )}
                                        </div>

                                        {/* 展开图标 */}
                                        {commit.files && commit.files.length > 0 && (
                                            <div className="flex items-center gap-1 mt-1 text-[13px] text-[var(--v2-text-2)]">
                                                {isExpanded
                                                    ? <ChevronDown className="w-3 h-3" />
                                                    : <ChevronRight className="w-3 h-3" />
                                                }
                                                <span>{isExpanded ? '收起文件列表' : '展开文件列表'}</span>
                                            </div>
                                        )}
                                    </div>

                                    {/* 展开的文件列表 */}
                                    {isExpanded && commit.files && (
                                        <div className="border-t border-[var(--v2-border-hairline)] bg-[var(--v2-bg-surface)]">
                                            {commit.files.map((filePath) => (
                                                <div
                                                    key={filePath}
                                                    className="flex items-center gap-2 px-3 py-1.5 text-[13px] hover:bg-[var(--v2-bg-hover)] transition-colors"
                                                >
                                                    <FileText className="w-3 h-3 text-[var(--v2-text-2)] shrink-0" />
                                                    <span className="font-mono text-[var(--v2-text-2)] truncate flex-1">
                                                        {filePath}
                                                    </span>
                                                    <button
                                                        onClick={(e) => handleViewBlame(filePath, commit.sha, e)}
                                                        className="panel-control text-[var(--v2-text-2)] hover:text-accent2-ink px-1.5 py-0.5 rounded hover:bg-accent2-soft transition-colors shrink-0"
                                                        title="Blame"
                                                    >
                                                        Blame
                                                    </button>
                                                </div>
                                            ))}
                                        </div>
                                    )}

                                    {/* Diff 内容 */}
                                    {isDiffActive && (
                                        <div className="border-t border-[var(--v2-border-hairline)] bg-[var(--v2-bg-surface)]">
                                            {diffLoading ? (
                                                <div className="flex items-center justify-center py-4">
                                                    <Loader2 className="w-4 h-4 animate-spin text-[var(--v2-text-2)]" />
                                                </div>
                                            ) : activeDiff ? (
                                                <div className="max-h-60 overflow-auto">
                                                    <pre className="px-3 py-2 text-[13px] font-mono text-[var(--v2-text-2)] whitespace-pre-wrap break-all">
                                                        {activeDiff.summary}
                                                    </pre>
                                                    {activeDiff.detailed && (
                                                        <div className="border-t border-[var(--v2-border-hairline)]">
                                                            {activeDiff.detailed.split('\n').map((line, i) => (
                                                                <div
                                                                    key={i}
                                                                    className={`px-3 py-0.5 text-[13px] font-mono whitespace-pre ${
                                                                        line.startsWith('+') && !line.startsWith('+++')
                                                                            ? 'bg-oksoft text-ok'
                                                                            : line.startsWith('-') && !line.startsWith('---')
                                                                                ? 'bg-errsoft text-err'
                                                                                : line.startsWith('@@')
                                                                                    ? 'bg-accent2-soft text-accent2-ink'
                                                                                    : 'text-[var(--v2-text-2)]'
                                                                    }`}
                                                                >
                                                                    {line}
                                                                </div>
                                                            ))}
                                                        </div>
                                                    )}
                                                </div>
                                            ) : null}
                                        </div>
                                    )}
                                </div>
                            </div>
                        );
                    })}
                </div>

                {/* 加载更多 */}
                {hasMore && (
                    <div className="flex justify-center py-3">
                        <button
                            onClick={handleLoadMore}
                            disabled={gitLoading}
                            className="panel-control flex items-center gap-1.5 text-[13px] px-4 py-2 rounded-[10px]
                                bg-[var(--v2-bg-hover)] text-[var(--v2-text-2)] hover:text-[var(--v2-text-1)]
                                hover:bg-[var(--v2-bg-surface)] transition-colors disabled:opacity-50"
                        >
                            {gitLoading ? (
                                <Loader2 className="w-3 h-3 animate-spin" />
                            ) : null}
                            加载更多 commit
                        </button>
                    </div>
                )}
            </div>
        </div>
    );
}
