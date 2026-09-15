import { SessionTitle } from './SessionTitle';
import { useMessageStore } from '@/store/messageStore';
import { BrandLogo } from '@/components/ui/BrandLogo';
import { GlassMaterial } from '@/components/theme/GlassMaterial';
/**
 * Header — 顶部导航栏组件
 * SPEC: §8.6.1
 *
 * 包含: Logo, SessionTitle, ModelSelector, CostIndicator, SettingsButton
 */

import { useCallback, useEffect } from 'react';
import { Plus, Menu, DollarSign, Sun, Moon, Sparkles, Keyboard, ChevronDown } from 'lucide-react';
import { useSessionStore } from '@/store/sessionStore';
import { useCostStore } from '@/store/costStore';
import { useDialogStore } from '@/store/dialogStore';
import { normalizeThemeMode, useConfigStore } from '@/store/configStore';
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
    'p-2 max-md:min-h-11 max-md:min-w-11 rounded-[10px] hover:bg-hover2 active:scale-95 transition-interactive duration-fast text-t2 ' +
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
    const { theme } = useConfigStore();
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

    const status = useSessionStore(s => s.status);
    const sessionTitle = useMessageStore(s => {
        const block = s.messages.find(m => m.type === 'user')?.content.find(b => b.type === 'text');
        return block?.type === 'text' ? block.text : '新会话';
    });
    const currentTheme = {
        light: { label: '浅色', icon: Sun },
        dark: { label: '深色', icon: Moon },
        glass: { label: '液态玻璃', icon: Sparkles },
    }[normalizeThemeMode(theme.mode)];
    const ThemeIcon = currentTheme.icon;
    const currentModelName = availableModels.find(item => item.id === model)?.displayName ?? model ?? '';
    // Compact presentation only; option labels, ids and model requests remain unchanged.
    const compactModelName = currentModelName.replace(/\s*[（(][^）)]*[）)]\s*$/, '');
    const firstSpace = compactModelName.indexOf(' ');
    const modelFamily = firstSpace > 0 ? compactModelName.slice(0, firstSpace) : compactModelName;
    const modelVersion = firstSpace > 0 ? compactModelName.slice(firstSpace + 1) : '';


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

    const mobileStatus = bridgeStatus !== 'connected'
        ? ({ disconnected: '连接已断开', reconnecting: '连接中', error: '连接异常' }[bridgeStatus])
        : ({ idle: '', streaming: '运行中', waiting_permission: '待审批', compacting: '压缩中' }[status]);
    const mobileStatusTone = bridgeStatus === 'error' || bridgeStatus === 'disconnected'
        ? 'border-errsoft bg-errsoft text-err'
        : status === 'waiting_permission'
            ? 'border-warnsoft bg-warnsoft text-warn'
            : 'border-accent2-ring bg-accent2-soft text-accent2-ink';

    const formatCost = (cost: number) => {
        if (cost < 0.01) return '<$0.01';
        return `$${cost.toFixed(2)}`;
    };

    return (
        <header className="app-header glass-surface relative h-14 border-b border-hairline bg-surface2 flex items-center px-2 md:px-4 shrink-0">
            <GlassMaterial />
            <div className="flex md:hidden min-w-0 w-full items-center gap-3 px-1" aria-label="当前会话信息">
                <button type="button" onClick={onMenuClick} aria-label="打开菜单" title="打开菜单" aria-haspopup="dialog" className="flex min-h-11 min-w-11 shrink-0 items-center justify-center rounded-[10px] text-t2 hover:bg-hover2 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent2-ink"><Menu size={20} /></button>
                <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-medium text-t1" title={sessionTitle}>{sessionTitle}</div>
                    <div className="mt-0.5 flex min-w-0 items-center gap-2">
                        <span className="truncate text-[13px] text-t2" title={currentModelName}>{compactModelName || '模型加载中'}</span>
                        {mobileStatus && (
                            <span role="status" className={`inline-flex shrink-0 items-center gap-1.5 rounded-full border px-2 py-0.5 text-[13px] font-medium leading-5 ${mobileStatusTone}`}>
                                <span className="h-1.5 w-1.5 rounded-full bg-current motion-safe:animate-pulse" aria-hidden="true" />
                                {mobileStatus}
                            </span>
                        )}
                    </div>
                </div>
                <BrandLogo className="h-8 w-8" />
            </div>
            {/* Left: Menu Button (mobile) + Logo */}
            <div className="hidden md:flex items-center gap-3">
                {showMenuButton && (
                    <button
                        onClick={onMenuClick}
                        className={`panel-control ${HEADER_BUTTON_CLASS} lg:hidden`}
                        aria-label="打开侧边栏"
                    >
                        <Menu className="w-5 h-5" />
                    </button>
                )}
                <div className="hidden md:flex items-center gap-2">
                    <BrandLogo />
                    <span className="font-semibold text-t1 hidden md:block">
                        zhikuncode
                    </span>
                </div>
            </div>

            {/* Center: Session Title & Model Selector */}
            <div className="hidden md:flex flex-1 items-center justify-center gap-1.5 md:gap-3 min-w-0">
                {workbenchEnabled && <WorkbenchViewSwitch />}
                <SessionTitle title={sessionTitle} sessionId={sessionId} connection={bridgeStatus === 'connected' ? '已连接' : ({disconnected: '连接已断开', reconnecting: '连接中', error: '连接异常'}[bridgeStatus])} />
                {/* 模型选择器：两种视图模式下常驻，避免 simple 模式下无法切换模型 */}
                {/* §7.4 模型 chip：bg-surface2 + hairline + rounded-full + accent 点（select 逻辑原样） */}
                <div className="flex min-w-0 items-center gap-2">
                    <div className="relative flex min-w-0 items-center gap-2 px-2 md:px-3 py-1.5 max-md:min-h-11 rounded-full border border-hairline bg-surface2
                        transition-surface duration-fast focus-within:ring-[3px] focus-within:ring-accent2-ring">
                        <span className="h-2 w-2 shrink-0 rounded-full bg-accent2" aria-hidden="true" />
                        <select
                            aria-label="模型选择"
                            title={currentModelName}
                            value={model || ''}
                            onChange={(e) => {
                                const newModel = e.target.value;
                                if (!newModel) return;
                                setModel(newModel);
                                void useConfigStore.getState().saveConfig({ defaultModel: newModel });
                                sendSetModel(newModel);
                            }}
                            disabled={modelsLoading || availableModels.length === 0}
                            data-compact-label={Boolean(currentModelName)}
                            className="panel-control min-w-0 max-w-[220px] max-md:min-w-[76px] truncate text-sm bg-transparent text-t1
                                focus:outline-none disabled:opacity-50"
                        >
                            {availableModels.length === 0 && (
                                <option value="">
                                    {modelsLoading ? '模型加载中…'
                                        : modelsError ? '模型列表加载失败' : '暂无可用模型'}
                                </option>
                            )}
                            {availableModels.map(m => (
                                <option key={m.id} value={m.id} className="text-t1 bg-surfacev2">{m.displayName}</option>
                            ))}
                        </select>
                        <ChevronDown aria-hidden="true" className="pointer-events-none absolute right-1.5 h-3 w-3 text-t2 md:hidden" />
                        {currentModelName && (
                            <span aria-hidden="true" className="pointer-events-none absolute left-6 right-5 flex flex-col justify-center md:hidden text-t1 leading-tight">
                                <span className="truncate text-[13px] font-medium">{modelFamily}</span>
                                {modelVersion && <span className="truncate text-[13px]">{modelVersion}</span>}
                            </span>
                        )}
                    </div>
                    {modelsError && (
                        <button
                            type="button"
                            onClick={() => void fetchModels()}
                            className="panel-control inline-flex text-[13px] text-accent2-ink hover:underline"
                            aria-label="重新加载模型列表"
                        >
                            重试
                        </button>
                    )}
                </div>
            </div>

            {/* Right: ⌘K 命令钮 + Cost + New Session + Settings */}
            <div className="hidden md:flex items-center gap-2">
                {/* §7.4 ⌘K 命令钮（Demo-A）：命令 ⌘K 胶囊，点击 = Ctrl+K 全局命令面板同一入口。
                    移动端不显示（§7.4 移动端只留 菜单+名称+新建）；compact(768–1023) 头部空间不足亦隐藏。
                    现状无头像入口，按任务要求不新增。 */}
                <button
                    onClick={openCommandPalette}
                    className="panel-control hidden lg:inline-flex items-center gap-1.5 h-8 px-3 rounded-full
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
                <div className="hidden md:flex items-center gap-1 px-3 py-1.5 rounded-[14px] bg-surface2 border border-hairline">
                    <DollarSign className="w-4 h-4 text-ok" />
                    <span className="text-sm tabular-nums text-t1">
                        {formatCost(sessionCost)}
                    </span>
                    <span className="text-[13px] tabular-nums text-t2">
                        / {formatCost(totalCost)}
                    </span>
                </div>

                {/* 显示当前主题；点击选择，不再循环切换。 */}
                <button
                    onClick={() => openDialog('settings')}
                    className={`panel-control hidden md:inline-flex items-center gap-1.5 border border-hairline bg-surface2 ${HEADER_BUTTON_CLASS}`}
                    title="外观设置"
                    aria-label="外观设置"
                    aria-haspopup="dialog"
                >
                    <ThemeIcon className="w-4 h-4" aria-hidden="true" />
                    <span className="text-sm whitespace-nowrap">{currentTheme.label}</span>
                    <ChevronDown className="w-3.5 h-3.5" aria-hidden="true" />
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
                    className={`panel-control inline-flex ${HEADER_BUTTON_CLASS}`}
                    title="MCP 管理"
                    aria-label="MCP 管理"
                >
                    <McpIcon className="h-5 w-8" />
                </button>

                <button
                    onClick={() => openDialog('keybindings')}
                    className={`panel-control hidden md:inline-flex ${HEADER_BUTTON_CLASS}`}
                    title="快捷键帮助"
                    aria-label="快捷键帮助"
                >
                    <Keyboard className="w-5 h-5" />
                </button>

            </div>
        </header>
    );
}
