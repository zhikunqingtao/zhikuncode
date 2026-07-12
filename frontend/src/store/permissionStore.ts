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
    pendingPermissions: PermissionRequest[];
    permissionMode: PermissionMode;
    denialTracking: DenialTrackingState;

    showPermission: (request: PermissionRequest) => void;
    respondPermission: (decision: PermissionDecision) => void;
    setPermissionMode: (mode: PermissionMode) => void;
    clearPermissions: () => void;
}

export const usePermissionStore = create<PermissionStoreState>()(
    subscribeWithSelector(immer((set) => ({
        pendingPermissions: [],
        permissionMode: 'default' as PermissionMode,
        denialTracking: { consecutiveDenials: 0, totalDenials: 0 },

        showPermission: (req) => set(d => {
            // 去重：如果同 toolUseId 已存在则更新，否则添加
            const idx = d.pendingPermissions.findIndex(p => p.toolUseId === req.toolUseId);
            if (idx >= 0) {
                d.pendingPermissions[idx] = req;
            } else {
                d.pendingPermissions.push(req);
            }
        }),
        respondPermission: (decision) => set(d => {
            // 移除队列中第一个（当前展示的）请求
            if (d.pendingPermissions.length > 0) {
                d.pendingPermissions.shift();
            }
            if (decision.decision === 'deny') {
                d.denialTracking.consecutiveDenials++;
                d.denialTracking.totalDenials++;
            } else {
                d.denialTracking.consecutiveDenials = 0;
            }
        }),
        setPermissionMode: (mode) => set(d => { d.permissionMode = mode; }),
        clearPermissions: () => set(d => { d.pendingPermissions = []; }),
    })))
);
