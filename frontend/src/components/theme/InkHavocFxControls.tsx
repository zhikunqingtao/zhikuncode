/**
 * InkHavocFxControls — 大闹天宫重彩特效开关共享控件
 * 浓郁模式 Toggle（描金纹样/角标/牌匾装饰层）+ 动效三档 Tabs（完整/精简/关闭），
 * 桌面外观设置（SettingsPanel）与移动端「更多操作」面板共用。
 * 读写 useConfigStore theme/setTheme；inkHavocFx 未持久化前的瞬态用 defaultInkHavocFx 兜底。
 * compact：移动端紧凑形态（标题/间距缩小，动效标签与 Tabs 纵向堆叠）。
 */
import { useState } from 'react';
import { defaultInkHavocFx, useConfigStore } from '@/store/configStore';
import { Button, Tabs, Toggle, cn } from '@/components/ui';
import { InkMountStudio } from './InkMountStudio';
import type { InkHavocFxConfig } from '@/types';

/** 天宫特效 · 动效三档分段控件选项 */
const MOTION_OPTIONS: { value: InkHavocFxConfig['motion']; label: string }[] = [
    { value: 'full', label: '完整' },
    { value: 'reduced', label: '精简' },
    { value: 'off', label: '关闭' },
];

export function InkHavocFxControls({ className, compact = false }: { className?: string; compact?: boolean }) {
    const { theme, setTheme } = useConfigStore();
    // normalizeTheme 保证持久化后恒有值；未持久化前的瞬态用默认值兜底
    const fx = theme.inkHavocFx ?? defaultInkHavocFx();
    // 波次3③ 装裱间弹窗开合（入口仅 ink 主题可见——本控件整体即 ink 限定）
    const [studioOpen, setStudioOpen] = useState(false);

    return (
        <div className={className}>
            <div className={cn('font-medium text-t2', compact ? 'mb-2 text-[13px]' : 'mb-3 text-sm')}>天宫特效</div>
            <div className="space-y-3">
                <div className="flex items-center justify-between gap-4">
                    <div>
                        <div className="text-sm text-t1">浓郁模式</div>
                        <div className="text-xs text-t3">描金纹样/角标/牌匾等装饰层</div>
                    </div>
                    <Toggle
                        aria-label="浓郁模式"
                        checked={fx.cinematic}
                        onCheckedChange={(cinematic) => setTheme({ inkHavocFx: { ...fx, cinematic } })}
                    />
                </div>
                <div className={cn('flex', compact ? 'flex-col items-start gap-2' : 'items-center justify-between gap-4')}>
                    <div className="text-sm text-t1">动效</div>
                    <Tabs
                        aria-label="动效档位"
                        className="w-auto shrink-0"
                        items={MOTION_OPTIONS}
                        value={fx.motion}
                        onValueChange={(motion) => setTheme({ inkHavocFx: { ...fx, motion: motion as InkHavocFxConfig['motion'] } })}
                    />
                </div>
                {/* 波次3② 闭关模式：收起装饰专注书写（挂匾仪式由 InkRetreatCeremony 驱动） */}
                <div className="flex items-center justify-between gap-4">
                    <div>
                        <div className="text-sm text-t1">闭关</div>
                        <div className="text-xs text-t3">收起装饰，专注书写</div>
                    </div>
                    <Toggle
                        aria-label="闭关模式"
                        checked={fx.retreat}
                        onCheckedChange={(retreat) => setTheme({ inkHavocFx: { ...fx, retreat } })}
                    />
                </div>
                {/* 波次3③ 装裱分享：装裱间单张卡片导出 PNG（双档可用） */}
                <div className="flex items-center justify-between gap-4">
                    <div>
                        <div className="text-sm text-t1">装裱分享</div>
                        <div className="text-xs text-t3">会话题名入画，立轴/手卷/册页导出 PNG</div>
                    </div>
                    <Button size="sm" onClick={() => setStudioOpen(true)} aria-haspopup="dialog">
                        装裱分享
                    </Button>
                </div>
            </div>
            <InkMountStudio open={studioOpen} onOpenChange={setStudioOpen} />
        </div>
    );
}

export default InkHavocFxControls;
