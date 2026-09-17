/**
 * SessionStatusIcon — 当前会话状态图标（仅图标无文字）。
 *
 * 与 Header 状态胶囊同款约定：streaming=Loader2 旋转（accent 色），
 * 其余状态=色点 + 8% 光晕（compacting 附加脉冲）。颜色规则统一取自
 * Header 导出的 getSessionStatusMeta，供桌面权限 chip（PermissionMenu）
 * 与手机端权限入口（MobileChoice leading）复用。
 */

import { Loader2 } from 'lucide-react';
import { getSessionStatusMeta } from '@/components/layout/Header';
import { useSessionStore } from '@/store/sessionStore';

export function SessionStatusIcon({ size = 14 }: { size?: number }) {
    const status = useSessionStore(s => s.status);
    const meta = getSessionStatusMeta(status);
    if (meta.spinner) {
        return <Loader2 size={size} aria-hidden="true" className="shrink-0 animate-spin" style={{ color: meta.color }} />;
    }
    const dot = Math.max(6, Math.round(size * 0.6));
    return (
        <span
            aria-hidden="true"
            className={`shrink-0 rounded-full ${meta.pulse ? 'motion-safe:animate-pulse' : ''}`}
            style={{
                width: dot,
                height: dot,
                backgroundColor: meta.color,
                boxShadow: `0 0 0 3px color-mix(in srgb, ${meta.color} 8%, transparent)`,
            }}
        />
    );
}
