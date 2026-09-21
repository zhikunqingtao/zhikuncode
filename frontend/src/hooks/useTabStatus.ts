import { useEffect } from 'react';
import { useSessionStore } from '@/store/sessionStore';
import { usePermissionStore } from '@/store/permissionStore';
import { useMessageStore } from '@/store/messageStore';

const DEFAULT_TITLE = 'zhikuncode';

type SessionStatus = ReturnType<typeof useSessionStore.getState>['status'];

/** 旋转 loader 帧（半月顺时针旋转）：与 Header 徽章 Loader2（开口圆环旋转）观感对齐，
 *  4 帧 × 250ms = 1s/圈，与 animate-spin 节奏一致 */
const SPINNER_FRAMES = ['◐', '◓', '◑', '◒'] as const;
/** 脉冲帧：1s 亮 + 1s 灭 = 2s 周期，与 compacting 徽章 animate-pulse 节奏一致 */
const PULSE_FRAMES = ['●', '●', '●', '●', '○', '○', '○', '○'] as const;
const FRAME_INTERVAL_MS = 250;
/** 标签页宽度有限，状态前缀之外给会话标题的预算（超出截断） */
const SESSION_TITLE_MAX = 20;

/**
 * 会话标题清洗：用户消息可能很长且含换行，压缩为单行并截断。
 * 与 Header 顶栏标题（SessionTitle 组件 CSS truncate）同源的兜底文案为「任务」。
 */
export function truncateSessionTitle(text: string): string {
    const oneLine = text.replace(/\s+/g, ' ').trim();
    if (!oneLine) return '任务';
    return oneLine.length > SESSION_TITLE_MAX
        ? `${oneLine.slice(0, SESSION_TITLE_MAX)}…`
        : oneLine;
}

/**
 * 计算浏览器标签页标题。纯函数，便于单测。
 * 优先级：待审批 > 运行中/压缩中 > 默认；文案与 Header 状态徽章保持一致。
 */
export function computeTabTitle(
    status: SessionStatus,
    pendingCount: number,
    sessionTitle: string,
    frame = 0,
): string {
    if (status === 'waiting_permission' || pendingCount > 0) {
        const prefix = pendingCount > 1 ? `🔴 (${pendingCount}) 待审批` : '🔴 待审批';
        return `${prefix} · ${sessionTitle}`;
    }
    if (status === 'streaming') {
        return `${SPINNER_FRAMES[frame % SPINNER_FRAMES.length]} 运行中 · ${sessionTitle}`;
    }
    if (status === 'compacting') {
        return `${PULSE_FRAMES[frame % PULSE_FRAMES.length]} 压缩中 · ${sessionTitle}`;
    }
    return DEFAULT_TITLE;
}

/**
 * 浏览器标签页标题实时反映当前会话状态，方便切到其他标签时一眼看到：
 * - 待审批（最高优先级）: 🔴 (N) 待审批 · {会话标题}（静态）
 * - 运行中: ◐ 运行中 · {会话标题}（半月旋转动画，对齐主界面 Loader2 徽章观感与转速）
 * - 压缩中: ● 压缩中 · {会话标题}（脉冲帧动画，对齐主界面徽章脉冲）
 * - 空闲: zhikuncode
 *
 * 会话标题与 Header 顶栏同源（第一条用户消息文本，兜底「任务」）。
 * 挂载在 App 根组件（usePageExitGuard 旁）。
 */
export function useTabStatus() {
    const status = useSessionStore(s => s.status);
    const pendingCount = usePermissionStore(s => s.pendingPermissions.length);
    // 与 Header.tsx 顶栏会话标题同源的 selector
    const sessionTitle = useMessageStore(s => {
        const block = s.messages.find(m => m.type === 'user')?.content.find(b => b.type === 'text');
        return block?.type === 'text' ? block.text : '任务';
    });

    const animated = status === 'streaming' || status === 'compacting';

    useEffect(() => {
        const name = truncateSessionTitle(sessionTitle);
        if (!animated) {
            document.title = computeTabTitle(status, pendingCount, name);
            return () => {
                document.title = DEFAULT_TITLE;
            };
        }
        let frame = 0;
        document.title = computeTabTitle(status, pendingCount, name, frame);
        const timer = setInterval(() => {
            frame += 1;
            document.title = computeTabTitle(status, pendingCount, name, frame);
        }, FRAME_INTERVAL_MS);
        return () => {
            clearInterval(timer);
            document.title = DEFAULT_TITLE;
        };
    }, [status, pendingCount, sessionTitle, animated]);
}
