/**
 * Header — 顶部导航栏组件
 * SPEC: §8.6.1
 *
 * 包含: Logo, SessionTitle, ModelSelector, CostIndicator, SettingsButton
 */

import { useCallback } from 'react';
import { Settings, Plus, Menu, Bot, DollarSign } from 'lucide-react';
import { useSessionStore } from '@/store/sessionStore';
import { useCostStore } from '@/store/costStore';
import { useDialogStore } from '@/store/dialogStore';

interface HeaderProps {
    onMenuClick?: () => void;
    showMenuButton?: boolean;
}

export function Header({ onMenuClick, showMenuButton = false }: HeaderProps) {
    const { sessionId, model, setModel } = useSessionStore();
    const { sessionCost, totalCost } = useCostStore();
    const { openDialog } = useDialogStore();

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
                    onChange={(e) => setModel(e.target.value)}
                    className="px-3 py-1.5 text-sm rounded-lg border border-[var(--border)] 
                        bg-[var(--bg-primary)] text-[var(--text-primary)]
                        focus:outline-none focus:ring-2 focus:ring-blue-500"
                >
                    <option value="qwen3.6-plus">Qwen 3.6 Plus</option>
                    <option value="qwen3.6-max">Qwen 3.6 Max</option>
                    <option value="qwen3.6-turbo">Qwen 3.6 Turbo</option>
                    <option value="claude-3-5-sonnet">Claude 3.5 Sonnet</option>
                    <option value="gpt-4">GPT-4</option>
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
