/**
 * StatusBar — 底部状态栏组件
 * SPEC: §8.6.4
 *
 * 包含: TokenBudgetIndicator + 瞬态项（待处理权限请求 / 回合数）
 * （权限模式为只读展示已移除——切换入口在输入区 PermissionMenu；
 *   会话状态/Tokens/成本/连接已上移至 Header 右簇；模型展示已移除。
 *   无瞬态内容时底栏不渲染，避免空条。）
 *
 * §7.4 令牌化（P3）：34px、hairline 顶分割、文本 text-t2、数字 tabular-nums。
 */

import { Activity } from 'lucide-react';
import { useSessionStore } from '@/store/sessionStore';
import { usePermissionStore } from '@/store/permissionStore';
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

export function StatusBar() {
    const { turnCount } = useSessionStore();
    const { pendingPermissions } = usePermissionStore();
    const simpleMode = useWorkbenchViewStore(s => s.enabled && s.viewMode === 'simple');

    // 简洁模式：状态与连接已上移至 Header，底栏整体不再需要。
    if (simpleMode) return null;

    const hasTransient = pendingPermissions.length > 0 || turnCount > 0;

    return (
        <div className="shrink-0">
            {/* Token Budget Indicator（按可见性自渲染） */}
            <TokenBudgetIndicator />

            {/* 瞬态项存在时才渲染底栏，避免空条 */}
            {hasTransient && (
                <footer className="app-status h-[34px] border-t border-hairline bg-surface2
                    flex items-center px-4 text-[13px] shrink-0">
                    <div className="flex items-center gap-4">
                        {pendingPermissions.length > 0 && (
                            <div className="flex items-center gap-1.5 text-warnstrong">
                                <Activity className="w-3.5 h-3.5 animate-pulse" />
                                <span className="tabular-nums">{pendingPermissions.length} 个权限请求</span>
                            </div>
                        )}

                        {turnCount > 0 && (
                            <span className="text-t2 tabular-nums">
                                回合 {turnCount}
                            </span>
                        )}
                    </div>
                </footer>
            )}
        </div>
    );
}
