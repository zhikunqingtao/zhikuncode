/**
 * SessionStatusCapsule — 会话状态胶囊（状态图标 + 文案）。
 *
 * Header 桌面右簇与桌面/平板输入区 composer-row 渲染同一组件，
 * 保证两处「就绪/运行中/等待权限/压缩中」视觉完全一致。
 */

import { Loader2 } from 'lucide-react';
import { useSessionStore } from '@/store/sessionStore';
import { getSessionStatusChipTone, getSessionStatusMeta } from './sessionStatusMeta';

export function SessionStatusCapsule() {
    const status = useSessionStore(s => s.status);
    const meta = getSessionStatusMeta(status);
    return (
        <div
            className={`inline-flex shrink-0 items-center gap-1.5 rounded-full border px-2 py-0.5 text-[13px] font-medium leading-5 ${getSessionStatusChipTone(status)}`}
            title="会话状态"
            role="status"
        >
            {meta.spinner ? (
                <Loader2 aria-hidden="true" className="h-3.5 w-3.5 animate-spin" />
            ) : (
                <span
                    aria-hidden="true"
                    className={`h-2 w-2 rounded-full ${meta.pulse ? 'motion-safe:animate-pulse' : ''}`}
                    style={{
                        backgroundColor: meta.color,
                        boxShadow: `0 0 0 3px color-mix(in srgb, ${meta.color} 8%, transparent)`,
                    }}
                />
            )}
            <span>{meta.label}</span>
        </div>
    );
}
