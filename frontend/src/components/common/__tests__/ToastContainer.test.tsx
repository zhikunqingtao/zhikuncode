/**
 * ToastContainer 测试（审查#5修复验证：按 key 独立计时 + 可见数量上限）
 * 旧缺陷复现场景：列表每次变化重置全部倒计时——t=0 加 A(5s)、t=1s 加 B(5s)，
 * A 的倒计时被重建，15s 后 15 条通知全部滞留；修复后 A 仍在 t=5s 准时消失。
 */
import { StrictMode } from 'react';
import { act, cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastContainer } from '../ToastContainer';
import { useNotificationStore } from '@/store/notificationStore';

function resetStore() {
    useNotificationStore.setState({ notifications: [] });
}

function add(key: string, timeout = 5000, message = `通知${key}`) {
    useNotificationStore.getState().addNotification({ key, level: 'info', message, timeout });
}

describe('ToastContainer', () => {
    beforeEach(() => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date('2026-10-04T00:00:00Z'));
        resetStore();
    });
    afterEach(() => {
        cleanup();
        resetStore();
        vi.restoreAllMocks();
        vi.useRealTimers();
    });

    it('新通知加入不重置既有通知的倒计时（审查#5核心场景）', () => {
        render(<ToastContainer />);
        act(() => { add('A', 5000); });
        act(() => { vi.advanceTimersByTime(1000); });
        act(() => { add('B', 5000); });
        // t=5s：A 应准时消失（旧实现 A 的计时被重置，t=6s 才消失）
        act(() => { vi.advanceTimersByTime(4000); });
        expect(useNotificationStore.getState().notifications.map(n => n.key)).toEqual(['B']);
        // t=6s：B 到期消失
        act(() => { vi.advanceTimersByTime(1000); });
        expect(useNotificationStore.getState().notifications).toHaveLength(0);
    });

    it('高频加入下旧通知按各自到期时间依次消失（积压回归）', () => {
        render(<ToastContainer />);
        for (let i = 0; i < 15; i += 1) {
            act(() => { add(`N${i}`, 5000); });
            act(() => { vi.advanceTimersByTime(1000); });
        }
        // 此刻 t=15s：N0..N10（加入于 t=0..10s）均已到期准时消失（N10 恰于 t=15s 到点，
        // 本断言同时验证了「按各自到期时间」的精确性）；N11..N14（t=11..14s 加入）仍在
        const keys = useNotificationStore.getState().notifications.map(n => n.key);
        expect(keys).toEqual(['N11', 'N12', 'N13', 'N14']);
    });

    it('可见数量上限：超出 MAX_VISIBLE(5) 只渲染最新 5 条', () => {
        render(<ToastContainer />);
        act(() => {
            for (let i = 0; i < 8; i += 1) add(`K${i}`, 60_000);
        });
        expect(document.querySelectorAll('.toast-card')).toHaveLength(5);
        expect(document.body.textContent).toContain('通知K7');
        expect(document.body.textContent).not.toContain('通知K0');
    });

    it('同批次关闭并重加同 key 时，新通知不沿用旧通知的截止时间', () => {
        render(<ToastContainer />);
        act(() => { add('M', 5000); });
        act(() => { vi.advanceTimersByTime(4000); });
        act(() => {
            useNotificationStore.getState().removeNotification('M');
            add('M', 5000, '新通知');
        });
        act(() => { vi.advanceTimersByTime(1000); });
        expect(useNotificationStore.getState().notifications[0]?.message).toBe('新通知');
        act(() => { vi.advanceTimersByTime(4000); });
        expect(useNotificationStore.getState().notifications).toHaveLength(0);
    });

    it('同毫秒的同 key 替换仍使用新对象的超时', () => {
        render(<ToastContainer />);
        act(() => { add('M', 1000, '旧通知'); });
        const old = useNotificationStore.getState().notifications[0];
        act(() => { add('M', 5000, '新通知'); });
        const current = useNotificationStore.getState().notifications[0];
        expect(current.createdAt).toBe(old.createdAt);
        expect(current).not.toBe(old);
        expect(document.querySelectorAll('.toast-card')).toHaveLength(1);
        act(() => { vi.advanceTimersByTime(1000); });
        expect(useNotificationStore.getState().notifications).toEqual([current]);
        act(() => { vi.advanceTimersByTime(4000); });
        expect(useNotificationStore.getState().notifications).toHaveLength(0);
    });

    it.each([0, -1])('替换为 timeout=%s 的常驻通知时取消旧倒计时', (timeout) => {
        render(<ToastContainer />);
        act(() => { add('M', 5000); });
        act(() => { vi.advanceTimersByTime(4000); });
        act(() => { add('M', timeout, '常驻通知'); });
        expect(vi.getTimerCount()).toBe(0);
        act(() => { vi.advanceTimersByTime(60_000); });
        expect(useNotificationStore.getState().notifications[0]?.message).toBe('常驻通知');
    });

    it('常驻通知替换为正数超时时开始计时', () => {
        render(<ToastContainer />);
        act(() => { add('M', 0); });
        act(() => { vi.advanceTimersByTime(10_000); });
        act(() => { add('M', 5000, '限时通知'); });
        act(() => { vi.advanceTimersByTime(4999); });
        expect(useNotificationStore.getState().notifications[0]?.message).toBe('限时通知');
        act(() => { vi.advanceTimersByTime(1); });
        expect(useNotificationStore.getState().notifications).toHaveLength(0);
    });

    it('替换 effect 执行前到达的旧回调不能删除 store 中的新通知', () => {
        const schedule = vi.spyOn(globalThis, 'setTimeout');
        render(<ToastContainer />);
        act(() => { add('M', 5000); });
        const oldCallback = schedule.mock.calls.find(([, delay]) => delay === 5000)![0] as () => void;
        act(() => { vi.advanceTimersByTime(4000); });
        act(() => {
            useNotificationStore.getState().removeNotification('M');
            add('M', 5000, '新通知');
            // 模拟旧任务已到执行阶段，而本批次 React effect 尚未处理替换。
            oldCallback();
        });
        expect(useNotificationStore.getState().notifications[0]?.message).toBe('新通知');
        act(() => { vi.advanceTimersByTime(5000); });
        expect(useNotificationStore.getState().notifications).toHaveLength(0);
    });

    it('替换 effect 执行后到达的旧回调不能删除新通知或它的计时任务', () => {
        const schedule = vi.spyOn(globalThis, 'setTimeout');
        render(<ToastContainer />);
        act(() => { add('M', 5000); });
        const oldCallback = schedule.mock.calls.find(([, delay]) => delay === 5000)![0] as () => void;
        act(() => { vi.advanceTimersByTime(4000); });
        act(() => { add('M', 5000, '新通知'); });
        act(() => { oldCallback(); });
        expect(useNotificationStore.getState().notifications[0]?.message).toBe('新通知');
        expect(vi.getTimerCount()).toBe(1);
        act(() => { vi.advanceTimersByTime(5000); });
        expect(useNotificationStore.getState().notifications).toHaveLength(0);
    });

    it('StrictMode 重建同一通知的计时任务后，首轮旧回调不能提前删除通知', () => {
        const schedule = vi.spyOn(globalThis, 'setTimeout');
        add('M', 5000);
        render(<StrictMode><ToastContainer /></StrictMode>);
        const callbacks = schedule.mock.calls.filter(([, delay]) => delay === 5000);
        expect(callbacks).toHaveLength(2);
        act(() => { (callbacks[0][0] as () => void)(); });
        expect(useNotificationStore.getState().notifications).toHaveLength(1);
        expect(vi.getTimerCount()).toBe(1);
        act(() => { vi.advanceTimersByTime(5000); });
        expect(useNotificationStore.getState().notifications).toHaveLength(0);
    });

    it('延迟挂载按 createdAt 的截止时间计时，不重新赠送完整超时', () => {
        add('M', 5000);
        vi.advanceTimersByTime(4000);
        render(<ToastContainer />);
        act(() => { vi.advanceTimersByTime(999); });
        expect(useNotificationStore.getState().notifications).toHaveLength(1);
        act(() => { vi.advanceTimersByTime(1); });
        expect(useNotificationStore.getState().notifications).toHaveLength(0);
    });

    it('卸载清理任务且旧回调不删通知，重挂只等待剩余时间', () => {
        const schedule = vi.spyOn(globalThis, 'setTimeout');
        const view = render(<ToastContainer />);
        act(() => { add('M', 5000); });
        const oldCallback = schedule.mock.calls.find(([, delay]) => delay === 5000)![0] as () => void;
        act(() => { vi.advanceTimersByTime(2000); });
        view.unmount();
        expect(vi.getTimerCount()).toBe(0);
        oldCallback();
        expect(useNotificationStore.getState().notifications).toHaveLength(1);
        vi.advanceTimersByTime(2000);
        render(<ToastContainer />);
        act(() => { vi.advanceTimersByTime(1000); });
        expect(useNotificationStore.getState().notifications).toHaveLength(0);
    });

    it('重挂时已经过期的通知立即到期', () => {
        const view = render(<ToastContainer />);
        act(() => { add('M', 5000); });
        view.unmount();
        vi.advanceTimersByTime(6000);
        render(<ToastContainer />);
        act(() => { vi.advanceTimersByTime(0); });
        expect(useNotificationStore.getState().notifications).toHaveLength(0);
    });

    it('手动关闭和清空都会清理对应计时任务', () => {
        render(<ToastContainer />);
        act(() => { add('A'); add('B'); });
        expect(vi.getTimerCount()).toBe(2);
        act(() => { useNotificationStore.getState().removeNotification('A'); });
        expect(vi.getTimerCount()).toBe(1);
        act(() => { useNotificationStore.getState().clearAll(); });
        expect(vi.getTimerCount()).toBe(0);
    });
});
