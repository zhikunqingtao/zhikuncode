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
    FolderTree,
    ChevronDown,
    ChevronRight,
    Trash2,
    Plus,
    Clock,
    ArrowDownUp,
    GitBranch,
    GitCommitHorizontal,
    BarChart3,
    FileText,
    ExternalLink,
    Workflow
} from 'lucide-react';
import { APISequenceDiagram } from '@/components/visualization/backend/APISequenceDiagram';
import { FileTreePanel } from '@/components/layout/FileTreePanel';
import { AgentDAGChart } from '@/components/visualization/shared/AgentDAGChart';
import { GitTimeline } from '@/components/visualization/shared/GitTimeline';
import { CodeComplexityTreemap } from '@/components/visualization/backend/CodeComplexityTreemap';
import { ChangeImpactGraph } from '@/components/visualization/backend/ChangeImpactGraph';
import { APIContractViewer } from '@/components/visualization/backend/APIContractViewer';
import { CodeDiagramGenerator } from '@/components/visualization/backend/CodeDiagramGenerator';
import { useApiContractStore } from '@/store/apiContractStore';
import { useTaskStore } from '@/store/taskStore';
import { useMessageStore } from '@/store/messageStore';
import { useSessionStore } from '@/store/sessionStore';
import { useConfigStore } from '@/store/configStore';
import { sendToServer } from '@/api/stompClient';
import type { TaskState } from '@/types';

type TabType = 'sessions' | 'tasks' | 'files' | 'sequence' | 'dag' | 'git' | 'complexity' | 'impact' | 'api-docs' | 'diagram';

// ═══ Sidebar 宽度配置 ═══
const MIN_WIDTH = 256;
const MAX_WIDTH = 800;
const DEFAULT_WIDTH = 320;
const STORAGE_KEY = 'sidebar-width';

export interface SidebarProps {
    className?: string;
    /** 是否在 Drawer 模式下（移动端），不显示拖拽手柄 */
    isDrawerMode?: boolean;
    /** 独立窗口模式下的默认 Tab */
    defaultTab?: string;
}

