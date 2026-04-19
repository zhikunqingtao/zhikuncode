import React, { useEffect, useState } from 'react';
import { FileText, Clock } from 'lucide-react';

interface SnapshotEntry {
    messageId: string;
    operation: string;
    timestamp: string;
}

interface DiffStatsResponse {
    filesAdded: number;
    filesModified: number;
    filesDeleted: number;
    changedFiles: string[];
}

export const FileChangesDashboard: React.FC<{ sessionId: string }> = ({ sessionId }) => {
    const [changes, setChanges] = useState<Map<string, SnapshotEntry[]>>(new Map());
    const [selectedFile, setSelectedFile] = useState<string | null>(null);
    const [diffStats, setDiffStats] = useState<DiffStatsResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [activeTab, setActiveTab] = useState<'files' | 'diff'>('files');

    // 对接已有后端 API — GET /api/sessions/{sessionId}/history/snapshots
    useEffect(() => {
        fetch(`/api/sessions/${sessionId}/history/snapshots`)
            .then(r => r.json())
            .then((data: Record<string, Array<{ messageId: string; trackedFiles: string[]; fileCount: number; timestamp: string }>>) => {
                const fileMap = new Map<string, SnapshotEntry[]>();
                Object.values(data).flat().forEach((snap) => {
                    for (const file of snap.trackedFiles || []) {
                        if (!fileMap.has(file)) fileMap.set(file, []);
                        fileMap.get(file)!.push({
                            messageId: snap.messageId,
                            operation: 'edit',
                            timestamp: snap.timestamp,
                        });
                    }
                });
                setChanges(fileMap);
                setLoading(false);
            })
            .catch(() => setLoading(false));
    }, [sessionId]);

    // 对接 GET /api/sessions/{sessionId}/history/diff
    const loadDiffStats = async (filePath: string, fromMessageId: string) => {
        const resp = await fetch(
            `/api/sessions/${sessionId}/history/diff?fromMessageId=${fromMessageId}&toMessageId=current`);
        const data: DiffStatsResponse = await resp.json();
        setDiffStats(data);
        setSelectedFile(filePath);
        setActiveTab('diff');
    };

    if (loading) return <div className="p-4 text-sm text-[var(--text-muted)]">加载中...</div>;

    const isMobile = typeof window !== 'undefined' && window.innerWidth < 768;

    // 文件列表面板
    const fileList = (
        <div className={isMobile ? '' : 'w-64 border-r border-[var(--border)]'}>
            <div className="p-3 border-b border-[var(--border)]">
                <h3 className="text-sm font-semibold flex items-center gap-1.5">
                    <FileText size={14} />
                    变更文件 ({changes.size})
                </h3>
            </div>
            <div className="overflow-y-auto">
                {Array.from(changes.entries()).map(([path, snaps]) => (
                    <button
                        key={path}
                        onClick={() => snaps[0] && loadDiffStats(path, snaps[0].messageId)}
                        className={`w-full text-left px-3 py-2 text-xs flex items-center gap-2
                            hover:bg-[var(--bg-tertiary)] transition-colors
                            ${selectedFile === path ? 'bg-blue-600/10 text-blue-300' : 'text-[var(--text-secondary)]'}`}
                    >
                        <FileText size={14} />
                        <span className="truncate flex-1">{path.split('/').pop()}</span>
                        <span className="text-[var(--text-muted)]">{snaps.length}x</span>
                    </button>
                ))}
            </div>
        </div>
    );

    // Diff 统计视图
    const diffView = (
        <div className="flex-1 overflow-auto p-4">
            {diffStats ? (
                <div className="space-y-3">
                    <h4 className="text-sm font-semibold text-[var(--text-primary)]">
                        {selectedFile} 变更统计
                    </h4>
                    <div className="grid grid-cols-3 gap-2">
                        <div className="bg-green-900/20 rounded-lg p-2 text-center">
                            <div className="text-lg font-bold text-green-400">{diffStats.filesAdded}</div>
                            <div className="text-xs text-[var(--text-muted)]">新增</div>
                        </div>
                        <div className="bg-yellow-900/20 rounded-lg p-2 text-center">
                            <div className="text-lg font-bold text-yellow-400">{diffStats.filesModified}</div>
                            <div className="text-xs text-[var(--text-muted)]">修改</div>
                        </div>
                        <div className="bg-red-900/20 rounded-lg p-2 text-center">
                            <div className="text-lg font-bold text-red-400">{diffStats.filesDeleted}</div>
                            <div className="text-xs text-[var(--text-muted)]">删除</div>
                        </div>
                    </div>
                    {diffStats.changedFiles.length > 0 && (
                        <div className="space-y-1">
                            <h5 className="text-xs font-medium text-[var(--text-secondary)]">变更文件列表</h5>
                            {diffStats.changedFiles.map((f) => (
                                <div key={f} className="text-xs font-mono text-[var(--text-muted)] flex items-center gap-1.5 py-0.5">
                                    <Clock size={12} />
                                    {f}
                                </div>
                            ))}
                        </div>
                    )}
                    <p className="text-xs text-[var(--text-muted)]">
                        注意: 当前 API 仅返回变更统计。行级 diff 内容展示需后续新增专用端点。
                    </p>
                </div>
            ) : (
                <div className="flex items-center justify-center h-full text-[var(--text-muted)]">
                    选择一个文件查看变更统计
                </div>
            )}
        </div>
    );

    // 手机端 Tab 模式
    if (isMobile) {
        return (
            <div className="flex flex-col h-full">
                <div className="flex border-b border-[var(--border)]">
                    <button onClick={() => setActiveTab('files')}
                            className={`flex-1 py-2 text-xs font-medium text-center
                                ${activeTab === 'files' ? 'text-blue-400 border-b-2 border-blue-400' : 'text-[var(--text-muted)]'}`}>
                        文件列表
                    </button>
                    <button onClick={() => setActiveTab('diff')}
                            className={`flex-1 py-2 text-xs font-medium text-center
                                ${activeTab === 'diff' ? 'text-blue-400 border-b-2 border-blue-400' : 'text-[var(--text-muted)]'}`}>
                        Diff 视图
                    </button>
                </div>
                {activeTab === 'files' ? fileList : diffView}
            </div>
        );
    }

    // 桌面端并列布局
    return (
        <div className="flex h-full">
            {fileList}
            {diffView}
        </div>
    );
};

