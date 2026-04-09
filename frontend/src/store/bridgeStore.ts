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
    bridgeStatus: 'connected' | 'disconnected' | 'reconnecting' | 'error';
    bridgeHandle: BridgeHandle | null;
    reconnectAttempt: number;
    nextRetryAt: number | null;
    connectedAt: number | null;
    lastError: string | null;

    connect: (config: BridgeConfig) => Promise<void>;
    disconnect: () => void;
    updateBridgeStatus: (data: {
        status: string;
        url: string;
        connectedAt?: number;
        nextRetryAt?: number;
        attempt?: number;
        error?: string;
    }) => void;
}

export const useBridgeStore = create<BridgeStoreState>()(
    subscribeWithSelector(immer((set) => ({
        bridgeStatus: 'disconnected' as const,
        bridgeHandle: null,
        reconnectAttempt: 0,
        nextRetryAt: null as number | null,
        connectedAt: null as number | null,
        lastError: null as string | null,

        connect: async (_config) => {
            // STOMP 连接逻辑见 §8.5
            set(d => { d.bridgeStatus = 'reconnecting'; });
        },
        disconnect: () => set(d => {
            d.bridgeStatus = 'disconnected';
            d.bridgeHandle = null;
            d.connectedAt = null;
        }),
        updateBridgeStatus: (data) => set(d => {
            d.bridgeStatus = data.status as BridgeStoreState['bridgeStatus'];
            if (data.connectedAt) d.connectedAt = data.connectedAt;
            if (data.nextRetryAt) d.nextRetryAt = data.nextRetryAt;
            if (data.attempt !== undefined) d.reconnectAttempt = data.attempt;
            if (data.error) d.lastError = data.error;
            if (data.status === 'connected') {
                d.reconnectAttempt = 0;
                d.nextRetryAt = null;
                d.lastError = null;
            }
            if (d.bridgeHandle) {
                d.bridgeHandle.status = data.status as BridgeHandle['status'];
                d.bridgeHandle.url = data.url;
            }
        }),
    })))
);
