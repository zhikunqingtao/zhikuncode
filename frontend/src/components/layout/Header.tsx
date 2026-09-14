/**
 * Header — 顶部导航栏组件
 * SPEC: §8.6.1
 *
 * 包含: Logo, SessionTitle, ModelSelector, CostIndicator, SettingsButton
 */

import { useCallback, useEffect } from 'react';
import { Settings, Plus, Menu, Bot, DollarSign, Sun, Moon, Sparkles } from 'lucide-react';
import { useSessionStore } from '@/store/sessionStore';
import { useCostStore } from '@/store/costStore';
import { useDialogStore } from '@/store/dialogStore';
import { useConfigStore } from '@/store/configStore';
import { useModelStore } from '@/store/modelStore';
import { useBridgeStore } from '@/store/bridgeStore';
import { sendSetModel } from '@/api/stompClient';
import { dispatchNewAuthorizedSessionRequest } from '@/services/authorizedSession';
import { useWorkbenchViewStore } from '@/store/workbenchViewStore';
import { WorkbenchViewSwitch } from '@/components/workbench/WorkbenchViewSwitch';
import { McpIcon } from '@/components/mcp/McpIcon';
import { Kbd } from '@/components/ui';

/** §7.4 头部按钮共性：hover/active/焦点环（ring-accent2-ring） */
const HEADER_BUTTON_CLASS =
    'p-2 rounded-lg hover:bg-hover2 active:scale-95 transition-interactive duration-fast text-t2 ' +
    'focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring';

interface HeaderProps {
    onMenuClick?: () => void;
    showMenuButton?: boolean;
}

