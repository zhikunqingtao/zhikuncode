import { SessionTitle } from './SessionTitle';
import { useMessageStore } from '@/store/messageStore';
import { BrandLogo } from '@/components/ui/BrandLogo';
import { GlassMaterial } from '@/components/theme/GlassMaterial';
/**
 * Header — 顶部导航栏组件
 * SPEC: §8.6.1
 *
 * 包含: Logo, SessionTitle, SessionStatus, Metrics(Tokens/Cost), ThemeSwitch, NewSession, Settings
 * （会话状态与用量指标自底部状态栏右簇上移；连接状态由 SessionTitle 副行呈现，不再重复）
 */

import { useEffect } from 'react';
import { Menu, Sun, Moon, Sparkles, Keyboard, ChevronDown, Coins, Loader2 } from 'lucide-react';
import { useSessionStore } from '@/store/sessionStore';
import { useCostStore } from '@/store/costStore';
import { useDialogStore } from '@/store/dialogStore';
import { normalizeThemeMode, useConfigStore } from '@/store/configStore';
import { useModelStore } from '@/store/modelStore';
import { useBridgeStore } from '@/store/bridgeStore';
import { clearSessionSelection } from '@/services/sessionActivation';
import { McpIcon } from '@/components/mcp/McpIcon';
import { MemoryIcon } from '@/components/memory/MemoryIcon';

/** §7.4 头部按钮共性：hover/active/焦点环（ring-accent2-ring） */
const HEADER_BUTTON_CLASS =
    'px-2 h-7 items-center justify-center max-md:min-h-11 max-md:min-w-11 rounded-[10px] hover:bg-hover2 hover:text-t1 active:scale-95 active:shadow-pressed transition-interactive duration-fast text-t2 ' +
    'focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring';

/** 会话状态展示（自底部状态栏上移至 Header 右簇）。
 *  streaming 用旋转图标（Loader2，accent2 墨色）——与任务面板/SimpleTaskList 的运行中约定一致，
 *  比脉冲点更直观；其余状态用色点 + 8% 光晕。 */
const SESSION_STATUS_META: Record<string, { label: string; color: string; pulse: boolean; spinner?: boolean }> = {
    idle: { label: '就绪', color: 'var(--v2-ok)', pulse: false },
    streaming: { label: '运行中', color: 'var(--v2-accent)', pulse: false, spinner: true },
    waiting_permission: { label: '等待权限', color: 'var(--v2-warn)', pulse: false },
    compacting: { label: '压缩中...', color: 'var(--v2-accent)', pulse: true },
};

/** 会话状态 → 图标规则（颜色/脉冲/旋转），供 Header 与输入区权限 chip 等复用同款状态图标 */
export function getSessionStatusMeta(status: string): { label: string; color: string; pulse: boolean; spinner?: boolean } {
    return SESSION_STATUS_META[status] ?? { label: status, color: 'var(--v2-ok)', pulse: false };
}

/** 会话状态 → 展示文案 */
export function getSessionStatusLabel(status: string): string {
    return SESSION_STATUS_META[status]?.label ?? status;
}

/** 指标间 hairline 竖向分割（同原 StatusBar 右簇 §7.4），装饰性 */
function MetricDivider({ className = '' }: { className?: string }) {
    return <span aria-hidden="true" className={`w-px h-3.5 bg-hairline shrink-0 ${className}`} />;
}

interface HeaderProps {
    onMenuClick?: () => void;
    showMenuButton?: boolean;
}

