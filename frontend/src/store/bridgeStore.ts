/**
 * BridgeStore — 桥接状态管理
 * SPEC: §8.3 Store #8
 * 持久化: 否
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type { BridgeHandle, BridgeConfig } from '@/types';

export interface BridgeStoreState {
    bridgeStatus: 'connected' | 'disconnected' | 'reconnecting';
    bridgeHandle: BridgeHandle | null;

    connect: (config: BridgeConfig) => Promise<void>;
    disconnect: () => void;
    updateBridgeStatus: (data: { status: string; url: string }) => void;
}

export const useBridgeStore = create<BridgeStoreState>()(
    subscribeWithSelector(immer((set) => ({
        bridgeStatus: 'disconnected' as const,
        bridgeHandle: null,

        connect: async (_config) => {
            // STOMP 连接逻辑见 §8.5
            set(d => { d.bridgeStatus = 'reconnecting'; });
        },
        disconnect: () => set(d => {
            d.bridgeStatus = 'disconnected';
            d.bridgeHandle = null;
        }),
        updateBridgeStatus: (data) => set(d => {
            d.bridgeStatus = data.status as BridgeStoreState['bridgeStatus'];
            if (d.bridgeHandle) {
                d.bridgeHandle.status = data.status as BridgeHandle['status'];
                d.bridgeHandle.url = data.url;
            }
        }),
    })))
);
