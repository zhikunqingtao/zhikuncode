/**
 * Sidebar — 左侧边栏组件
 * SPEC: §8.6.2
 *
 * 包含: SessionList, TaskPanel, FileTracker
 */

import { useState, useCallback, useEffect, useRef } from 'react';
import { 
    MessageSquare, 
    CheckCircle2, 
    XCircle, 
    Loader2, 
    FileText, 
    Folder,
    ChevronDown,
    ChevronRight,
    Trash2,
    RefreshCw,
    Plus,
    Clock
} from 'lucide-react';
import { useTaskStore } from '@/store/taskStore';
import { useMessageStore } from '@/store/messageStore';
import { useSessionStore } from '@/store/sessionStore';
import { useConfigStore } from '@/store/configStore';
import { sendToServer } from '@/api/stompClient';
import type { TaskState } from '@/types';

type TabType = 'sessions' | 'tasks' | 'files';

interface SidebarProps {
    className?: string;
}

export function Sidebar({ className = '' }: SidebarProps) {
    const [activeTab, setActiveTab] = useState<TabType>('sessions');
    const { tasks, clearTasks } = useTaskStore();

    const tabs: { id: TabType; label: string; icon: typeof MessageSquare }[] = [
        { id: 'sessions', label: '会话', icon: MessageSquare },
        { id: 'tasks', label: '任务', icon: CheckCircle2 },
        { id: 'files', label: '文件', icon: FileText },
    ];

    return (
        <aside className={`w-64 h-full bg-[var(--bg-secondary)] border-r border-[var(--border)] flex flex-col ${className}`}>
            {/* Tab Navigation */}
            <div className="flex border-b border-[var(--border)]">
                {tabs.map((tab) => (
                    <button
                        key={tab.id}
                        onClick={() => setActiveTab(tab.id)}
                        className={`flex-1 py-3 px-2 text-sm font-medium flex items-center justify-center gap-1.5
                            transition-colors ${
                            activeTab === tab.id
                                ? 'text-[var(--text-primary)] border-b-2 border-blue-500 bg-[var(--bg-hover)]'
                                : 'text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)]'
                        }`}
                    >
                        <tab.icon className="w-4 h-4" />
                        <span className="hidden sm:inline">{tab.label}</span>
                    </button>
                ))}
            </div>

            {/* Content */}
            <div className="flex-1 overflow-y-auto">
                {activeTab === 'sessions' && <SessionList />}
                {activeTab === 'tasks' && <TaskPanel tasks={tasks} onClear={clearTasks} />}
                {activeTab === 'files' && <FileTracker />}
            </div>
        </aside>
    );
}

// ═══ Session Summary 类型 ═══
interface SessionSummary {
    id: string;
    title: string | null;
    model: string;
    workingDirectory: string;
    messageCount: number;
    costUsd: number;
    createdAt: string;
    updatedAt: string;
}

