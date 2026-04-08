/**
 * ThemePicker — 主题选择器
 * SPEC: §8.7 主题系统
 *
 * 提供主题模式、强调色、字体大小等选项的快速切换
 */

import React from 'react';
import { Sun, Moon, Monitor, Check } from 'lucide-react';
import { useConfigStore } from '@/store/configStore';
import type { ThemeConfig } from '@/types';

export const ThemePicker: React.FC = () => {
    const { theme, setTheme } = useConfigStore();

    const modes: { value: ThemeConfig['mode']; label: string; icon: typeof Sun }[] = [
        { value: 'light', label: '浅色', icon: Sun },
        { value: 'dark', label: '深色', icon: Moon },
        { value: 'system', label: '系统', icon: Monitor },
    ];

    const accentColors = [
        { value: '#3b82f6', label: '蓝色' },
        { value: '#8b5cf6', label: '紫色' },
        { value: '#ec4899', label: '粉色' },
        { value: '#f59e0b', label: '橙色' },
        { value: '#10b981', label: '绿色' },
        { value: '#ef4444', label: '红色' },
    ];

    const fontSizes: { value: ThemeConfig['fontSize']; label: string }[] = [
        { value: 'small', label: '小' },
        { value: 'medium', label: '中' },
        { value: 'large', label: '大' },
    ];

    return (
        <div className="p-4 space-y-6">
            {/* 主题模式 */}
            <div>
                <h4 className="text-sm font-medium text-[var(--text-secondary)] mb-3">主题模式</h4>
                <div className="flex gap-2">
                    {modes.map(({ value, label, icon: Icon }) => (
                        <button
                            key={value}
                            onClick={() => setTheme({ mode: value })}
                            className={`flex-1 flex flex-col items-center gap-1 p-3 rounded-lg border transition-all
                                ${theme.mode === value
                                    ? 'border-blue-500 bg-blue-500/10'
                                    : 'border-[var(--border)] hover:border-blue-500/50 hover:bg-[var(--bg-hover)]'
                                }`}
                        >
                            <Icon className={`w-5 h-5 ${theme.mode === value ? 'text-blue-500' : 'text-[var(--text-secondary)]'}`} />
                            <span className={`text-xs ${theme.mode === value ? 'text-blue-500' : 'text-[var(--text-primary)]'}`}>
                                {label}
                            </span>
                        </button>
                    ))}
                </div>
            </div>

            {/* 强调色 */}
            <div>
                <h4 className="text-sm font-medium text-[var(--text-secondary)] mb-3">强调色</h4>
                <div className="flex gap-2 flex-wrap">
                    {accentColors.map(({ value, label }) => (
                        <button
                            key={value}
                            onClick={() => setTheme({ accentColor: value })}
                            title={label}
                            className={`w-8 h-8 rounded-full border-2 transition-all
                                ${theme.accentColor === value
                                    ? 'border-[var(--text-primary)] scale-110'
                                    : 'border-transparent hover:scale-105'
                                }`}
                            style={{ backgroundColor: value }}
                        >
                            {theme.accentColor === value && (
                                <Check className="w-4 h-4 text-white mx-auto" />
                            )}
                        </button>
                    ))}
                </div>
            </div>

            {/* 字体大小 */}
            <div>
                <h4 className="text-sm font-medium text-[var(--text-secondary)] mb-3">字体大小</h4>
                <div className="flex gap-2">
                    {fontSizes.map(({ value, label }) => (
                        <button
                            key={value}
                            onClick={() => setTheme({ fontSize: value })}
                            className={`flex-1 py-2 rounded-lg border text-sm transition-all
                                ${theme.fontSize === value
                                    ? 'border-blue-500 bg-blue-500/10 text-blue-500'
                                    : 'border-[var(--border)] hover:border-blue-500/50 hover:bg-[var(--bg-hover)] text-[var(--text-primary)]'
                                }`}
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
