/**
 * InboxStore — 收件箱状态管理
 * SPEC: §8.3 Store #10
 * 持久化: 否
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type { InboxMessage } from '@/types';

export interface InboxStoreState {
    messages: InboxMessage[];
    unreadCount: number;

    addInboxMessage: (data: { fromId: string; content: string }) => void;
    markAsRead: (messageId: string) => void;
}

export const useInboxStore = create<InboxStoreState>()(
    subscribeWithSelector(immer((set) => ({
        messages: [],
        unreadCount: 0,

        addInboxMessage: (data) => set(d => {
            d.messages.push({
                id: crypto.randomUUID(),
                fromId: data.fromId,
                content: data.content,
                timestamp: Date.now(),
                read: false,
            });
            d.unreadCount++;
        }),
        markAsRead: (id) => set(d => {
            const m = d.messages.find(msg => msg.id === id);
            if (m && !m.read) {
                m.read = true;
                d.unreadCount--;
            }
        }),
    })))
);