// Session List Component — 从后端 API 获取会话列表
function SessionList() {
    const [sessions, setSessions] = useState<SessionSummary[]>([]);
    const [loading, setLoading] = useState(true);
    const [hasMore, setHasMore] = useState(false);
    const [nextCursor, setNextCursor] = useState<string | null>(null);
    const currentSessionId = useSessionStore(s => s.sessionId);
    const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

    // 加载会话列表
    const fetchSessions = useCallback(async (cursor?: string | null) => {
        try {
            const params = new URLSearchParams({ limit: '50' });
            if (cursor) params.set('cursor', cursor);
            const resp = await fetch(`/api/sessions?${params}`);
            if (!resp.ok) return;
            const data = await resp.json();
            if (cursor) {
                setSessions(prev => [...prev, ...data.sessions]);
            } else {
                setSessions(data.sessions);
            }
            setHasMore(data.hasMore);
            setNextCursor(data.nextCursor);
        } catch (e) {
            console.warn('[SessionList] Failed to fetch sessions:', e);
        } finally {
            setLoading(false);
        }
    }, []);

    // 初始加载 + 兆底轮询（60s，防止 WS 推送丢失）
    useEffect(() => {
        fetchSessions();
        pollRef.current = setInterval(() => fetchSessions(), 60000);
        return () => { if (pollRef.current) clearInterval(pollRef.current); };
    }, [fetchSessions]);

    // WebSocket 推送驱动的即时刷新
    useEffect(() => {
        const handler = () => fetchSessions();
        window.addEventListener('session-list-updated', handler);
        return () => window.removeEventListener('session-list-updated', handler);
    }, [fetchSessions]);

    // 切换会话
    const handleSwitchSession = useCallback(async (sessionId: string) => {
        if (sessionId === currentSessionId) return;
        try {
            // 清除当前消息
            useMessageStore.getState().clearMessages();
            // 恢复会话
            useSessionStore.getState().resumeSession(sessionId);
            // 绑定 WebSocket session
            sendToServer('/app/bind-session', { sessionId });
        } catch (e) {
            console.error('[SessionList] Failed to switch session:', e);
        }
    }, [currentSessionId]);

    // 新建会话
    const handleNewSession = useCallback(async () => {
        try {
            useMessageStore.getState().clearMessages();
            const defaultModel = useConfigStore.getState().defaultModel ?? 'qwen3.6-plus';
            await useSessionStore.getState().createSession('.', defaultModel);
            const newSessionId = useSessionStore.getState().sessionId;
            if (newSessionId) {
                sendToServer('/app/bind-session', { sessionId: newSessionId });
            }
            fetchSessions();
        } catch (e) {
            console.error('[SessionList] Failed to create session:', e);
        }
    }, [fetchSessions]);

    // 删除会话
    const handleDeleteSession = useCallback(async (e: React.MouseEvent, sessionId: string) => {
        e.stopPropagation();
        try {
            await fetch(`/api/sessions/${sessionId}`, { method: 'DELETE' });
            setSessions(prev => prev.filter(s => s.id !== sessionId));
            // 如果删除的是当前会话，清除状态
            if (sessionId === currentSessionId) {
                useMessageStore.getState().clearMessages();
                useSessionStore.getState().resumeSession('');
            }
        } catch (e) {
            console.error('[SessionList] Failed to delete session:', e);
        }
    }, [currentSessionId]);

    // 格式化时间
    const formatTime = (isoStr: string) => {
        try {
            const date = new Date(isoStr);
            const now = new Date();
            const diffMs = now.getTime() - date.getTime();
            const diffMin = Math.floor(diffMs / 60000);
            if (diffMin < 1) return '刚刚';
            if (diffMin < 60) return `${diffMin} 分钟前`;
            const diffHour = Math.floor(diffMin / 60);
            if (diffHour < 24) return `${diffHour} 小时前`;
            const diffDay = Math.floor(diffHour / 24);
            if (diffDay < 7) return `${diffDay} 天前`;
            return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' });
        } catch { return ''; }
    };

    if (loading) {
        return (
            <div className="p-4 flex justify-center">
                <Loader2 className="w-5 h-5 animate-spin text-[var(--text-muted)]" />
            </div>
        );
    }

    return (
        <div className="flex flex-col h-full">
            {/* 新建会话按钮 */}
            <div className="p-2 border-b border-[var(--border)]">
                <button
                    onClick={handleNewSession}
                    className="w-full flex items-center gap-2 px-3 py-2 rounded-lg
                        text-sm text-[var(--text-primary)] hover:bg-[var(--bg-hover)]
                        border border-dashed border-[var(--border)] transition-colors"
                >
                    <Plus className="w-4 h-4" />
                    新建会话
                </button>
            </div>

            {/* 会话列表 */}
            <div className="flex-1 overflow-y-auto p-2 space-y-1">
                {sessions.length === 0 ? (
                    <div className="p-4 text-center text-[var(--text-muted)] text-sm">
                        暂无会话记录
                    </div>
                ) : (
                    sessions.map(session => (
                        <div
                            key={session.id}
                            onClick={() => handleSwitchSession(session.id)}
                            className={`group px-3 py-2.5 rounded-lg cursor-pointer transition-colors
                                ${session.id === currentSessionId
                                    ? 'bg-blue-500/10 border border-blue-500/30'
                                    : 'hover:bg-[var(--bg-hover)] border border-transparent'}`}
                        >
                            <div className="flex items-start justify-between gap-1">
                                <div className="flex-1 min-w-0">
                                    <div className="text-sm font-medium text-[var(--text-primary)] truncate">
                                        {session.title || `会话 ${session.id.slice(0, 8)}`}
                                    </div>
                                    <div className="flex items-center gap-2 mt-1">
                                        <span className="text-xs text-[var(--text-muted)] truncate">
                                            {session.model}
                                        </span>
                                        <span className="text-xs text-[var(--text-muted)]">
                                            {session.messageCount} 条消息
                                        </span>
                                    </div>
                                    <div className="flex items-center gap-1 mt-1 text-xs text-[var(--text-muted)]">
                                        <Clock className="w-3 h-3" />
                                        {formatTime(session.updatedAt)}
                                    </div>
                                </div>
                                <button
                                    onClick={(e) => handleDeleteSession(e, session.id)}
                                    className="p-1 rounded opacity-0 group-hover:opacity-100
                                        hover:bg-red-500/10 text-[var(--text-muted)] hover:text-red-500
                                        transition-all"
                                    title="删除会话"
                                >
                                    <Trash2 className="w-3.5 h-3.5" />
                                </button>
                            </div>
                        </div>
                    ))
                )}

                {/* 加载更多 */}
                {hasMore && (
                    <button
                        onClick={() => fetchSessions(nextCursor)}
                        className="w-full py-2 text-xs text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                    >
                        加载更多...
                    </button>
                )}
            </div>
        </div>
    );
}

