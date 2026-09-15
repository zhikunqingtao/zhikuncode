/**
 * StatusBar — 底部状态栏组件
 * SPEC: §8.6.4
 *
 * 包含: PermissionMode, Model, Tokens, Cost, ConnectionStatus
 *
 * §7.4 令牌化（P3）：34px、hairline 顶分割、文本 text-t2（任务书字面 text-t3
 * 实测对比度 <4.5:1 不达 §10.1 AA 红线，取 t2——同 SettingsPanel P1b 先例：
 * 对比度红线优先于色级偏好）、状态点 ok/warn/err/accent + 8% 光晕、
 * 数字 tabular-nums。桌面/移动 hide-m 逻辑（hidden md:flex）不动。
 */

import { Shield, Cpu, Coins, Wifi, WifiOff, Activity } from 'lucide-react';
import { useSessionStore } from '@/store/sessionStore';
import { useCostStore } from '@/store/costStore';
import { usePermissionStore } from '@/store/permissionStore';
import { useBridgeStore } from '@/store/bridgeStore';
import { TokenBudgetIndicator } from '@/components/status/TokenBudgetIndicator';
import type { PermissionMode } from '@/types';
import { useWorkbenchViewStore } from '@/store/workbenchViewStore';

export function getPermissionModeLabel(mode: PermissionMode): string {
    switch (mode) {
        case 'default': return '标准授权';
        case 'plan': return '先做计划';
        case 'accept_edits': return '自动编辑';
        case 'dont_ask': return '拒绝待批';
        case 'auto_approve': return '完全访问';
    }
}

export function getPermissionModeDescription(mode: PermissionMode): string {
    return {
        default: '按需确认操作',
        plan: '先制定计划，再执行',
        accept_edits: '自动接受文件编辑',
        dont_ask: '需要确认的操作自动拒绝',
        auto_approve: '自动批准工具权限请求，仍受系统限制',
    }[mode];
}

export function getPermissionModeColor(mode: PermissionMode): string {
    switch (mode) {
        case 'default': return 'text-accent2-ink';
        case 'plan': return 'text-accent2-ink';
        case 'accept_edits': return 'text-ok';
        case 'dont_ask': return 'text-warn';
        case 'auto_approve': return 'text-err';
    }
}

/** §7.4 状态点色调 → v2 令牌 */
const DOT_TONE_COLORS = {
    ok: 'var(--v2-ok)',
    warn: 'var(--v2-warn)',
    err: 'var(--v2-err)',
    accent: 'var(--v2-accent)',
} as const;
type DotTone = keyof typeof DOT_TONE_COLORS;

/** 状态点：2px 圆点 + 8% 同色光晕（color-mix 取令牌 8% 透明度，装饰性 aria-hidden） */
function StatusDot({ tone, pulse = false }: { tone: DotTone; pulse?: boolean }) {
    const color = DOT_TONE_COLORS[tone];
    return (
        <span
            aria-hidden="true"
            className={`w-2 h-2 rounded-full shrink-0 ${pulse ? 'animate-pulse' : ''}`}
            style={{
                backgroundColor: color,
                boxShadow: `0 0 0 3px color-mix(in srgb, ${color} 8%, transparent)`,
            }}
        />
    );
}

/** 会话状态 → 状态点色调（idle=ok / waiting=warn / 进行中=accent） */
function sessionStatusTone(status: string): { tone: DotTone; pulse: boolean } {
    switch (status) {
        case 'streaming': return { tone: 'accent', pulse: true };
        case 'waiting_permission': return { tone: 'warn', pulse: false };
        case 'compacting': return { tone: 'accent', pulse: true };
        default: return { tone: 'ok', pulse: false };
    }
}

/** hairline 竖向分割（§7.4 条目间 ｜ 分割），装饰性 */
function HairlineDivider({ className = '' }: { className?: string }) {
    return <span aria-hidden="true" className={`w-px h-3.5 bg-hairline shrink-0 ${className}`} />;
}

