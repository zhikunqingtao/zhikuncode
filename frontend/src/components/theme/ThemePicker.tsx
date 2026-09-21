/**
 * ThemePicker — 主题选择器
 * SPEC: §8.7 主题系统
 *
 * 提供主题模式、强调色、字体大小等选项的快速切换
 * P1b（指南 §9.6）：6 色按 §3.4 终值；选中态 accent 驱动；字号档与 3 模式逻辑不动
 */

import React from 'react';
import { Sun, Moon, Check, Sparkles } from 'lucide-react';
import { useConfigStore } from '@/store/configStore';
import { ACCENT_PRESETS, normalizeAccentHex } from '@/theme/accents';
import { cn } from '@/components/ui';
import type { ThemeConfig } from '@/types';

export const ThemePicker: React.FC = () => {
    const { theme, setTheme } = useConfigStore();

    const modes: { value: ThemeConfig['mode']; label: string; icon: typeof Sun }[] = [
        { value: 'light', label: '浅色', icon: Sun },
        { value: 'dark', label: '深色', icon: Moon },
        { value: 'glass', label: '液态玻璃', icon: Sparkles },
    ];

    const accentColors = ACCENT_PRESETS.map(({ hex, label }) => ({ value: hex, label }));

    const fontSizes: { value: ThemeConfig['fontSize']; label: string }[] = [
        { value: 'small', label: '小' },
        { value: 'medium', label: '中' },
        { value: 'large', label: '大' },
    ];

    return (
        <div className="p-4 space-y-6">
            {/* 主题模式 */}
            <div>
                <h4 className="text-sm font-medium text-t2 mb-3">主题模式</h4>
                <div className="flex gap-2">
                    {modes.map(({ value, label, icon: Icon }) => {
                        const selected = theme.mode === value;
                        return (
                            <button
                                key={value}
                                onClick={() => setTheme({ mode: value })}
                                className={cn(
                                    'flex-1 flex flex-col items-center gap-1 p-3 rounded-[10px] border transition-interactive duration-fast',
                                    selected
                                        ? 'border-accent2 bg-accent2-soft'
                                        : 'border-hairline text-t1 hover:border-accent2-ring hover:bg-hover2'
                                )}
                            >
                                {/* 选中态：accent 经边/soft 底/图标编码，文字保 t1（§10.1 对比度） */}
                                <Icon className={cn('w-5 h-5', selected ? 'text-accent2-ink' : 'text-t2')} />
                                <span className="text-[13px] text-t1">{label}</span>
                            </button>
                        );
                    })}
                </div>
            </div>

            {/* 强调色 */}
            <div>
                <h4 className="text-sm font-medium text-t2 mb-3">强调色</h4>
                <div className="flex gap-2 flex-wrap">
                    {accentColors.map(({ value, label }) => {
                        const selected = normalizeAccentHex(theme.accentColor) === value;
                        return (
                            <button
                                key={value}
                                onClick={() => setTheme({ accentColor: value })}
                                title={label}
                                className={cn(
                                    'w-8 h-8 rounded-full border-2 transition-interactive duration-fast',
                                    selected
                                        ? 'border-transparent scale-110'
                                        : 'border-transparent hover:scale-105'
                                )}
                                style={{
                                    backgroundColor: value,
                                    /* 选中环 = accent2-ring（boxShadow 方式，§9.6） */
                                    boxShadow: selected ? '0 0 0 3px var(--v2-accent-ring)' : undefined,
                                }}
                            >
                                {selected && (
                                    <Check className="w-4 h-4 text-white mx-auto" />
                                )}
                            </button>
                        );
                    })}
                </div>
            </div>

            {/* 字体大小 */}
            <div>
                <h4 className="text-sm font-medium text-t2 mb-3">字体大小</h4>
                <div className="flex gap-2">
                    {fontSizes.map(({ value, label }) => (
                        <button
                            key={value}
                            onClick={() => setTheme({ fontSize: value })}
                            className={cn(
                                'flex-1 py-2 rounded-[10px] border text-sm transition-interactive duration-fast',
                                theme.fontSize === value
                                    ? 'border-accent2 bg-accent2-soft text-t1'
                                    : 'border-hairline text-t1 hover:border-accent2-ring hover:bg-hover2'
                            )}
                        >
                            {label}
                        </button>
                    ))}
                </div>
            </div>
        </div>
    );
};

export default ThemePicker;
