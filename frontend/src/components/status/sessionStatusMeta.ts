/**
 * sessionStatusMeta — 会话状态展示规则（颜色/脉冲/旋转/文案/胶囊语气）。
 *
 * Header 桌面右簇状态胶囊、输入区状态胶囊 (SessionStatusCapsule)、
 * 状态图标 (SessionStatusIcon) 共用，保证各入口视觉完全一致。
 */

/** 会话状态展示（自底部状态栏上移至 Header 右簇）。
 *  streaming 用旋转图标（Loader2，accent2 墨色）——与任务面板/SimpleTaskList 的运行中约定一致，
 *  比脉冲点更直观；其余状态用色点 + 8% 光晕。 */
const SESSION_STATUS_META: Record<string, { label: string; color: string; pulse: boolean; spinner?: boolean }> = {
    idle: { label: '就绪', color: 'var(--v2-ok)', pulse: false },
    streaming: { label: '运行中', color: 'var(--v2-accent)', pulse: false, spinner: true },
    waiting_permission: { label: '等待权限', color: 'var(--v2-warn)', pulse: false },
    compacting: { label: '压缩中...', color: 'var(--v2-accent)', pulse: true },
};

/** 会话状态 → 图标规则（颜色/脉冲/旋转） */
export function getSessionStatusMeta(status: string): { label: string; color: string; pulse: boolean; spinner?: boolean } {
    return SESSION_STATUS_META[status] ?? { label: status, color: 'var(--v2-ok)', pulse: false };
}

/** 会话状态 → 展示文案 */
export function getSessionStatusLabel(status: string): string {
    return SESSION_STATUS_META[status]?.label ?? status;
}

/** 状态胶囊语气：运行中/压缩中=accent 软底高亮，等待权限=警告色，就绪=透明低调（无事态不抢视觉） */
export function getSessionStatusChipTone(status: string): string {
    return status === 'idle'
        ? 'border-transparent text-t2'
        : status === 'waiting_permission'
            ? 'border-warnsoft bg-warnsoft text-warn'
            : 'border-accent2-ring bg-accent2-soft text-accent2-ink';
}
