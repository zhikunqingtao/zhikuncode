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

    /** §3.5 语义色左条（双编码：颜色 + 语义图标） */
    const barClass: Record<string, string> = {
        error: 'bg-err',
        warning: 'bg-warn',
        success: 'bg-ok',
    };

    return (
        <div className="fixed bottom-12 right-4 z-40 flex flex-col gap-2" aria-live="assertive" aria-atomic="false">
            {notifications.map(n => (
                <div
                    key={n.key}
                    role="alert"
                    className="relative overflow-hidden max-w-sm rounded-xl border border-hairline bg-surfacev2 shadow-e3 animate-slide-up"
                >
                    {/* 语义色左条 3px */}
                    <span aria-hidden="true" className={`absolute inset-y-0 left-0 w-[3px] ${barClass[n.level] ?? 'bg-accent2'}`} />
                    <div className="flex items-start justify-between gap-2 py-3 pl-4 pr-3 text-sm text-t1">
                        <span className="leading-relaxed">{n.message}</span>
                        <div className="flex shrink-0 items-center gap-1">
                            {n.level === 'error' && n.onRetry && (
                                <button
                                    onClick={() => { void n.onRetry?.(); }}
                                    className="rounded-md px-2 py-0.5 text-xs font-medium text-accent2-strong hover:bg-accent2-soft transition-interactive duration-fast"
                                >
                                    重试
                                </button>
                            )}
                            <button
                                onClick={() => removeNotification(n.key)}
                                aria-label="关闭通知"
                                className="rounded-md p-0.5 text-t3 transition-interactive duration-fast hover:bg-hover2 hover:text-t1"
                            >
                                ×
                            </button>
                        </div>
                    </div>
                </div>
            ))}
        </div>
    );
}