export function Header({ onMenuClick, showMenuButton = false }: HeaderProps) {
    const { sessionId, model, setModel } = useSessionStore();
    const { sessionCost, totalCost, usage } = useCostStore();
    const { bridgeStatus } = useBridgeStore();
    const { openDialog } = useDialogStore();
    const { theme } = useConfigStore();

    // 动态加载可用模型列表（统一从 modelStore 缓存读取；移动端头部展示当前模型名）
    const {
        models: availableModels,
        defaultModel,
        loaded,
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
        return block?.type === 'text' ? block.text : '任务';
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
    const sessionStatusMeta = getSessionStatusMeta(status);
    const streaming = bridgeStatus === 'connected' && status === 'streaming';
    /** 桌面右簇状态胶囊语气：运行中/压缩中=accent 软底高亮，等待权限=警告色，就绪=透明低调（无事态不抢视觉） */
    const sessionStatusChipTone =
        status === 'idle'
            ? 'border-transparent text-t2'
            : status === 'waiting_permission'
                ? 'border-warnsoft bg-warnsoft text-warn'
                : 'border-accent2-ring bg-accent2-soft text-accent2-ink';

    const mobileStatus = bridgeStatus !== 'connected'
        ? ({ disconnected: '连接已断开', reconnecting: '连接中', error: '连接异常' }[bridgeStatus])
        : ({ idle: '', streaming: '运行中', waiting_permission: '待审批', compacting: '压缩中' }[status]);
    const mobileStatusTone = bridgeStatus === 'error' || bridgeStatus === 'disconnected'
        ? 'border-errsoft bg-errsoft text-err'
        : status === 'waiting_permission'
            ? 'border-warnsoft bg-warnsoft text-warn'
            : 'border-accent2-ring bg-accent2-soft text-accent2-ink';

    return (
        <header className="app-header glass-surface relative h-14 border-b border-hairline bg-surface2 flex items-center px-2 md:px-4 shrink-0">
            <GlassMaterial interactive />
            <div className="flex md:hidden min-w-0 w-full items-center gap-3 px-1" aria-label="当前会话信息">
                <button type="button" onClick={onMenuClick} aria-label="打开会话列表" title="打开会话列表" className="flex min-h-11 min-w-11 shrink-0 items-center justify-center rounded-[10px] text-t2 hover:bg-hover2 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent2-ink"><Menu size={20} /></button>
                <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-medium text-t1" title={sessionTitle}>{sessionTitle}</div>
                    <div className="mt-0.5 flex min-w-0 items-center gap-2">
                        <span className="truncate text-[13px] text-t2" title={currentModelName}>{compactModelName || '模型加载中'}</span>
                        {mobileStatus && (
                            <span role="status" className={`inline-flex shrink-0 items-center gap-1.5 rounded-full border px-2 py-0.5 text-[13px] font-medium leading-5 ${mobileStatusTone}`}>
                                {streaming
                                    ? <Loader2 className="h-3 w-3 animate-spin" aria-hidden="true" />
                                    : <span className="h-1.5 w-1.5 rounded-full bg-current motion-safe:animate-pulse" aria-hidden="true" />}
                                {mobileStatus}
                            </span>
                        )}
                    </div>
                </div>
                <button type="button" onClick={clearSessionSelection} aria-label="返回首页" title="返回首页"
                    className="flex min-h-11 min-w-11 shrink-0 items-center justify-center rounded-[10px] hover:bg-hover2 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent2-ink">
                    <BrandLogo className="h-8 w-8" />
                </button>
            </div>
            {/* Left: Menu Button (mobile) + Logo */}
            <div className="hidden md:flex items-center gap-3">
                {showMenuButton && (
                    <button
                        onClick={onMenuClick}
                        className={`panel-control ${HEADER_BUTTON_CLASS} min-h-11 min-w-11 lg:hidden`}
                        aria-label="打开会话列表"
                    >
                        <Menu className="w-5 h-5" />
                    </button>
                )}
                <button type="button" onClick={clearSessionSelection} aria-label="返回首页" title="返回首页"
                    className="hidden md:flex min-h-11 min-w-11 items-center justify-center gap-2 rounded-[10px] hover:bg-hover2 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent2-ink">
                    <BrandLogo />
                    <span className="font-semibold text-t1 hidden lg:block">
                        zhikuncode
                    </span>
                </button>
            </div>

            {/* Center: Session Title */}
            <div className="hidden md:flex flex-1 items-center justify-center gap-1.5 md:gap-3 min-w-0">
                <SessionTitle title={sessionTitle} sessionId={sessionId} connection={bridgeStatus === 'connected' ? '已连接' : ({disconnected: '连接已断开', reconnecting: '连接中', error: '连接异常'}[bridgeStatus])} />
            </div>

            {/* Right: SessionStatus + Metrics + Theme + New Session + MCP + Shortcuts */}
            <div className="hidden md:flex items-center gap-2">
                {/* 会话状态 + 用量指标（自底部状态栏右簇上移；指标细节 ≥lg 展示，空间不足时让位） */}
                <div className="flex items-center gap-2.5 pr-1 text-[13px] font-mono tabular-nums text-t3">
                    <div
                        className={`inline-flex shrink-0 items-center gap-1.5 rounded-full border px-2 py-0.5 text-[13px] font-medium leading-5 ${sessionStatusChipTone}`}
                        title="会话状态"
                        role="status"
                    >
                        {sessionStatusMeta.spinner ? (
                            <Loader2 aria-hidden="true" className="h-3.5 w-3.5 animate-spin" />
                        ) : (
                            <span
                                aria-hidden="true"
                                className={`h-2 w-2 rounded-full ${sessionStatusMeta.pulse ? 'motion-safe:animate-pulse' : ''}`}
                                style={{
                                    backgroundColor: sessionStatusMeta.color,
                                    boxShadow: `0 0 0 3px color-mix(in srgb, ${sessionStatusMeta.color} 8%, transparent)`,
                                }}
                            />
                        )}
                        <span>{sessionStatusMeta.label}</span>
                    </div>
                    <MetricDivider className="hidden lg:block" />
                    <div className="hidden lg:flex items-center gap-2 tabular-nums">
                        <span title="输入 Tokens">↑ {usage.inputTokens.toLocaleString()}</span>
                        <span title="输出 Tokens">↓ {usage.outputTokens.toLocaleString()}</span>
                        {usage.cacheReadInputTokens > 0 && (
                            <span title="缓存读取" className="text-accent2-ink">
                                ⚡ {usage.cacheReadInputTokens.toLocaleString()}
                            </span>
                        )}
                    </div>
                    <MetricDivider className="hidden lg:block" />
                    <div className="hidden lg:flex items-center gap-1 tabular-nums" title="当前会话成本">
                        <Coins className="w-3.5 h-3.5 text-t3" />
                        <span>${sessionCost.toFixed(3)}</span>
                    </div>
                    <MetricDivider className="hidden lg:block" />
                    <span className="hidden lg:block tabular-nums" title={`全局累计: $${totalCost.toFixed(3)}`}>
                        ∑ ${totalCost.toFixed(3)}
                    </span>
                </div>

                {/* 显示当前主题；点击选择，不再循环切换。 */}
                <button
                    onClick={() => openDialog('settings')}
                    className={`panel-control hidden md:inline-flex items-center gap-1.5 border border-hairline bg-surfacev2 shadow-raised hover:shadow-raised-hover ${HEADER_BUTTON_CLASS}`}
                    title="外观设置"
                    aria-label="外观设置"
                    aria-haspopup="dialog"
                >
                    <ThemeIcon className="w-4 h-4" aria-hidden="true" />
                    <span className="text-sm whitespace-nowrap">{currentTheme.label}</span>
                    <ChevronDown className="w-3.5 h-3.5" aria-hidden="true" />
                </button>

                {/* Settings */}
                <button
                    onClick={() => openDialog('mcp')}
                    className={`panel-control inline-flex ${HEADER_BUTTON_CLASS}`}
                    title="MCP 管理"
                    aria-label="MCP 管理"
                >
                    <McpIcon className="h-7 w-auto" />
                </button>

                {/* Memory */}
                <button
                    onClick={() => openDialog('memory')}
                    className={`panel-control inline-flex ${HEADER_BUTTON_CLASS}`}
                    title="记忆"
                    aria-label="记忆"
                >
                    <MemoryIcon className="h-7 w-auto" />
                </button>

                <button
                    onClick={() => openDialog('keybindings')}
                    className={`panel-control hidden md:inline-flex ${HEADER_BUTTON_CLASS}`}
                    title="快捷键帮助"
                    aria-label="快捷键帮助"
                >
                    <Keyboard className="w-6 h-6" />
                </button>

            </div>
        </header>
    );
}
