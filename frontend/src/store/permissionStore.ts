/**
 * PermissionStore — 权限状态管理
 * SPEC: §8.3 Store #3
 * 持久化: 否
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type { PermissionRequest, PermissionDecision, PermissionMode, DenialTrackingState } from '@/types';

export interface PermissionStoreState {
    pendingPermission: PermissionRequest | null;
    permissionMode: PermissionMode;
    denialTracking: DenialTrackingState;

    showPermission: (request: PermissionRequest) => void;
    respondPermission: (decision: PermissionDecision) => void;
    setPermissionMode: (mode: PermissionMode) => void;
    clearPendingPermission: () => void;
}

export const usePermissionStore = create<PermissionStoreState>()(
    subscribeWithSelector(immer((set) => ({
        pendingPermission: null,
        permissionMode: 'default' as PermissionMode,
        denialTracking: { consecutiveDenials: 0, totalDenials: 0 },

        showPermission: (req) => set(d => { d.pendingPermission = req; }),
        respondPermission: (decision) => set(d => {
            d.pendingPermission = null;
            if (decision.decision === 'deny') {
                d.denialTracking.consecutiveDenials++;
                d.denialTracking.totalDenials++;
            } else {
                d.denialTracking.consecutiveDenials = 0;
            }
        }),
        setPermissionMode: (mode) => set(d => { d.permissionMode = mode; }),
        clearPendingPermission: () => set(d => { d.pendingPermission = null; }),
    })))
);
