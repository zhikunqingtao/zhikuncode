import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { computeTabTitle, truncateSessionTitle, useTabStatus } from '../useTabStatus';
import { useSessionStore } from '@/store/sessionStore';
import { usePermissionStore } from '@/store/permissionStore';
import { useMessageStore } from '@/store/messageStore';
import type { Message, PermissionDecision, PermissionRequest } from '@/types';

function makePermission(id: string): PermissionRequest {
    return {
        toolUseId: id,
        toolName: 'Bash',
        input: {},
        riskLevel: 'medium',
        reason: 'test',
    };
}

function makeAllowDecision(id: string): PermissionDecision {
    return {
        toolUseId: id,
        decision: 'allow',
        optionId: 'allow-once',
        operationHash: 'test-hash',
        deliveryGeneration: 0,
    };
}

function setUserMessage(text: string) {
    useMessageStore.setState({
        messages: [
            { type: 'user', content: [{ type: 'text', text }] } as unknown as Message,
        ],
    });
}

describe('truncateSessionTitle', () => {
    it('短标题原样返回', () => {
        expect(truncateSessionTitle('帮我审查这份文档')).toBe('帮我审查这份文档');
    });

    it('换行与多余空白压缩为单行', () => {
        expect(truncateSessionTitle('第一行\n第二行   第三行')).toBe('第一行 第二行 第三行');
    });

    it('超长标题截断并加省略号', () => {
        const long = '这是一个非常非常长的会话标题超过二十个字符需要被截断';
        expect(truncateSessionTitle(long)).toBe('这是一个非常非常长的会话标题超过二十个字…');
    });

    it('空白输入兜底为「任务」', () => {
        expect(truncateSessionTitle('   \n  ')).toBe('任务');
    });
});

describe('computeTabTitle', () => {
    it('idle 返回默认标题（不带会话标题）', () => {
        expect(computeTabTitle('idle', 0, '某任务')).toBe('zhikuncode');
    });

    it('streaming 显示半月 loader 帧 + 运行中 + 会话标题', () => {
        expect(computeTabTitle('streaming', 0, '审查文档', 0)).toBe('◐ 运行中 · 审查文档');
        expect(computeTabTitle('streaming', 0, '审查文档', 1)).toBe('◓ 运行中 · 审查文档');
        expect(computeTabTitle('streaming', 0, '审查文档', 3)).toBe('◒ 运行中 · 审查文档');
    });

    it('frame 超过帧数时循环', () => {
        expect(computeTabTitle('streaming', 0, '审查文档', 4)).toBe('◐ 运行中 · 审查文档');
    });

    it('compacting 显示脉冲帧 + 压缩中 + 会话标题', () => {
        expect(computeTabTitle('compacting', 0, '审查文档', 0)).toBe('● 压缩中 · 审查文档');
        expect(computeTabTitle('compacting', 0, '审查文档', 5)).toBe('○ 压缩中 · 审查文档');
    });

    it('waiting_permission 显示待审批（静态，无帧），单个不带数量', () => {
        expect(computeTabTitle('waiting_permission', 1, '审查文档', 5)).toBe('🔴 待审批 · 审查文档');
    });

    it('多个待审批显示数量', () => {
        expect(computeTabTitle('waiting_permission', 3, '审查文档')).toBe('🔴 (3) 待审批 · 审查文档');
    });

    it('待审批优先级高于运行中', () => {
        expect(computeTabTitle('streaming', 2, '审查文档', 4)).toBe('🔴 (2) 待审批 · 审查文档');
    });

    it('waiting_permission 但队列恰好为空时仍显示待审批（防御）', () => {
        expect(computeTabTitle('waiting_permission', 0, '审查文档')).toBe('🔴 待审批 · 审查文档');
    });

    it('队列有待审批但 status 未跟上时仍以队列为准', () => {
        expect(computeTabTitle('idle', 1, '审查文档')).toBe('🔴 待审批 · 审查文档');
    });
});