// Task Panel Component
function TaskPanel({ tasks, onClear }: { tasks: Map<string, TaskState>; onClear: () => void }) {
    const [expandedTasks, setExpandedTasks] = useState<Set<string>>(new Set());

    const toggleTask = useCallback((taskId: string) => {
        setExpandedTasks(prev => {
            const next = new Set(prev);
            if (next.has(taskId)) {
                next.delete(taskId);
            } else {
                next.add(taskId);
            }
            return next;
        });
    }, []);

    const getStatusIcon = (status: string) => {
        switch (status) {
            case 'completed':
                return <CheckCircle2 className="w-4 h-4 text-green-500" />;
            case 'failed':
                return <XCircle className="w-4 h-4 text-red-500" />;
            case 'running':
                return <Loader2 className="w-4 h-4 text-blue-500 animate-spin" />;
            default:
                return <div className="w-4 h-4 rounded-full border-2 border-[var(--border)]" />;
        }
    };

    if (tasks.size === 0) {
        return (
            <div className="p-4 text-center text-[var(--text-muted)] text-sm">
                暂无运行中的任务
            </div>
        );
    }

    return (
        <div className="p-2">
            <div className="flex items-center justify-between mb-2 px-2">
                <span className="text-xs text-[var(--text-muted)]">
                    {tasks.size} 个任务
                </span>
                <button
                    onClick={onClear}
                    className="p-1 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)]"
                    title="清除已完成任务"
                >
                    <Trash2 className="w-3.5 h-3.5" />
                </button>
            </div>
            
            {Array.from(tasks.entries()).map(([taskId, task]) => (
                <div key={taskId} className="mb-1">
                    <button
                        onClick={() => toggleTask(taskId)}
                        className="w-full px-3 py-2 rounded-lg hover:bg-[var(--bg-hover)] flex items-center gap-2"
                    >
                        {expandedTasks.has(taskId) ? (
                            <ChevronDown className="w-4 h-4 text-[var(--text-muted)]" />
                        ) : (
                            <ChevronRight className="w-4 h-4 text-[var(--text-muted)]" />
                        )}
                        {getStatusIcon(task.status)}
                        <span className="flex-1 text-left text-sm text-[var(--text-primary)] truncate">
                            {task.agentName || taskId.slice(0, 8)}
                        </span>
                    </button>
                    
                    {expandedTasks.has(taskId) && (
                        <div className="ml-9 mt-1 space-y-1">
                            {task.progress !== undefined && (
                                <div className="h-1.5 bg-[var(--bg-primary)] rounded-full overflow-hidden">
                                    <div 
                                        className="h-full bg-blue-500 transition-all"
                                        style={{ width: `${(task.progress as number) * 100}%` }}
                                    />
                                </div>
                            )}
                            {task.result !== undefined && task.result !== null && (
                                <div className="text-xs text-[var(--text-secondary)] p-2 bg-[var(--bg-primary)] rounded">
                                    {typeof task.result === 'string' ? task.result : JSON.stringify(task.result).slice(0, 100)}
                                </div>
                            )}
                        </div>
                    )}
                </div>
            ))}
        </div>
    );
}

// File Tracker Component
function FileTracker() {
    // 这里可以从 store 获取被读取/编辑的文件列表
    const recentFiles = [
        { path: 'src/App.tsx', type: 'read', timestamp: Date.now() },
        { path: 'package.json', type: 'edit', timestamp: Date.now() - 3600000 },
    ];

    return (
        <div className="p-2">
            <div className="flex items-center justify-between mb-2 px-2">
                <span className="text-xs text-[var(--text-muted)]">
                    最近访问的文件
                </span>
                <button
                    className="p-1 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)]"
                    title="刷新"
                >
                    <RefreshCw className="w-3.5 h-3.5" />
                </button>
            </div>
            
            {recentFiles.length === 0 ? (
                <div className="p-4 text-center text-[var(--text-muted)] text-sm">
                    暂无文件记录
                </div>
            ) : (
                <div className="space-y-1">
                    {recentFiles.map((file, index) => (
                        <div
                            key={index}
                            className="px-3 py-2 rounded-lg hover:bg-[var(--bg-hover)] flex items-center gap-2 cursor-pointer"
                        >
                            <Folder className="w-4 h-4 text-[var(--text-muted)]" />
                            <div className="flex-1 min-w-0">
                                <div className="text-sm text-[var(--text-primary)] truncate">
                                    {file.path.split('/').pop()}
                                </div>
                                <div className="text-xs text-[var(--text-muted)] truncate">
                                    {file.path}
                                </div>
                            </div>
                            <span className={`text-xs px-1.5 py-0.5 rounded ${
                                file.type === 'edit' 
                                    ? 'bg-yellow-500/20 text-yellow-600' 
                                    : 'bg-blue-500/20 text-blue-600'
                            }`}>
                                {file.type === 'edit' ? '编辑' : '读取'}
                            </span>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
