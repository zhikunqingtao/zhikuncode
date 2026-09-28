/**
 * DriftSyncBadge — DRIFT SYNCED 徽章（星舰 HUD 事件特效 v4③）
 *
 * 数据源：useSessionStore.status（与 SessionStatusCapsule 同字段）。
 * 仅 spaceship 主题 + spaceshipFx.eventFx + status==='streaming' 时渲染，
 * 其余情况返回 null（非星舰主题/关闭事件特效零副作用）。
 * 样式在 styles/spaceship.css「Event FX」区块：切角小徽章 + 呼吸光环
 * （EVENT-FX 类：motion-full/reduced 播放，motion-off 静止但徽章保留）。
 */

import { defaultSpaceshipFx, useConfigStore } from '@/store/configStore';
import { useSessionStore } from '@/store/sessionStore';

export function DriftSyncBadge() {
    const theme = useConfigStore(s => s.theme);
    const status = useSessionStore(s => s.status);
    // normalizeTheme 保证持久化后恒有值；未持久化前的瞬态用默认值兜底（同 SpaceshipHudLayer）
    const fx = theme.spaceshipFx ?? defaultSpaceshipFx();

    if (theme.mode !== 'spaceship' || !fx.eventFx || status !== 'streaming') return null;

    return (
        <span className="drift-sync-badge" role="status" title="数据流同步中">
            <span className="drift-sync-dot" aria-hidden="true" />
            DRIFT SYNCED
        </span>
    );
}

export default DriftSyncBadge;