export function Header({ onMenuClick, showMenuButton = false }: HeaderProps) {
    const { sessionId, model, setModel } = useSessionStore();
    const { sessionCost, totalCost } = useCostStore();
    const { bridgeStatus } = useBridgeStore();
    const { openDialog } = useDialogStore();
    const { theme, setTheme } = useConfigStore();
    const workbenchEnabled = useWorkbenchViewStore(s => s.enabled);
    const viewMode = useWorkbenchViewStore(s => s.viewMode);
    const simpleMode = workbenchEnabled && viewMode === 'simple';

    // 动态加载可用模型列表（统一从 modelStore 缓存读取，附带 supportsImages / maxImages 能力）
    const {
        models: availableModels,
        defaultModel,
        loaded,
        loading: modelsLoading,
        error: modelsError,
        fetchModels,
    } = useModelStore();

    useEffect(() => {
        if (loaded) return;
        void fetchModels();
    }, [loaded, fetchModels]);

    useEffect(() => {
        // 模型列表加载完成且当前未选模型时，使用后端默认模型
        if (loaded && !model && defaultModel) {
            setModel(defaultModel);
        }
    }, [loaded, defaultModel, model, setModel]);

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
        dispatchNewAuthorizedSessionRequest();
    }, []);

    /** §7.4 ⌘K 命令钮：与 Ctrl+K 全局命令面板同一入口——
     *  向 window 派发合成 keydown，命中 usePromptState 既有全局监听
     * （运行中/压缩中的关闭语义与真实按键完全一致），不新写面板、不提升状态。 */
    const openCommandPalette = useCallback(() => {
        window.dispatchEvent(new KeyboardEvent('keydown', {
            key: 'k',
            ctrlKey: true,
            bubbles: true,
            cancelable: true,
        }));
    }, []);

    const formatCost = (cost: number) => {
        if (cost < 0.01) return '<$0.01';
        return `$${cost.toFixed(2)}`;
    };

    return (
        <header className="h-14 border-b border-hairline bg-surface2 flex items-center px-2 md:px-4 shrink-0">
            {/* Left: Menu Button (mobile) + Logo */}
            <div className="flex items-center gap-3">
                {showMenuButton && (
                    <button
                        onClick={onMenuClick}
                        className={`${HEADER_BUTTON_CLASS} lg:hidden`}
                        aria-label="打开侧边栏"
                    >
                        <Menu className="w-5 h-5" />
                    </button>
                )}
                <div className="hidden md:flex items-center gap-2">
                    {/* §7.4：渐变 accent 方块（逻辑与文本不动，仅令牌化） */}
                    <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-accent2 to-accent2-strong flex items-center justify-center">
                        <Bot className="w-5 h-5 text-white" />
                    </div>
                    <span className="font-semibold text-t1 hidden md:block">
                        {workbenchEnabled ? 'ZhikunCode' : 'AI Assistant'}
                    </span>
                </div>
            </div>

            {/* Center: Session Title & Model Selector */}
            <div className="flex-1 flex items-center justify-center gap-3 min-w-0">
                {workbenchEnabled && <WorkbenchViewSwitch />}
                {!simpleMode && (
                    <div className="hidden md:flex flex-col leading-tight max-w-[150px]">
                        <span className="text-sm text-t2 truncate">
                            {sessionId ? `Session: ${sessionId.slice(0, 8)}...` : 'New Session'}
                        </span>
                        {/* 副标题：连接态（分支名因无随会话更新的可靠数据源未上） */}
                        <span className="text-xs text-t3">
                            {bridgeStatus === 'connected' ? '已连接' : '连接中'}
                        </span>
                    </div>
                )}
                {/* 模型选择器：两种视图模式下常驻，避免 simple 模式下无法切换模型 */}
                {/* §7.4 模型 chip：bg-surface2 + hairline + rounded-full + accent 点（select 逻辑原样） */}
                <div className="flex min-w-0 items-center gap-2">
                    <div className="flex min-w-0 items-center gap-2 px-3 py-1.5 rounded-full border border-hairline bg-surface2
                        transition-surface duration-fast focus-within:ring-[3px] focus-within:ring-accent2-ring">
                        <span className="h-2 w-2 shrink-0 rounded-full bg-accent2" aria-hidden="true" />
                        <select
                            aria-label="模型选择"
                            value={model || ''}
                            onChange={(e) => {
                                const newModel = e.target.value;
                                if (!newModel) return;
                                setModel(newModel);
                                void useConfigStore.getState().saveConfig({ defaultModel: newModel });
                                sendSetModel(newModel);
                            }}
                            disabled={modelsLoading || availableModels.length === 0}
                            className="min-w-0 max-w-[220px] truncate text-sm bg-transparent text-t1
                                focus:outline-none disabled:opacity-50"
                        >
                            {availableModels.length === 0 && (
                                <option value="">
                                    {modelsLoading ? '模型加载中…'
                                        : modelsError ? '模型列表加载失败' : '暂无可用模型'}
                                </option>
                            )}
                            {availableModels.map(m => (
                                <option key={m.id} value={m.id}>{m.displayName}</option>
                            ))}
                        </select>
                    </div>
                    {modelsError && (
                        <button
                            type="button"
                            onClick={() => void fetchModels()}
                            className="inline-flex text-xs text-accent2-strong hover:underline"
                            aria-label="重新加载模型列表"
                        >
                            重试
                        </button>
                    )}
                </div>
            </div>

            {/* Right: ⌘K 命令钮 + Cost + New Session + Settings */}
            <div className="flex items-center gap-2">
                {/* §7.4 ⌘K 命令钮（Demo-A）：命令 ⌘K 胶囊，点击 = Ctrl+K 全局命令面板同一入口。
                    移动端不显示（§7.4 移动端只留 菜单+名称+新建）；compact(768–1023) 头部空间不足亦隐藏。
                    现状无头像入口，按任务要求不新增。 */}
                <button
                    onClick={openCommandPalette}
                    className="hidden lg:inline-flex items-center gap-1.5 h-8 px-3 rounded-full
                        border border-hairline bg-surface2 text-sm text-t2
                        hover:bg-hover2 active:scale-95 transition-interactive duration-fast
                        focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring"
                    title="全局命令面板（Ctrl+K）"
                    aria-keyshortcuts="Control+K Meta+K"
                >
                    命令
                    <Kbd>⌘K</Kbd>
                </button>

                {/* Cost Indicator（§3.8：成本数字 tabular-nums） */}
                <div className="hidden md:flex items-center gap-1 px-3 py-1.5 rounded-lg bg-surface2 border border-hairline">
                    <DollarSign className="w-4 h-4 text-ok" />
                    <span className="text-sm tabular-nums text-t1">
                        {formatCost(sessionCost)}
                    </span>
                    <span className="text-xs tabular-nums text-t2">
                        / {formatCost(totalCost)}
                    </span>
                </div>

                {/* Theme Toggle */}
                <button
                    onClick={toggleTheme}
                    className={`hidden md:inline-flex ${HEADER_BUTTON_CLASS}`}
                    title={isGlass ? '切换到浅色模式' : isDark ? '切换到液态玻璃模式' : '切换到深色模式'}
                    aria-label={isGlass ? '切换到浅色模式' : isDark ? '切换到液态玻璃模式' : '切换到深色模式'}
                >
                    {isGlass ? <Sparkles className="w-5 h-5" /> : isDark ? <Sun className="w-5 h-5" /> : <Moon className="w-5 h-5" />}
                </button>

                {/* New Session */}
                <button
                    onClick={handleNewSession}
                    className={HEADER_BUTTON_CLASS}
                    title={simpleMode ? '新建任务' : '新建会话'}
                    aria-label={simpleMode ? '新建任务' : '新建会话'}
                >
                    <Plus className="w-5 h-5" />
                </button>

                {/* Settings */}
                <button
                    onClick={() => openDialog('mcp')}
                    className={`inline-flex ${HEADER_BUTTON_CLASS}`}
                    title="MCP 管理"
                    aria-label="MCP 管理"
                >
                    <McpIcon className="h-5 w-8" />
                </button>

                <button
                    onClick={() => openDialog('settings')}
                    className={`hidden md:inline-flex ${HEADER_BUTTON_CLASS}`}
                    title="设置"
                >
                    <Settings className="w-5 h-5" />
                </button>
            </div>
        </header>
    );
}
