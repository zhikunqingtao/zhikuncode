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
    respondPermission: (decision: PermissionDecision, interactionId?: string) => void;
    setPermissionMode: (mode: PermissionMode) => void;
    clearPermissions: () => void;
    removeInteraction: (interactionId: string) => void;
    updateInteractionDeadline: (interactionId: string, decisionDeadlineAt: number, version?: number) => void;
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
        respondPermission: (decision, interactionId) => set(d => {
            // Terminal WS may arrive before REST resolves. Remove only the request
            // the user actually answered; never shift a subsequently queued request.
            d.pendingPermissions = d.pendingPermissions.filter(p =>
                p.interactionId !== (interactionId ?? decision.toolUseId)
                && p.toolUseId !== decision.toolUseId);
            if (decision.decision === 'deny') {
                d.denialTracking.consecutiveDenials++;
                d.denialTracking.totalDenials++;
            } else {
                d.denialTracking.consecutiveDenials = 0;
            }
        }),
        setPermissionMode: (mode) => set(d => { d.permissionMode = mode; }),
        clearPermissions: () => set(d => { d.pendingPermissions = []; }),
        removeInteraction: (interactionId) => set(d => {
            d.pendingPermissions = d.pendingPermissions.filter(p =>
                p.interactionId !== interactionId && p.toolUseId !== interactionId);
        }),
        updateInteractionDeadline: (interactionId, decisionDeadlineAt, version) => set(d => {
            const pending = d.pendingPermissions.find(p => p.interactionId === interactionId);
            if (pending) {
                pending.decisionDeadlineAt = decisionDeadlineAt;
                if (version !== undefined) pending.version = version;
            }
        }),
    })))
);
