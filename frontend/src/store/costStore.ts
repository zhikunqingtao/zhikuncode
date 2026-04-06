/**
 * CostStore — 费用状态管理
 * SPEC: §8.3 Store #5
 * 持久化: 否 (由 #15 cost_update 权威推送)
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type { Usage } from '@/types';

export interface CostStoreState {
    sessionCost: number;
    totalCost: number;
    usage: Usage;

    updateCost: (data: { sessionCost: number; totalCost: number; usage: Usage }) => void;
    resetSessionCost: () => void;
}

export const useCostStore = create<CostStoreState>()(
    subscribeWithSelector(immer((set) => ({
        sessionCost: 0,
        totalCost: 0,
        usage: { inputTokens: 0, outputTokens: 0, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },

        updateCost: (data) => set(d => {
            d.sessionCost = data.sessionCost;
            d.totalCost = data.totalCost;
            d.usage = data.usage;
        }),
        resetSessionCost: () => set(d => {
            d.sessionCost = 0;
            d.usage = { inputTokens: 0, outputTokens: 0, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 };
        }),
    })))
);
