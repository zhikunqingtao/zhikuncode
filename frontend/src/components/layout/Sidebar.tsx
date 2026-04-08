/**
 * Sidebar — 左侧边栏组件
 * SPEC: §8.6.2
 *
 * 包含: SessionList, TaskPanel, FileTracker
 */

import { useState, useCallback } from 'react';
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
    RefreshCw
} from 'lucide-react';
import { useTaskStore } from '@/store/taskStore';
import { useMessageStore } from '@/store/messageStore';
import type { Message } from '@/types';
import type { TaskState } from '@/types';

type TabType = 'sessions' | 'tasks' | 'files';

interface SidebarProps {
    className?: string;
}

export function Sidebar({ className = '' }: SidebarProps) {
    const [activeTab, setActiveTab] = useState<TabType>('sessions');
    const { messages } = useMessageStore();
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
                {activeTab === 'sessions' && <SessionList messages={messages} />}
                {activeTab === 'tasks' && <TaskPanel tasks={tasks} onClear={clearTasks} />}
                {activeTab === 'files' && <FileTracker />}
            </div>
        </aside>
    );
}

// Session List Component
function SessionList({ messages }: { messages: Message[] }) {
    const userMessages = messages.filter(m => m.type === 'user');
    
    if (userMessages.length === 0) {
        return (
            <div className="p-4 text-center text-[var(--text-muted)] text-sm">
                暂无会话消息
            </div>
        );
    }

    return (
        <div className="p-2 space-y-1">
            {userMessages.map((msg, index) => (
                <div
                    key={msg.uuid}
                    className="px-3 py-2 rounded-lg hover:bg-[var(--bg-hover)] cursor-pointer
                        text-sm text-[var(--text-secondary)] truncate"
                >
                    <span className="text-xs text-[var(--text-muted)] mr-2">#{index + 1}</span>
                    {Array.isArray(msg.content) && msg.content[0]?.type === 'text' 
                        ? (msg.content[0] as {text: string}).text.slice(0, 30)
                        : '...'}
                </div>
            ))}
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
