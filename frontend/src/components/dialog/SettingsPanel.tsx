/** 外观设置：主题立即生效，由 ConfigStore 持久化。 */
import { Moon, Sun, Sparkles } from 'lucide-react';
import { useConfigStore } from '@/store/configStore';
import { Button, Dialog, cn } from '@/components/ui';
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
                                'flex flex-col items-center gap-2 p-4 rounded-lg border text-sm text-t1 transition-interactive duration-fast',
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
                <div className="mt-5 flex justify-end">
                    <Button variant="primary" onClick={onClose}>完成</Button>
                </div>
            </div>
        </Dialog>
    );
}

export default SettingsPanel;
