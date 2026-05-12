/**
 * DialogManager — 全局对话框管理器
 * 
 * 统一管理应用中所有对话框的显示：
 * - PermissionDialog (权限请求)
 * - ElicitationDialog (反向提问)
 * - SettingsPanel (设置面板)
 */

import React from 'react';
import { useDialogStore } from '@/store/dialogStore';
import { usePermissionStore } from '@/store/permissionStore';
import { useActivityStore } from '@/store/activityStore';
import { useAppUiStore } from '@/store/appUiStore';
import { useSessionStore } from '@/store/sessionStore';
import { sendToServer, sendPermissionResponse } from '@/api/stompClient';
import PermissionDialog from '@/components/permission/PermissionDialog';
import { ElicitationDialog } from '@/components/dialog/ElicitationDialog';
import { SettingsPanel } from '@/components/dialog/SettingsPanel';

export const DialogManager: React.FC = () => {
    const { activeDialog, closeDialog } = useDialogStore();
    const { pendingPermission, respondPermission, clearPendingPermission } = usePermissionStore();
    const { elicitationDialog, dismissElicitationDialog } = useAppUiStore();

    // Handle permission decision — 更新本地状态 + 发送决策到后端
    const handlePermissionDecision = React.useCallback((decision: { toolUseId: string; decision: 'allow' | 'deny'; remember?: boolean; scope?: string }) => {
        // 1. 发送决策到后端（必须先于状态清理，确保 toolUseId 有效）
        sendPermissionResponse(
            decision.toolUseId,
            decision.decision,
            decision.remember,
            decision.scope
        );
        // 2. 更新本地 store 状态
        respondPermission(decision);
        clearPendingPermission();
        // 3. 恢复会话状态
        useSessionStore.getState().setStatus('streaming');
        // 4. 标记 Activity 决策：批准/拒绝
        if (decision.decision === 'allow') {
            useActivityStore.getState().markToolUseApproved(decision.toolUseId);
        } else {
            useActivityStore.getState().markToolUseDenied(decision.toolUseId);
        }
    }, [respondPermission, clearPendingPermission]);

    // Handle elicitation submit — send response to backend via WebSocket
    const handleElicitationSubmit = React.useCallback((requestId: string, response: string | string[]) => {
        sendToServer('/app/elicitation', {
            requestId,
            answer: response,
        });
        dismissElicitationDialog();
        useSessionStore.getState().setStatus('streaming');
    }, [dismissElicitationDialog]);

    // Handle elicitation cancel — send cancellation to backend
    const handleElicitationCancel = React.useCallback(() => {
        if (elicitationDialog?.requestId) {
            sendToServer('/app/elicitation', {
                requestId: elicitationDialog.requestId,
                answer: null, // null indicates cancellation
            });
        }
        dismissElicitationDialog();
        useSessionStore.getState().setStatus('idle');
    }, [elicitationDialog, dismissElicitationDialog]);

    return (
        <>
            {/* Permission Dialog */}
            {pendingPermission && (
                <PermissionDialog
                    request={pendingPermission}
                    onDecision={handlePermissionDecision}
                />
            )}

            {/* Elicitation Dialog */}
            {elicitationDialog && (
                <ElicitationDialog
                    requestId={elicitationDialog.requestId}
                    question={elicitationDialog.question}
                    options={elicitationDialog.options as { value: string; label: string; description?: string }[] | undefined}
                    allowFreeText={!elicitationDialog.options || (elicitationDialog.options as unknown[]).length === 0}
                    onSubmit={handleElicitationSubmit}
                    onCancel={handleElicitationCancel}
                />
            )}

            {/* Settings Panel */}
            {activeDialog === 'settings' && (
                <SettingsPanel onClose={closeDialog} />
            )}
        </>
    );
};

export default DialogManager;
