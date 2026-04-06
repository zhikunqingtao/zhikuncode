/**
 * NotificationStore — 通知状态管理
 * SPEC: §8.3 Store #9
 * 持久化: 否
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type { NotificationItem, NotificationPriority } from '@/types';

export interface NotificationStoreState {
    notifications: NotificationItem[];

    addNotification: (config: {
        key: string;
        level: 'info' | 'success' | 'warning' | 'error';
        message: string;
        priority?: NotificationPriority;
        timeout?: number;
    }) => void;
    removeNotification: (key: string) => void;
    clearAll: () => void;
}

export const useNotificationStore = create<NotificationStoreState>()(
    subscribeWithSelector(immer((set) => ({
        notifications: [],

        addNotification: (config) => set(d => {
            d.notifications.push({
                key: config.key,
                level: config.level,
                message: config.message,
                priority: config.priority ?? 'normal',
                timeout: config.timeout ?? 5000,
                createdAt: Date.now(),
            });
        }),
        removeNotification: (key) => set(d => {
            d.notifications = d.notifications.filter(n => n.key !== key);
        }),
        clearAll: () => set(d => { d.notifications = []; }),
    })))
);
