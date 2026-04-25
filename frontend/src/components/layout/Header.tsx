/**
 * Header — 顶部导航栏组件
 * SPEC: §8.6.1
 *
 * 包含: Logo, SessionTitle, ModelSelector, CostIndicator, SettingsButton
 */

import { useCallback, useEffect, useState } from 'react';
import { Settings, Plus, Menu, Bot, DollarSign, Sun, Moon, Sparkles } from 'lucide-react';
import { useSessionStore } from '@/store/sessionStore';
import { useCostStore } from '@/store/costStore';
import { useDialogStore } from '@/store/dialogStore';
import { useConfigStore } from '@/store/configStore';

interface HeaderProps {
    onMenuClick?: () => void;
    showMenuButton?: boolean;
}

export function Header({ onMenuClick, showMenuButton = false }: HeaderProps) {
    const { sessionId, model, setModel } = useSessionStore();
    const { sessionCost, totalCost } = useCostStore();
    const { openDialog } = useDialogStore();
    const { theme, setTheme } = useConfigStore();

    // 动态加载可用模型列表
    const [availableModels, setAvailableModels] = useState<Array<{ id: string; displayName: string }>>([
        { id: 'qwen3.6-max-preview', displayName: 'Qwen 3.6 Max Preview' },
        { id: 'deepseek-v4-pro', displayName: 'DeepSeek V4 Pro' },
    ]);

    useEffect(() => {
        fetch('/api/models')
            .then(res => res.json())
            .then(data => {
                if (data.models && data.models.length > 0) {
                    setAvailableModels(data.models.map((m: any) => ({
                        id: m.id,
                        displayName: m.displayName || m.id,
                    })));
                    // 如果当前未选择模型，设为后端默认模型
                    if (!model && data.defaultModel) {
                        setModel(data.defaultModel);
                    }
                }
            })
            .catch(err => console.warn('Failed to fetch models:', err));
    }, []);

    // 判断当前是否为深色模式（含 system 跟随）
    const isDark = theme.mode === 'dark' || 
        (theme.mode === 'system' && typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches);
    const isGlass = theme.mode === 'glass';

    const toggleTheme = useCallback(() => {
        // 循环切换: light → dark → glass → light
        if (theme.mode === 'light') {
            setTheme({ mode: 'dark' });
        } else if (theme.mode === 'dark') {
            setTheme({ mode: 'glass' });
        } else {
            setTheme({ mode: 'light' });
        }
    }, [theme.mode, setTheme]);

    const handleNewSession = useCallback(() => {
        window.location.reload();
    }, []);

    const formatCost = (cost: number) => {
        if (cost < 0.01) return '<$0.01';
        return `$${cost.toFixed(2)}`;
    };

    return (
        <header className="h-14 border-b border-[var(--border)] bg-[var(--bg-secondary)] flex items-center px-4 shrink-0">
            {/* Left: Menu Button (mobile) + Logo */}
            <div className="flex items-center gap-3">
                {showMenuButton && (
                    <button
                        onClick={onMenuClick}
                        className="p-2 rounded-lg hover:bg-[var(--bg-hover)] lg:hidden"
                        aria-label="打开侧边栏"
                    >
                        <Menu className="w-5 h-5 text-[var(--text-secondary)]" />
                    </button>
                )}
                <div className="flex items-center gap-2">
                    <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-blue-500 to-purple-600 flex items-center justify-center">
                        <Bot className="w-5 h-5 text-white" />
                    </div>
                    <span className="font-semibold text-[var(--text-primary)] hidden sm:block">
                        AI Assistant
                    </span>
                </div>
            </div>

            {/* Center: Session Title & Model Selector */}
            <div className="flex-1 flex items-center justify-center gap-4">
                <span className="text-sm text-[var(--text-secondary)] truncate max-w-[150px] hidden md:block">
                    {sessionId ? `Session: ${sessionId.slice(0, 8)}...` : 'New Session'}
                </span>
                
                <select
                    value={model || ''}
                    onChange={(e) => {
                        const newModel = e.target.value;
                        setModel(newModel);
                        // 同步更新 configStore 默认模型并持久化到后端数据库
                        useConfigStore.getState().saveConfig({ defaultModel: newModel });
                    }}
                    className="px-3 py-1.5 text-sm rounded-lg border border-[var(--border)] 
                        bg-[var(--bg-primary)] text-[var(--text-primary)]
                        focus:outline-none focus:ring-2 focus:ring-blue-500"
                >
                    {availableModels.map(m => (
                        <option key={m.id} value={m.id}>{m.displayName}</option>
                    ))}
                </select>
            </div>

            {/* Right: Cost + New Session + Settings */}
            <div className="flex items-center gap-2">
                {/* Cost Indicator */}
                <div className="hidden md:flex items-center gap-1 px-3 py-1.5 rounded-lg bg-[var(--bg-primary)] border border-[var(--border)]">
                    <DollarSign className="w-4 h-4 text-green-500" />
                    <span className="text-sm text-[var(--text-secondary)]">
                        {formatCost(sessionCost)}
                    </span>
                    <span className="text-xs text-[var(--text-muted)]">
                        / {formatCost(totalCost)}
                    </span>
                </div>

                {/* Theme Toggle */}
                <button
                    onClick={toggleTheme}
                    className="p-2 rounded-lg hover:bg-[var(--bg-hover)] text-[var(--text-secondary)] transition-colors"
                    title={isGlass ? '切换到浅色模式' : isDark ? '切换到液态玻璃模式' : '切换到深色模式'}
                    aria-label={isGlass ? '切换到浅色模式' : isDark ? '切换到液态玻璃模式' : '切换到深色模式'}
                >
                    {isGlass ? <Sparkles className="w-5 h-5" /> : isDark ? <Sun className="w-5 h-5" /> : <Moon className="w-5 h-5" />}
                </button>

                {/* New Session */}
                <button
                    onClick={handleNewSession}
                    className="p-2 rounded-lg hover:bg-[var(--bg-hover)] text-[var(--text-secondary)]"
                    title="新建会话"
                >
                    <Plus className="w-5 h-5" />
                </button>

                {/* Settings */}
                <button
                    onClick={() => openDialog('settings')}
                    className="p-2 rounded-lg hover:bg-[var(--bg-hover)] text-[var(--text-secondary)]"
                    title="设置"
                >
                    <Settings className="w-5 h-5" />
                </button>
            </div>
        </header>
    );
}
