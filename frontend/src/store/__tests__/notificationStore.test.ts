import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useNotificationStore } from '../notificationStore';

describe('notificationStore', () => {
    beforeEach(() => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date('2026-10-04T00:00:00Z'));
        useNotificationStore.getState().clearAll();
    });

    afterEach(() => {
        useNotificationStore.getState().clearAll();
        vi.useRealTimers();
    });

    it('同 key 替换为新通知并移至队尾，其他通知的身份与顺序不变', () => {
        const store = useNotificationStore.getState();
        const retry = vi.fn();
        store.addNotification({ key: 'A', level: 'error', message: '旧通知', timeout: 1000, onRetry: retry });
        store.addNotification({ key: 'B', level: 'info', message: 'B' });
        store.addNotification({ key: 'C', level: 'warning', message: 'C' });
        const [oldA, b, c] = useNotificationStore.getState().notifications;

        // 同毫秒替换：createdAt 可相同，但通知对象必须不同。
        store.addNotification({ key: 'A', level: 'success', message: '新通知', timeout: 0 });
        const notifications = useNotificationStore.getState().notifications;
        expect(notifications.map(n => n.key)).toEqual(['B', 'C', 'A']);
        expect(notifications[0]).toBe(b);
        expect(notifications[1]).toBe(c);
        expect(notifications[2]).not.toBe(oldA);
        expect(notifications[2]).toEqual({
            key: 'A', level: 'success', message: '新通知', priority: 'normal',
            timeout: 0, createdAt: oldA.createdAt, onRetry: undefined,
        });
    });

    it('不同 key 的添加、删除、清空和默认超时保持原语义', () => {
        const store = useNotificationStore.getState();
        store.addNotification({ key: 'A', level: 'info', message: 'A' });
        store.addNotification({ key: 'B', level: 'error', message: 'B', priority: 'urgent', timeout: -1 });
        expect(useNotificationStore.getState().notifications.map(n => n.timeout)).toEqual([5000, -1]);
        store.removeNotification('A');
        expect(useNotificationStore.getState().notifications.map(n => n.key)).toEqual(['B']);
        expect(useNotificationStore.getState().notifications[0].priority).toBe('urgent');
        store.clearAll();
        expect(useNotificationStore.getState().notifications).toEqual([]);
    });
});