export function Sidebar({ className = '', isDrawerMode = false, defaultTab }: SidebarProps) {
    const [activeTab, setActiveTab] = useState<TabType>(() => {
        if (defaultTab && ['sessions','tasks','files','sequence','dag','git','complexity','impact','api-docs','diagram'].includes(defaultTab)) {
            return defaultTab as TabType;
        }
        return 'sessions';
    });
    const { tasks, clearTasks } = useTaskStore();

    // ── 可拖拽宽度 ──
    // 动态最大宽度：不超过 800px 且不超过视口 70%
    const getMaxWidth = useCallback(() => Math.min(MAX_WIDTH, Math.floor(window.innerWidth * 0.7)), []);

    const [width, setWidth] = useState(() => {
        if (isDrawerMode) return 280;
        const saved = localStorage.getItem(STORAGE_KEY);
        const maxW = Math.min(MAX_WIDTH, Math.floor(window.innerWidth * 0.7));
        return saved ? Math.min(Math.max(Number(saved), MIN_WIDTH), maxW) : DEFAULT_WIDTH;
    });
    const [isDragging, setIsDragging] = useState(false);
    const widthRef = useRef(width);
    widthRef.current = width;

    // 拖拽手柄 — mousedown
    const handleMouseDown = useCallback((e: React.MouseEvent) => {
        e.preventDefault();
        e.stopPropagation();
        const startX = e.clientX;
        const startWidth = widthRef.current;
        const maxW = getMaxWidth();
        setIsDragging(true);

        const handleMouseMove = (ev: MouseEvent) => {
            const newWidth = Math.min(Math.max(startWidth + (ev.clientX - startX), MIN_WIDTH), maxW);
            setWidth(newWidth);
            widthRef.current = newWidth;
        };

        const handleMouseUp = () => {
            document.removeEventListener('mousemove', handleMouseMove);
            document.removeEventListener('mouseup', handleMouseUp);
            document.body.style.cursor = '';
            document.body.style.userSelect = '';
            setIsDragging(false);
            localStorage.setItem(STORAGE_KEY, String(widthRef.current));
        };

        document.addEventListener('mousemove', handleMouseMove);
        document.addEventListener('mouseup', handleMouseUp);
        document.body.style.cursor = 'col-resize';
        document.body.style.userSelect = 'none';
    }, [getMaxWidth]);

    // 双击重置
    const handleDoubleClick = useCallback(() => {
        setWidth(DEFAULT_WIDTH);
        localStorage.setItem(STORAGE_KEY, String(DEFAULT_WIDTH));
    }, []);

    // 新窗口打开
    const handleOpenInNewWindow = useCallback(() => {
        const url = `${window.location.origin}${window.location.pathname}?sidebar=detached&tab=${activeTab}`;
        window.open(url, 'zhikun-sidebar', 'width=600,height=800,menubar=no,toolbar=no');
    }, [activeTab]);

    const tabs: { id: TabType; label: string; icon: typeof MessageSquare }[] = [
        { id: 'sessions', label: '会话', icon: MessageSquare },
        { id: 'tasks', label: '任务', icon: CheckCircle2 },
        { id: 'files', label: '文件', icon: FolderTree },
        { id: 'sequence', label: '序列图', icon: ArrowDownUp },
        { id: 'dag', label: 'DAG', icon: GitBranch },
        { id: 'git', label: 'Git', icon: GitCommitHorizontal },
        { id: 'complexity', label: '复杂度', icon: BarChart3 },
        { id: 'impact', label: '影响分析', icon: GitBranch },
        { id: 'api-docs', label: 'API文档', icon: FileText },
        { id: 'diagram', label: '图表生成', icon: Workflow },
    ];

    // Drawer 模式不使用动态宽度
    const sidebarStyle = isDrawerMode ? undefined : { width: `${width}px` };
    const sidebarWidthClass = isDrawerMode ? 'w-full' : '';

    return (
        <aside
            className={`${sidebarWidthClass} h-full bg-[var(--bg-secondary)] border-r border-[var(--border)] flex flex-col relative z-10 ${className}`}
            style={sidebarStyle}
        >
            {/* Tab Navigation */}
            <div className="flex flex-wrap border-b border-[var(--border)] flex-shrink-0">
                {tabs.map((tab) => (
                    <button
                        key={tab.id}
                        onClick={() => setActiveTab(tab.id)}
                        title={tab.label}
                        className={`py-2 px-2 text-sm font-medium flex items-center justify-center
                            transition-colors ${
                            activeTab === tab.id
                                ? 'text-[var(--text-primary)] border-b-2 border-blue-500 bg-[var(--bg-hover)]'
                                : 'text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)]'
                        }`}
                    >
                        <tab.icon className="w-4 h-4" />
                    </button>
                ))}
                {/* 新窗口打开按钮 */}
                <button
                    onClick={handleOpenInNewWindow}
                    className="p-1.5 ml-auto text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)] rounded transition-colors self-center mr-1"
                    title="在新窗口中打开侧边栏"
                >
                    <ExternalLink className="w-4 h-4" />
                </button>
            </div>

            {/* Content */}
            <div className="flex-1 overflow-y-auto">
                {activeTab === 'sessions' && <SessionList />}
                {activeTab === 'tasks' && <TaskPanel tasks={tasks} onClear={clearTasks} />}
                {activeTab === 'files' && <FileTreePanel sidebarWidth={isDrawerMode ? 280 : width} />}
                {activeTab === 'sequence' && <APISequenceDiagram />}
                {activeTab === 'dag' && (
                    <div className="h-full">
                        <AgentDAGChart />
                    </div>
                )}
                {activeTab === 'git' && (
                    <div className="h-full">
                        <GitTimeline repoPath="." />
                    </div>
                )}
                {activeTab === 'complexity' && (
                    <div className="h-full">
                        <CodeComplexityTreemap />
                    </div>
                )}
                {activeTab === 'impact' && (
                    <div className="h-full">
                        <ChangeImpactGraph />
                    </div>
                )}
                {activeTab === 'api-docs' && (
                    <ApiDocsTab />
                )}
                {activeTab === 'diagram' && (
                    <div className="h-full">
                        <CodeDiagramGenerator />
                    </div>
                )}
            </div>

            {/* 拖拽手柄 — 仅桌面端 */}
            {!isDrawerMode && (
                <div
                    className={`absolute right-0 top-0 bottom-0 w-1 cursor-col-resize
                        transition-colors z-30
                        ${isDragging ? 'bg-blue-500/70' : 'hover:bg-blue-500/50'}`}
                    onMouseDown={handleMouseDown}
                    onDoubleClick={handleDoubleClick}
                >
                    {/* 增大可点击区域到 8px */}
                    <div className="absolute -left-2 -right-2 top-0 bottom-0" />
                </div>
            )}
        </aside>
    );
}

// ═══ API Docs Tab — 自动加载 ═══
function ApiDocsTab() {
    const fetchOpenApiSpec = useApiContractStore(s => s.fetchOpenApiSpec);
    useEffect(() => {
        fetchOpenApiSpec('merged');
    }, [fetchOpenApiSpec]);
    return (
        <div className="h-full">
            <APIContractViewer />
        </div>
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
            const defaultModel = useConfigStore.getState().defaultModel ?? 'qwen3.6-max-preview';
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
        <div className="flex flex-col h-full p-2">
            <div className="flex items-center justify-between mb-2 px-2 flex-shrink-0">
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
            
            <div className="flex-1 overflow-y-auto">
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
        </div>
    );
}