describe('useTabStatus', () => {
    beforeEach(() => {
        act(() => {
            useSessionStore.getState().setStatus('idle');
            usePermissionStore.getState().clearPermissions();
            useMessageStore.setState({ messages: [] });
        });
    });

    afterEach(() => {
        act(() => {
            useSessionStore.getState().setStatus('idle');
            usePermissionStore.getState().clearPermissions();
            useMessageStore.setState({ messages: [] });
        });
        vi.useRealTimers();
        document.title = 'zhikuncode';
    });

    it('默认状态保持原标题', () => {
        renderHook(() => useTabStatus());
        expect(document.title).toBe('zhikuncode');
    });

    it('运行中时标题带会话标题（与顶栏同源），回 idle 后恢复', () => {
        setUserMessage('帮我审查这份文档，哪些值得做？');
        renderHook(() => useTabStatus());
        act(() => useSessionStore.getState().setStatus('streaming'));
        expect(document.title).toBe('◐ 运行中 · 帮我审查这份文档，哪些值得做？');
        act(() => useSessionStore.getState().setStatus('idle'));
        expect(document.title).toBe('zhikuncode');
    });

    it('streaming 时标题按 250ms 一帧旋转（1s/圈）', () => {
        vi.useFakeTimers();
        setUserMessage('某任务');
        renderHook(() => useTabStatus());
        act(() => useSessionStore.getState().setStatus('streaming'));
        expect(document.title).toBe('◐ 运行中 · 某任务');
        act(() => vi.advanceTimersByTime(250));
        expect(document.title).toBe('◓ 运行中 · 某任务');
        act(() => vi.advanceTimersByTime(500));
        expect(document.title).toBe('◒ 运行中 · 某任务');
        act(() => vi.advanceTimersByTime(250));
        expect(document.title).toBe('◐ 运行中 · 某任务'); // 1s 后转满一圈
    });

    it('停止 streaming 后动画定时器被清理', () => {
        vi.useFakeTimers();
        setUserMessage('某任务');
        renderHook(() => useTabStatus());
        act(() => useSessionStore.getState().setStatus('streaming'));
        act(() => useSessionStore.getState().setStatus('idle'));
        const titleAfterIdle = document.title;
        act(() => vi.advanceTimersByTime(1000));
        expect(document.title).toBe(titleAfterIdle);
    });

    it('compacting 显示脉冲帧动画（2s 周期）', () => {
        vi.useFakeTimers();
        setUserMessage('某任务');
        renderHook(() => useTabStatus());
        act(() => useSessionStore.getState().setStatus('compacting'));
        expect(document.title).toBe('● 压缩中 · 某任务');
        act(() => vi.advanceTimersByTime(1000));
        expect(document.title).toBe('○ 压缩中 · 某任务');
        act(() => vi.advanceTimersByTime(1000));
        expect(document.title).toBe('● 压缩中 · 某任务');
    });

    it('待审批到达时标题显示待审批（静态不闪），裁决出队后恢复', () => {
        vi.useFakeTimers();
        setUserMessage('某任务');
        renderHook(() => useTabStatus());
        act(() => {
            usePermissionStore.getState().showPermission(makePermission('p1'));
            useSessionStore.getState().setStatus('waiting_permission');
        });
        expect(document.title).toBe('🔴 待审批 · 某任务');
        const t1 = document.title;
        act(() => vi.advanceTimersByTime(500));
        expect(document.title).toBe(t1); // 待审批保持静态
        act(() => {
            usePermissionStore.getState().respondPermission(makeAllowDecision('p1'));
            useSessionStore.getState().setStatus('idle');
        });
        expect(document.title).toBe('zhikuncode');
    });

    it('多个待审批显示数量，运行中状态被待审批覆盖', () => {
        setUserMessage('某任务');
        renderHook(() => useTabStatus());
        act(() => {
            usePermissionStore.getState().showPermission(makePermission('p1'));
            usePermissionStore.getState().showPermission(makePermission('p2'));
            useSessionStore.getState().setStatus('streaming');
        });
        expect(document.title).toBe('🔴 (2) 待审批 · 某任务');
    });

    it('无用户消息时会话标题兜底为「任务」', () => {
        renderHook(() => useTabStatus());
        act(() => useSessionStore.getState().setStatus('streaming'));
        expect(document.title).toBe('◐ 运行中 · 任务');
    });

    it('unmount 后标题恢复默认且定时器清理', () => {
        vi.useFakeTimers();
        setUserMessage('某任务');
        const { unmount } = renderHook(() => useTabStatus());
        act(() => useSessionStore.getState().setStatus('streaming'));
        expect(document.title).toBe('◐ 运行中 · 某任务');
        unmount();
        expect(document.title).toBe('zhikuncode');
        act(() => vi.advanceTimersByTime(500));
        expect(document.title).toBe('zhikuncode');
    });
});
