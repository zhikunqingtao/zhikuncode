/**
 * BrowserReplayTimeline — ZhikunCode v1.5 升级项 A MVP。
 *
 * 会话级浏览器语义快照时间线：
 *   · 通过 GET /api/browser/replay/{sessionId} 拉取内存缓存中的快照序列
 *   · 复用 <Drawer> 作为承载容器，右侧滑出
 *   · 左侧列表：时间戳 + URL/title + 节点/交互统计 + 可选缩略图
 *   · 点击某帧展开底部交互元素表 + 顶部 5 层语义树预览
 *
 * 数据模型对齐后端 BrowserSnapshot record：
 *   { snapshotId, sessionId, capturedAt, url, title, selector,
 *     nodeCount, interactive[], tree{}, screenshotBase64 }
 *
 * MVP 约束：
 *   · 不主动轮询（由父组件或用户触发 refresh）；避免 WebSocket 带宽浪费
 *   · 所有数据来自内存缓存；后端 Replay 不落库
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Drawer } from '@/components/layout/Drawer';
import { RefreshCw, Image as ImageIcon, ChevronDown, ChevronRight, Trash2 } from 'lucide-react';

export interface BrowserSnapshot {
    snapshotId: string;
    sessionId: string;
    capturedAt: string;
    url: string | null;
    title: string | null;
    selector: string | null;
    nodeCount: number;
    interactive: Array<{ role: string; name?: string; value?: string; disabled?: boolean }>;
    tree: Record<string, unknown> | null;
    screenshotBase64: string | null;
}

interface BrowserReplayTimelineProps {
    open: boolean;
    onClose: () => void;
    sessionId: string;
    /** 默认 360，覆盖移动端/桌面端布局 */
    width?: number;
}

