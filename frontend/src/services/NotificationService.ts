/**
 * NotificationService — Phase 2 推送通知服务
 * 浏览器原生通知 + Toast 降级机制
 */

import { useNotificationStore } from '@/store/notificationStore';

class NotificationServiceImpl {
    private permissionState: NotificationPermission = 'default';
    private isSupported = false;

    async init(): Promise<void> {
        this.isSupported = 'Notification' in window;
        if (this.isSupported) {
            this.permissionState = Notification.permission;
        }
    }

    async requestPermission(): Promise<boolean> {
        if (!this.isSupported) return false;
        if (this.permissionState === 'granted') return true;
        if (this.permissionState === 'denied') return false;

        const result = await Notification.requestPermission();
        this.permissionState = result;
        return result === 'granted';
    }

    send(title: string, options: {
        body?: string;
        tag?: string;
        data?: Record<string, unknown>;
        fallbackToast?: boolean;
    } = {}): void {
        if (this.permissionState === 'granted' && this.isSupported) {
            try {
                const notification = new Notification(title, {
                    icon: '/favicon.svg',
                    tag: options.tag,
                    body: options.body,
                });
                notification.onclick = () => {
                    window.focus();
                    notification.close();
                    if (options.data?.workerId) {
                        window.dispatchEvent(new CustomEvent('navigate-to-worker', {
                            detail: { workerId: options.data.workerId }
                        }));
                    }
                };
            } catch {
                // Notification 构造失败时降级
                this.sendToast(title, options.body);
            }
        } else if (options.fallbackToast !== false) {
            this.sendToast(title, options.body);
        }
    }

    private sendToast(title: string, body?: string): void {
        const store = useNotificationStore.getState();
        store.addNotification({
            key: `push-${Date.now()}`,
            level: 'warning',
            message: body ? `${title}: ${body}` : title,
            timeout: 8000,
        });
    }

    get canNotify(): boolean {
        return this.isSupported && this.permissionState === 'granted';
    }
}

export const notificationService = new NotificationServiceImpl();
