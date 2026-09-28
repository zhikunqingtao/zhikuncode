/**
 * SpaceshipFxControls — 星舰特效三开关共享控件
 * 电影级视觉 Toggle / 事件特效 Toggle / 动效三档 Tabs（完整/精简/关闭），
 * 桌面外观设置（SettingsPanel）与移动端「更多操作」面板共用。
 * 读写 useConfigStore theme/setTheme；spaceshipFx 未持久化前的瞬态用 defaultSpaceshipFx 兜底。
 * compact：移动端紧凑形态（标题/间距缩小，动效标签与 Tabs 纵向堆叠）。
 */
import { defaultSpaceshipFx, useConfigStore } from '@/store/configStore';
import { Tabs, Toggle, cn } from '@/components/ui';
import type { SpaceshipFxConfig } from '@/types';

/** 星舰特效 · 动效三档分段控件选项 */
const MOTION_OPTIONS: { value: SpaceshipFxConfig['motion']; label: string }[] = [
    { value: 'full', label: '完整' },
    { value: 'reduced', label: '精简' },
    { value: 'off', label: '关闭' },
];

export function SpaceshipFxControls({ className, compact = false }: { className?: string; compact?: boolean }) {
    const { theme, setTheme } = useConfigStore();
    // normalizeTheme 保证持久化后恒有值；未持久化前的瞬态用默认值兜底
    const fx = theme.spaceshipFx ?? defaultSpaceshipFx();

    return (
        <div className={className}>
            <div className={cn('font-medium text-t2', compact ? 'mb-2 text-[13px]' : 'mb-3 text-sm')}>星舰特效</div>
            <div className="space-y-3">
                <div className="flex items-center justify-between gap-4">
                    <div>
                        <div className="text-sm text-t1">电影级视觉</div>
                        <div className="text-xs text-t3">雷达/框架/刻度尺等装饰层</div>
                    </div>
                    <Toggle
                        aria-label="电影级视觉"
                        checked={fx.cinematic}
                        onCheckedChange={(cinematic) => setTheme({ spaceshipFx: { ...fx, cinematic } })}
                    />
                </div>
                <div className="flex items-center justify-between gap-4">
                    <div>
                        <div className="text-sm text-t1">事件特效</div>
                        <div className="text-xs text-t3">TOKEN 警告/开机自检/DRIFT 同步</div>
                    </div>
                    <Toggle
                        aria-label="事件特效"
                        checked={fx.eventFx}
                        onCheckedChange={(eventFx) => setTheme({ spaceshipFx: { ...fx, eventFx } })}
                    />
                </div>
                <div className={cn('flex', compact ? 'flex-col items-start gap-2' : 'items-center justify-between gap-4')}>
                    <div className="text-sm text-t1">动效</div>
                    <Tabs
                        aria-label="动效档位"
                        className="w-auto shrink-0"
                        items={MOTION_OPTIONS}
                        value={fx.motion}
                        onValueChange={(motion) => setTheme({ spaceshipFx: { ...fx, motion: motion as SpaceshipFxConfig['motion'] } })}
                    />
                </div>
            </div>
        </div>
    );
}

export default SpaceshipFxControls;
