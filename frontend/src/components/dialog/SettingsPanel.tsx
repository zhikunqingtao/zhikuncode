/** 外观设置：主题立即生效，由 ConfigStore 持久化。 */
import { Moon, Sun, Sparkles, Check } from 'lucide-react';
import { useConfigStore } from '@/store/configStore';
import { Button, Dialog, cn } from '@/components/ui';
import { ACCENT_PRESETS, normalizeAccentHex } from '@/theme/accents';
import type { ThemeConfig } from '@/types';

const THEMES: { mode: ThemeConfig['mode']; label: string; icon: typeof Sun }[] = [
    { mode: 'light', label: '浅色', icon: Sun },
    { mode: 'dark', label: '深色', icon: Moon },
    { mode: 'glass', label: '液态玻璃', icon: Sparkles },
];

export function SettingsPanel({ onClose }: { onClose: () => void }) {
    const { theme, setTheme } = useConfigStore();

    return (
        <Dialog open title="外观设置" onClose={onClose} className="max-w-lg border border-hairline">
            <div className="p-5">
                <div className="grid grid-cols-3 gap-3" role="group" aria-label="主题">
                    {THEMES.map(({ mode, label, icon: Icon }) => (
                        <button
                            key={mode}
                            type="button"
                            aria-pressed={theme.mode === mode}
                            onClick={() => setTheme({ mode })}
                            className={cn(
                                'flex flex-col items-center gap-2 p-4 rounded-[10px] border text-sm text-t1 transition-interactive duration-fast',
                                'focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring',
                                theme.mode === mode
                                    ? 'border-accent2 bg-accent2-soft'
                                    : 'border-hairline hover:bg-hover2',
                            )}
                        >
                            <Icon className="w-5 h-5 text-accent2-ink" aria-hidden="true" />
                            <span>{label}</span>
                        </button>
                    ))}
                </div>
                <div className="mt-5">
                    <div className="text-sm font-medium text-t2 mb-3">强调色</div>
                    <div className="flex gap-2 flex-wrap" role="group" aria-label="强调色">
                        {ACCENT_PRESETS.map(({ hex, label }) => {
                            const selected = normalizeAccentHex(theme.accentColor) === hex;
                            return (
                                <button
                                    key={hex}
                                    type="button"
                                    aria-pressed={selected}
                                    aria-label={`强调色 ${label}`}
                                    title={label}
                                    onClick={() => setTheme({ accentColor: hex })}
                                    className={cn(
                                        'w-8 h-8 rounded-full border-2 border-transparent transition-interactive duration-fast',
                                        'focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring',
                                        selected ? 'scale-110' : 'hover:scale-105',
                                    )}
                                    style={{
                                        backgroundColor: hex,
                                        /* 选中环 = accent2-ring（boxShadow 方式，与 ThemePicker 一致） */
                                        boxShadow: selected ? '0 0 0 3px var(--v2-accent-ring)' : undefined,
                                    }}
                                >
                                    {selected && <Check className="w-4 h-4 text-white mx-auto" aria-hidden="true" />}
                                </button>
                            );
                        })}
                    </div>
                </div>
                <div className="mt-5 flex justify-end">
                    <Button variant="primary" onClick={onClose}>完成</Button>
                </div>
            </div>
        </Dialog>
    );
}

export default SettingsPanel;
