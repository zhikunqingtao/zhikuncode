/**
 * ToastContainer — 通知 Toast 容器
 * SPEC: §8.2.6a
 * 读取 notificationStore，在右下角展示通知列表
 */

import { useEffect } from 'react';
import { useNotificationStore } from '@/store/notificationStore';

export function ToastContainer() {
    const notifications = useNotificationStore(s => s.notifications);
    const removeNotification = useNotificationStore(s => s.removeNotification);

    // 自动消失定时器
    useEffect(() => {
        const timers: ReturnType<typeof setTimeout>[] = [];
        for (const n of notifications) {
            if (n.timeout && n.timeout > 0) {
                const timer = setTimeout(() => {
                    removeNotification(n.key);
                }, n.timeout);
                timers.push(timer);
            }
        }
        return () => timers.forEach(t => clearTimeout(t));
    }, [notifications, removeNotification]);

    if (notifications.length === 0) return null;

    return (
        <div className="fixed bottom-12 right-4 z-40 flex flex-col gap-2" aria-live="assertive" aria-atomic="false">
            {notifications.map(n => (
                <div
                    key={n.key}
                    role="alert"
                    className={`px-4 py-3 rounded-lg shadow-lg text-sm max-w-sm animate-in slide-in-from-right
                        ${n.level === 'error' ? 'bg-red-500 text-white'
                        : n.level === 'warning' ? 'bg-yellow-500 text-black'
                        : n.level === 'success' ? 'bg-green-500 text-white'
                        : 'bg-[var(--bg-secondary)] border border-[var(--border)]'}`}
                >
                    <div className="flex justify-between items-start gap-2">
                        <span>{n.message}</span>
                        <button
                            onClick={() => removeNotification(n.key)}
                            className="text-xs opacity-70 hover:opacity-100 shrink-0"
                        >
                            ×
                        </button>
                    </div>
                </div>
            ))}
        </div>
    );
}