const BrowserReplayTimeline: React.FC<BrowserReplayTimelineProps> = ({
    open,
    onClose,
    sessionId,
    width = 420,
}) => {
    const [snapshots, setSnapshots] = useState<BrowserSnapshot[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [selectedId, setSelectedId] = useState<string | null>(null);

    const fetchTimeline = useCallback(async () => {
        if (!sessionId) return;
        setLoading(true);
        setError(null);
        try {
            const resp = await fetch(`/api/browser/replay/${encodeURIComponent(sessionId)}`);
            if (!resp.ok) {
                throw new Error(`HTTP ${resp.status}`);
            }
            const data: BrowserSnapshot[] = await resp.json();
            setSnapshots(Array.isArray(data) ? data : []);
        } catch (e) {
            setError(e instanceof Error ? e.message : String(e));
        } finally {
            setLoading(false);
        }
    }, [sessionId]);

    const clearTimeline = useCallback(async () => {
        if (!sessionId) return;
        try {
            await fetch(`/api/browser/replay/${encodeURIComponent(sessionId)}`, {
                method: 'DELETE',
            });
            setSnapshots([]);
            setSelectedId(null);
        } catch (e) {
            setError(e instanceof Error ? e.message : String(e));
        }
    }, [sessionId]);

    // 打开时自动拉取一次
    useEffect(() => {
        if (open) {
            fetchTimeline();
        }
    }, [open, fetchTimeline]);

    const selected = useMemo(
        () => snapshots.find((s) => s.snapshotId === selectedId) ?? null,
        [snapshots, selectedId],
    );

    return (
        <Drawer open={open} onClose={onClose} width={width} side="right">
            <div className="flex items-center justify-between px-4 py-3 border-b border-[var(--border)]">
                <div className="flex flex-col">
                    <span className="text-sm font-semibold text-[var(--text-primary)]">
                        浏览器快照时间线
                    </span>
                    <span className="text-xs text-[var(--text-secondary)]">
                        session: {sessionId.slice(0, 12)}… · {snapshots.length} 帧
                    </span>
                </div>
                <div className="flex items-center gap-2">
                    <button
                        type="button"
                        onClick={fetchTimeline}
                        disabled={loading}
                        className="p-1.5 rounded hover:bg-[var(--bg-secondary)] disabled:opacity-50"
                        title="刷新"
                    >
                        <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
                    </button>
                    <button
                        type="button"
                        onClick={clearTimeline}
                        disabled={loading || snapshots.length === 0}
                        className="p-1.5 rounded hover:bg-[var(--bg-secondary)] disabled:opacity-50"
                        title="清空"
                    >
                        <Trash2 size={14} />
                    </button>
                </div>
            </div>

            {error && (
                <div className="px-4 py-2 text-xs text-red-500 bg-red-500/10 border-b border-red-500/20">
                    {error}
                </div>
            )}

            <div className="flex-1 overflow-y-auto">
                {snapshots.length === 0 && !loading && !error && (
                    <div className="p-6 text-center text-xs text-[var(--text-secondary)]">
                        暂无快照。可在对话中输入
                        <code className="mx-1 px-1 bg-[var(--bg-secondary)] rounded">/browser-snapshot</code>
                        触发一次采集。
                    </div>
                )}
                <ul className="divide-y divide-[var(--border)]">
                    {snapshots.map((snap) => (
                        <SnapshotRow
                            key={snap.snapshotId}
                            snapshot={snap}
                            expanded={snap.snapshotId === selectedId}
                            onToggle={() =>
                                setSelectedId((prev) => (prev === snap.snapshotId ? null : snap.snapshotId))
                            }
                        />
                    ))}
                </ul>
            </div>

            {selected && (
                <div className="border-t border-[var(--border)] max-h-64 overflow-y-auto p-3 bg-[var(--bg-secondary)]/30">
                    <InteractiveList interactive={selected.interactive} />
                </div>
            )}
        </Drawer>
    );
};

interface SnapshotRowProps {
    snapshot: BrowserSnapshot;
    expanded: boolean;
    onToggle: () => void;
}

const SnapshotRow: React.FC<SnapshotRowProps> = ({ snapshot, expanded, onToggle }) => {
    const ts = useMemo(() => {
        try {
            return new Date(snapshot.capturedAt).toLocaleTimeString();
        } catch {
            return snapshot.capturedAt;
        }
    }, [snapshot.capturedAt]);

    return (
        <li className="px-3 py-2 hover:bg-[var(--bg-secondary)]/50 cursor-pointer" onClick={onToggle}>
            <div className="flex items-start gap-2">
                <div className="mt-0.5 text-[var(--text-secondary)]">
                    {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                </div>
                <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 text-xs text-[var(--text-secondary)] mb-1">
                        <span className="font-mono">{ts}</span>
                        <span className="opacity-60">·</span>
                        <span>
                            {snapshot.nodeCount} nodes
                        </span>
                        <span className="opacity-60">·</span>
                        <span>{snapshot.interactive?.length ?? 0} interactive</span>
                        {snapshot.screenshotBase64 && (
                            <ImageIcon size={12} className="opacity-60" />
                        )}
                    </div>
                    <div className="text-sm text-[var(--text-primary)] truncate">
                        {snapshot.title || snapshot.url || '(untitled)'}
                    </div>
                    {snapshot.url && (
                        <div className="text-xs text-[var(--text-secondary)] truncate font-mono">
                            {snapshot.url}
                        </div>
                    )}
                </div>
            </div>
            {expanded && snapshot.screenshotBase64 && (
                <div className="mt-2 ml-6">
                    <img
                        src={`data:image/png;base64,${snapshot.screenshotBase64}`}
                        alt="snapshot"
                        className="max-w-full max-h-40 rounded border border-[var(--border)]"
                    />
                </div>
            )}
        </li>
    );
};

interface InteractiveListProps {
    interactive: BrowserSnapshot['interactive'];
}

const InteractiveList: React.FC<InteractiveListProps> = ({ interactive }) => {
    if (!interactive || interactive.length === 0) {
        return (
            <div className="text-xs text-[var(--text-secondary)]">未抽取到交互元素。</div>
        );
    }
    return (
        <div>
            <div className="text-xs font-semibold text-[var(--text-secondary)] mb-2">
                交互元素 ({interactive.length})
            </div>
            <ul className="space-y-1 text-xs font-mono">
                {interactive.slice(0, 50).map((it, idx) => (
                    <li key={idx} className="flex gap-2">
                        <span className="text-[var(--accent)] min-w-[64px]">{it.role}</span>
                        <span className="text-[var(--text-primary)] truncate flex-1">
                            {it.name || '(no name)'}
                        </span>
                        {it.disabled && (
                            <span className="text-[var(--text-secondary)] opacity-60">disabled</span>
                        )}
                    </li>
                ))}
                {interactive.length > 50 && (
                    <li className="text-[var(--text-secondary)] opacity-60">
                        … 还有 {interactive.length - 50} 项
                    </li>
                )}
            </ul>
        </div>
    );
};

export default BrowserReplayTimeline;