export function StatusBar() {
    const { model, status, turnCount } = useSessionStore();
    const { permissionMode } = usePermissionStore();
    const { sessionCost, totalCost, usage } = useCostStore();
    const { pendingPermissions } = usePermissionStore();
    const { bridgeStatus } = useBridgeStore();
    const simpleMode = useWorkbenchViewStore(s => s.enabled && s.viewMode === 'simple');

    const getStatusLabel = (s: string) => {
        switch (s) {
            case 'idle': return '就绪';
            case 'streaming': return '生成中...';
            case 'waiting_permission': return '等待权限';
            case 'compacting': return '压缩中...';
            default: return s;
        }
    };

    const statusDot = sessionStatusTone(status);

    if (simpleMode) {
        return (
            <footer className="app-status h-[34px] border-t border-hairline bg-surface2 flex items-center justify-between px-4 text-[13px] shrink-0">
                <div className="flex items-center gap-2">
                    <StatusDot tone={statusDot.tone} pulse={statusDot.pulse} />
                    <span className="text-t2">{getStatusLabel(status)}</span>
                </div>
                <div className="flex items-center gap-1.5 text-t2" title={bridgeStatus === 'connected' ? '已连接' : '未连接'}>
                    {bridgeStatus === 'connected' ? <Wifi className="h-3.5 w-3.5 text-ok" /> : <WifiOff className="h-3.5 w-3.5 text-err" />}
                    <span>{bridgeStatus === 'connected' ? '已连接' : '连接中'}</span>
                </div>
            </footer>
        );
    }

    return (
        <div className="shrink-0">
            {/* Token Budget Indicator */}
            <TokenBudgetIndicator />

            <footer className="app-status h-[34px] border-t border-hairline bg-surface2
                flex items-center px-4 text-[13px] shrink-0">

            {/* Left: Permission Mode & Status */}
            <div className="flex items-center gap-4">
                <div className="flex items-center gap-1.5" title="权限模式">
                    <Shield className={`w-3.5 h-3.5 ${getPermissionModeColor(permissionMode)}`} />
                    <span className="text-t2">
                        {getPermissionModeLabel(permissionMode)}
                    </span>
                </div>

                {pendingPermissions.length > 0 && (
                    <div className="flex items-center gap-1.5 text-warnstrong">
                        <Activity className="w-3.5 h-3.5 animate-pulse" />
                        <span className="tabular-nums">{pendingPermissions.length} 个权限请求</span>
                    </div>
                )}

                <div className="flex items-center gap-1.5" title="会话状态">
                    <StatusDot tone={statusDot.tone} pulse={statusDot.pulse} />
                    <span className="text-t2">
                        {getStatusLabel(status)}
                    </span>
                </div>

                {turnCount > 0 && (
                    <span className="text-t2 tabular-nums">
                        回合 {turnCount}
                    </span>
                )}
            </div>

            {/* Center: Model */}
            <div className="flex-1 flex items-center justify-center">
                <div className="flex items-center gap-1.5 text-t2">
                    <Cpu className="w-3.5 h-3.5 text-t3" />
                    <span className="truncate max-w-[150px]">{model}</span>
                </div>
            </div>

            {/* Right: Tokens & Cost & Connection（hairline 分割；hide-m 逻辑不动） */}
            <div className="flex items-center gap-3">
                {/* Tokens */}
                <div className="hidden md:flex items-center gap-2 text-t2 tabular-nums">
                    <span title="输入 Tokens">↑ {usage.inputTokens.toLocaleString()}</span>
                    <span title="输出 Tokens">↓ {usage.outputTokens.toLocaleString()}</span>
                    {usage.cacheReadInputTokens > 0 && (
                        <span title="缓存读取" className="text-accent2-ink">
                            ⚡ {usage.cacheReadInputTokens.toLocaleString()}
                        </span>
                    )}
                </div>

                <HairlineDivider className="hidden md:block" />

                {/* Cost */}
                <div className="flex items-center gap-1 text-t2" title="当前会话成本">
                    <Coins className="w-3.5 h-3.5 text-t3" />
                    <span className="tabular-nums">${sessionCost.toFixed(3)}</span>
                </div>

                <HairlineDivider />

                {/* Global Total Cost */}
                <span className="text-[13px] text-t2 tabular-nums" title={`全局累计: $${totalCost.toFixed(3)}`}>
                    ∑ ${totalCost.toFixed(3)}
                </span>

                <HairlineDivider />

                {/* Connection Status */}
                <div className="flex items-center gap-1" title={bridgeStatus === 'connected' ? '已连接' : '未连接'}>
                    {bridgeStatus === 'connected' ? (
                        <Wifi className="w-3.5 h-3.5 text-ok" />
                    ) : (
                        <WifiOff className="w-3.5 h-3.5 text-err" />
                    )}
                </div>
            </div>
        </footer>
        </div>
    );
}
