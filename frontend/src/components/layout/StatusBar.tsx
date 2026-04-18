/**
 * StatusBar — 底部状态栏组件
 * SPEC: §8.6.4
 *
 * 包含: PermissionMode, Model, Tokens, Cost, ConnectionStatus
 */

import { Shield, Cpu, Coins, Wifi, WifiOff, Activity } from 'lucide-react';
import { useSessionStore } from '@/store/sessionStore';
import { useCostStore } from '@/store/costStore';
import { usePermissionStore } from '@/store/permissionStore';
import { useBridgeStore } from '@/store/bridgeStore';
import { TokenBudgetIndicator } from '@/components/status/TokenBudgetIndicator';

export function StatusBar() {
    const { model, status, turnCount } = useSessionStore();
    const { permissionMode } = usePermissionStore();
    const { sessionCost, usage } = useCostStore();
    const { pendingPermission } = usePermissionStore();
    const { bridgeStatus } = useBridgeStore();

    const getPermissionModeLabel = (mode: string) => {
        switch (mode) {
            case 'read_only': return '只读';
            case 'read_write': return '读写';
            case 'auto_edit': return '自动';
            default: return mode;
        }
    };

    const getPermissionModeColor = (mode: string) => {
        switch (mode) {
            case 'read_only': return 'text-blue-500';
            case 'read_write': return 'text-yellow-500';
            case 'auto_edit': return 'text-green-500';
            default: return 'text-gray-500';
        }
    };

    const getStatusLabel = (s: string) => {
        switch (s) {
            case 'idle': return '就绪';
            case 'streaming': return '生成中...';
            case 'waiting_permission': return '等待权限';
            case 'compacting': return '压缩中...';
            default: return s;
        }
    };

    return (
        <div className="shrink-0">
            {/* Token Budget Indicator */}
            <TokenBudgetIndicator />

            <footer className="h-8 border-t border-[var(--border)] bg-[var(--bg-secondary)] 
                flex items-center px-4 text-xs shrink-0">
            
            {/* Left: Permission Mode & Status */}
            <div className="flex items-center gap-4">
                <div className="flex items-center gap-1.5" title="权限模式">
                    <Shield className={`w-3.5 h-3.5 ${getPermissionModeColor(permissionMode)}`} />
                    <span className="text-[var(--text-secondary)]">
                        {getPermissionModeLabel(permissionMode)}
                    </span>
                </div>

                {pendingPermission && (
                    <div className="flex items-center gap-1.5 text-yellow-500">
                        <Activity className="w-3.5 h-3.5 animate-pulse" />
                        <span>1 个权限请求</span>
                    </div>
                )}

                <div className="flex items-center gap-1.5" title="会话状态">
                    <div className={`w-2 h-2 rounded-full ${
                        status === 'streaming' ? 'bg-blue-500 animate-pulse' :
                        status === 'waiting_permission' ? 'bg-yellow-500' :
                        status === 'compacting' ? 'bg-purple-500' :
                        'bg-green-500'
                    }`} />
                    <span className="text-[var(--text-secondary)]">
                        {getStatusLabel(status)}
                    </span>
                </div>

                {turnCount > 0 && (
                    <span className="text-[var(--text-muted)]">
                        回合 {turnCount}
                    </span>
                )}
            </div>

            {/* Center: Model */}
            <div className="flex-1 flex items-center justify-center">
                <div className="flex items-center gap-1.5 text-[var(--text-muted)]">
                    <Cpu className="w-3.5 h-3.5" />
                    <span className="truncate max-w-[150px]">{model}</span>
                </div>
            </div>

            {/* Right: Tokens & Cost & Connection */}
            <div className="flex items-center gap-4">
                {/* Tokens */}
                <div className="hidden md:flex items-center gap-2 text-[var(--text-muted)]">
                    <span title="输入 Tokens">↑ {usage.inputTokens.toLocaleString()}</span>
                    <span title="输出 Tokens">↓ {usage.outputTokens.toLocaleString()}</span>
                    {usage.cacheReadInputTokens > 0 && (
                        <span title="缓存读取" className="text-blue-400">
                            ⚡ {usage.cacheReadInputTokens.toLocaleString()}
                        </span>
                    )}
                </div>

                {/* Cost */}
                <div className="flex items-center gap-1 text-[var(--text-secondary)]" title="当前会话成本">
                    <Coins className="w-3.5 h-3.5" />
                    <span>${sessionCost.toFixed(3)}</span>
                </div>

                {/* Connection Status */}
                <div className="flex items-center gap-1" title={bridgeStatus === 'connected' ? '已连接' : '未连接'}>
                    {bridgeStatus === 'connected' ? (
                        <Wifi className="w-3.5 h-3.5 text-green-500" />
                    ) : (
                        <WifiOff className="w-3.5 h-3.5 text-red-500" />
                    )}
                </div>
            </div>
        </footer>
        </div>
    );
}
