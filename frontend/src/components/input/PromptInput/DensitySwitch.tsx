import { GlassSelection } from '@/components/theme/GlassSelection';
/**
 * 消息密度切换：桌面分段控件，移动端常驻卡片中的单按钮 + 底部 Sheet。
 * 切档沿用现有 setDensity，清除当前会话的手动展开偏好。
 */

import React, { useCallback, useState, useId } from 'react';
import { Rows3, Check, X } from 'lucide-react';
import { SheetShell } from '@/components/apos/MobileBottomSheet';
import { useSessionStore } from '@/store/sessionStore';
import { useTurnViewStore, type TurnDensity } from '@/store/turnViewStore';
import { cn } from '@/components/ui/cn';

const DENSITY_OPTIONS: Array<{ value: TurnDensity; label: string }> = [
    { value: 'compact', label: '简洁' },
    { value: 'balanced', label: '平衡' },
    { value: 'detailed', label: '详细' },
];

function useDensitySelect(): (value: TurnDensity) => void {
    const sessionId = useSessionStore(s => s.sessionId);
    return useCallback((value: TurnDensity) => {
        useTurnViewStore.getState().setDensity(value, sessionId ?? undefined);
    }, [sessionId]);
}

// ==================== 桌面端：紧凑分段控件 ====================

export const DensitySwitch: React.FC = () => {
    const glassId = useId();
    const density = useTurnViewStore(s => s.density);
    const handleSelect = useDensitySelect();

    return (
        <span
            role="tablist"
            aria-label="消息密度"
            data-testid="density-switch"
            className="glass-segments inline-flex h-7 shrink-0 items-center gap-0.5 rounded-full border border-hairline bg-surface2 p-0.5"
        >
            {DENSITY_OPTIONS.map(option => {
                const selected = option.value === density;
                return (
                    <button
                        key={option.value}
                        type="button"
                        role="tab"
                        aria-selected={selected}
                        title={`消息密度：${option.label}`}
                        onClick={() => handleSelect(option.value)}
                        className={cn(
                            'h-6 rounded-full px-2.5 text-[13px] font-medium',
                            'transition-interactive duration-fast',
                            'focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring',
                            selected
                                ? 'bg-surfacev2 text-t1 shadow-e1'
                                : 'text-t2 hover:text-t1',
                        )}
                    >
                        {selected && <GlassSelection id={glassId} />}
                        {option.label}
                    </button>
                );
            })}
        </span>
    );
};

// ==================== 移动端：单按钮 + 三选一菜单 ====================

/** 快捷条 chip 配方（与 MobilePromptBar CHIP_CLASS 一致） */
const MOBILE_CHIP_CLASS = `flex h-11 shrink-0 items-center gap-1.5 rounded-full border border-hairline
    bg-surfacev2 px-2 text-[13px] text-t2 shadow-e1 transition-interactive duration-fast
    active:scale-95`;

/** 菜单项（触控命中 ≥44px，与 MobilePromptBar MENU_ITEM_CLASS 一致） */
const MOBILE_MENU_ITEM_CLASS = `flex h-11 w-full items-center gap-2 px-3.5 text-left text-[13px] text-t2
    transition-interactive duration-fast hover:bg-hover2 active:scale-[.98]`;

export const MobileDensitySwitch: React.FC = () => {
    const density = useTurnViewStore(s => s.density);
    const handleSelect = useDensitySelect();
    const [open, setOpen] = useState(false);
    const close = useCallback(() => setOpen(false), []);
    const current = DENSITY_OPTIONS.find(option => option.value === density)
        ?? DENSITY_OPTIONS[1];

    const handlePick = useCallback((value: TurnDensity) => {
        handleSelect(value);
        setOpen(false);
    }, [handleSelect]);

    return (
        <>
            <button
                type="button"
                aria-label={`消息密度：${current.label}`}
                aria-expanded={open}
                aria-haspopup="dialog"
                data-testid="mobile-density-chip"
                onClick={() => setOpen(v => !v)}
                className={MOBILE_CHIP_CLASS}
            >
                <Rows3 size={14} aria-hidden="true" />
                {current.label}
            </button>
            <SheetShell isOpen={open} onClose={close} ariaLabel="消息密度" header={
                <div className="flex items-center justify-between px-4 pb-2"><h3 className="text-t1 text-base font-semibold">消息密度</h3>
                    <button type="button" aria-label="关闭消息密度" onClick={close} className="panel-control flex h-11 w-11 items-center justify-center rounded-full hover:bg-hover2"><X size={18} /></button>
                </div>
            }>
                <div data-testid="mobile-density-menu" className="px-2 pb-4">
                    {DENSITY_OPTIONS.map(option => (
                        <button key={option.value} type="button" aria-pressed={option.value === density}
                            onClick={() => handlePick(option.value)}
                            className={cn(MOBILE_MENU_ITEM_CLASS, 'min-h-14 rounded-xl', option.value === density && 'bg-accent2-soft text-accent2-ink')}>
                            <span className="flex flex-1 flex-col items-start gap-0.5"><span className="font-medium">{option.label}</span>
                                <span className="text-[13px] text-t3">{option.value === 'compact' ? '问题、过程与回复默认折叠' : option.value === 'balanced' ? '按任务查看执行摘要' : '查看完整过程与任务导航'}</span>
                            </span>
                            {option.value === density && <Check size={18} aria-hidden="true" />}
                        </button>
                    ))}
                </div>
            </SheetShell>
        </>
    );
};
