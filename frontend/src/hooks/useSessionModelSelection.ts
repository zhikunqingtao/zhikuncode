import { useSyncExternalStore } from 'react';
import { isSessionBindingReady, subscribeSessionBinding } from '@/api/dispatch';
import { isWsConnected, sendSetModel } from '@/api/stompClient';
import { useAppUiStore } from '@/store/appUiStore';
import { useBridgeStore } from '@/store/bridgeStore';
import { useModelStore } from '@/store/modelStore';
import { useNotificationStore } from '@/store/notificationStore';
import { useSessionStore } from '@/store/sessionStore';
import { useResponsive } from './useResponsive';

/** Shared by the header, desktop composer and mobile model sheet. */
export function useSessionModelSelection() {
    const sessionId = useSessionStore(s => s.sessionId);
    const bridgeStatus = useBridgeStore(s => s.bridgeStatus);
    const mobileNavTab = useAppUiStore(s => s.mobileNavTab);
    const loading = useModelStore(s => s.loading);
    const models = useModelStore(s => s.models);
    const { isMobile, isTablet } = useResponsive();
    const bound = useSyncExternalStore(subscribeSessionBinding,
        () => Boolean(sessionId && isSessionBindingReady(sessionId)));
    const inDetail = Boolean(sessionId) && !((isMobile || isTablet) && mobileNavTab);
    const disabledReason = loading || models.length === 0 ? '模型暂不可用'
        : !sessionId ? undefined
        : !inDetail ? '进入会话后可切换模型'
        : bridgeStatus !== 'connected' ? '连接恢复后可切换模型'
        : !bound ? '会话连接完成后可切换模型' : undefined;

    const selectModel = (newModel: string) => {
        // Read live state: an open selector may outlive a navigation or rebind.
        const current = useSessionStore.getState();
        const modelState = useModelStore.getState();
        if (current.sessionId !== sessionId || modelState.loading
                || !modelState.models.some(model => model.id === newModel)) return;
        // Home has no server Session yet; keep a local selection for its first creation.
        if (!sessionId) {
            current.setModel(newModel);
            return;
        }
        if ((window.innerWidth < 1024 && useAppUiStore.getState().mobileNavTab)
                || !isSessionBindingReady(sessionId)
                || useBridgeStore.getState().bridgeStatus !== 'connected'
                || !isWsConnected()) return;

        const previousModel = current.model;
        current.setModel(newModel);
        try {
            sendSetModel(newModel);
            return;
        } catch {
            // Keep the displayed model consistent when publishing fails.
        }
        useSessionStore.setState({ model: previousModel });
        useNotificationStore.getState().addNotification({
            key: 'model-send-failed', level: 'error',
            message: '模型切换发送失败，请检查连接后重试',
        });
    };

    return { disabled: Boolean(disabledReason), disabledReason, selectModel };
}
