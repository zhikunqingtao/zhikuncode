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
            // interactionId 是持久化身份；重试可能按设计复用 toolUseId。
            const idx = d.pendingPermissions.findIndex(p => req.interactionId
                ? p.interactionId === req.interactionId
                : !p.interactionId && p.toolUseId === req.toolUseId);
            if (idx >= 0) {
                d.pendingPermissions[idx] = req;
            } else {
                d.pendingPermissions.push(req);
            }
        }),
        respondPermission: (decision, interactionId) => set(d => {
            // WebSocket 终态可能先于 REST 返回；只移除用户实际回答的请求，不能误删后续排队请求。
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
