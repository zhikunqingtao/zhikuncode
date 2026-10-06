/**
 * JellyFxControls — 果冻主题特效开关共享控件
 * 浓郁档 Toggle（Q 弹与金箔装饰拉满，html class fx-jelly-rich）+ 动效三档 Tabs（完整/精简/关闭），
 * 桌面外观设置（SettingsPanel）与移动端「更多操作」面板共用。
 * 读写 useConfigStore theme/setTheme；jellyFx 未持久化前的瞬态用 defaultJellyFx 兜底。
 * compact：移动端紧凑形态（标题/间距缩小，动效标签与 Tabs 纵向堆叠）。
 * 第二阶段（Q 弹主引擎强度/装饰层开关等）控件位在此扩展。
 */
import { defaultJellyFx, useConfigStore } from '@/store/configStore';
import { Tabs, Toggle, cn } from '@/components/ui';
import type { JellyFxConfig } from '@/types';

/** 果冻特效 · 动效三档分段控件选项 */
const MOTION_OPTIONS: { value: JellyFxConfig['motion']; label: string }[] = [
    { value: 'full', label: '完整' },
    { value: 'reduced', label: '精简' },
    { value: 'off', label: '关闭' },
];

export function JellyFxControls({ className, compact = false }: { className?: string; compact?: boolean }) {
    const { theme, setTheme } = useConfigStore();
    // normalizeTheme 保证持久化后恒有值；未持久化前的瞬态用默认值兜底
    const fx = theme.jellyFx ?? defaultJellyFx();

    return (
        <div className={className}>
            <div className={cn('font-medium text-t2', compact ? 'mb-2 text-[13px]' : 'mb-3 text-sm')}>果冻特效</div>
            <div className="space-y-3">
                <div className="flex items-center justify-between gap-4">
                    <div>
                        <div className="text-sm text-t1">浓郁模式</div>
                        <div className="text-xs text-t3">Q 弹形变与金箔装饰拉满</div>
                    </div>
                    <Toggle
                        aria-label="浓郁模式"
                        checked={fx.cinematic}
                        onCheckedChange={(cinematic) => setTheme({ jellyFx: { ...fx, cinematic } })}
                    />
                </div>
                <div className={cn('flex', compact ? 'flex-col items-start gap-2' : 'items-center justify-between gap-4')}>
                    <div className="text-sm text-t1">动效</div>
                    <Tabs
                        aria-label="动效档位"
                        className="w-auto shrink-0"
                        items={MOTION_OPTIONS}
                        value={fx.motion}
                        onValueChange={(motion) => setTheme({ jellyFx: { ...fx, motion: motion as JellyFxConfig['motion'] } })}
                    />
                </div>
                {/* 第二阶段控件位：Q 弹主引擎强度 / 装饰层（果冻滴·漂浮金箔）开关在此扩展 */}
            </div>
        </div>
    );
}

export default JellyFxControls;
