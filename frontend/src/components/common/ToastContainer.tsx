/**
 * ToastContainer — 通知 Toast 容器
 * SPEC: §8.2.6a
 * 读取 notificationStore，在右下角展示通知列表
 */

import { useEffect, useRef } from 'react';
import { useNotificationStore } from '@/store/notificationStore';
import { InkSealStamp } from '@/components/theme/InkSealStamp';
import type { NotificationItem } from '@/types';

/** 同时可见的通知上限（审查#5修复：防积压遮挡；超出裁掉最旧） */
const MAX_VISIBLE = 5;

interface NotificationTimer {
    notification: NotificationItem;
    timer: ReturnType<typeof setTimeout>;
}

export function ToastContainer() {
    const notifications = useNotificationStore(s => s.notifications);
    const removeNotification = useNotificationStore(s => s.removeNotification);

    // 同 key 替换也必须重新计时；对象身份区分同一毫秒创建的不同通知。
    const timersRef = useRef(new Map<string, NotificationTimer>());
    useEffect(() => {
        const timers = timersRef.current;
        const currentByKey = new Map(notifications.map(n => [n.key, n]));
        for (const [key, entry] of timers) {
            if (currentByKey.get(key) !== entry.notification) {
                clearTimeout(entry.timer);
                timers.delete(key);
            }
        }
        for (const n of notifications) {
            if (!(n.timeout > 0) || timers.has(n.key)) continue;
            const entry: NotificationTimer = {
                notification: n,
                timer: setTimeout(() => {
                    // 已清理的旧回调不能影响新任务（包括 StrictMode 的同对象重建）。
                    if (timersRef.current.get(n.key) !== entry) return;
                    timersRef.current.delete(n.key);
                    // Store 的替换可能已发生，而 React 尚未运行本次 effect。
                    const store = useNotificationStore.getState();
                    if (store.notifications.find(item => item.key === n.key) === n) {
                        store.removeNotification(n.key);
                    }
                }, Math.max(0, n.createdAt + n.timeout - Date.now())),
            };
            timers.set(n.key, entry);
        }
    }, [notifications]);

    /* 卸载兜底：全清防泄漏 */
    useEffect(() => () => {
        for (const entry of timersRef.current.values()) clearTimeout(entry.timer);
        timersRef.current.clear();
    }, []);

    if (notifications.length === 0) return null;

    /** 超出上限时只展示最新 MAX_VISIBLE 条（最旧的让位，防持续遮挡界面） */
    const visible = notifications.slice(-MAX_VISIBLE);

    /** §3.5 语义色左条（双编码：颜色 + 语义图标） */
    const barClass: Record<string, string> = {
        error: 'bg-err',
        warning: 'bg-warn',
        success: 'bg-ok',
    };

    return (
        <div className="fixed bottom-12 right-4 z-40 flex flex-col gap-2" aria-live="assertive" aria-atomic="false">
            {visible.map(n => (
                <div
                    key={n.key}
                    role="alert"
                    className="toast-card relative overflow-hidden max-w-sm rounded-[14px] border border-hairline bg-surfacev2 shadow-e3 animate-slide-up"
                >
                    {/* 语义色左条 3px */}
                    <span aria-hidden="true" className={`absolute inset-y-0 left-0 w-[3px] ${barClass[n.level] ?? 'bg-accent2'}`} />
                    <div className="flex items-start justify-between gap-2 py-3 pl-4 pr-3 text-sm text-t1">
                        {/* 盖印仪式（波次1增强①）：仅 ink 双模式浓郁档渲染朱印，其余返回 null 不占布局 */}
                        <InkSealStamp level={n.level} />
                        <span className="leading-relaxed">{n.message}</span>
                        <div className="flex shrink-0 items-center gap-1">
                            {n.level === 'error' && n.onRetry && (
                                <button
                                    onClick={() => { void n.onRetry?.(); }}
                                    className="panel-control rounded-md px-2 py-0.5 text-[13px] font-medium text-accent2-ink hover:bg-accent2-soft transition-interactive duration-fast"
                                >
                                    重试
                                </button>
                            )}
                            <button
                                onClick={() => removeNotification(n.key)}
                                aria-label="关闭通知"
                                className="panel-control rounded-md p-0.5 text-t3 transition-interactive duration-fast hover:bg-hover2 hover:text-t1"
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
