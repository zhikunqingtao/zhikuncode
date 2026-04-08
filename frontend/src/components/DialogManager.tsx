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
import { useAppUiStore } from '@/store/appUiStore';
import PermissionDialog from '@/components/permission/PermissionDialog';
import { ElicitationDialog } from '@/components/dialog/ElicitationDialog';
import { SettingsPanel } from '@/components/dialog/SettingsPanel';

export const DialogManager: React.FC = () => {
    const { activeDialog, closeDialog } = useDialogStore();
    const { pendingPermission, respondPermission, clearPendingPermission } = usePermissionStore();
    const { elicitationDialog, dismissElicitationDialog } = useAppUiStore();

    // Handle permission decision
    const handlePermissionDecision = React.useCallback((decision: { toolUseId: string; decision: 'allow' | 'deny'; remember?: boolean; scope?: string }) => {
        respondPermission(decision);
        clearPendingPermission();
    }, [respondPermission, clearPendingPermission]);

    // Handle elicitation submit
    const handleElicitationSubmit = React.useCallback((requestId: string, response: string | string[]) => {
        // TODO: Send elicitation response to backend
        console.log('[Elicitation] Response:', requestId, response);
        dismissElicitationDialog();
    }, [dismissElicitationDialog]);

    // Handle elicitation cancel
    const handleElicitationCancel = React.useCallback(() => {
        dismissElicitationDialog();
    }, [dismissElicitationDialog]);

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
