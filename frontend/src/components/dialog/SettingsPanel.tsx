/** 外观设置：主题立即生效，由 ConfigStore 持久化。 */
import { Moon, Sun, Sparkles, Check, Rocket, Flower2, Landmark } from 'lucide-react';
import { useConfigStore } from '@/store/configStore';
import { Button, Dialog, cn } from '@/components/ui';
import { SpaceshipFxControls } from '@/components/theme/SpaceshipFxControls';
import { InkHavocFxControls } from '@/components/theme/InkHavocFxControls';
import { ACCENT_PRESETS, normalizeAccentHex } from '@/theme/accents';
import type { ThemeConfig } from '@/types';

const THEMES: { mode: ThemeConfig['mode']; label: string; icon: typeof Sun }[] = [
    { mode: 'light', label: '浅色', icon: Sun },
    { mode: 'dark', label: '深色', icon: Moon },
    { mode: 'glass', label: '液态玻璃', icon: Sparkles },
    { mode: 'spaceship', label: '星舰', icon: Rocket },
    { mode: 'ink-havoc', label: '花果晨', icon: Flower2 },
    { mode: 'ink-havoc-night', label: '灵霄夜', icon: Landmark },
];

export function SettingsPanel({ onClose }: { onClose: () => void }) {
    const { theme, setTheme } = useConfigStore();
    const spaceshipMode = theme.mode === 'spaceship';
    // 大闹天宫重彩双模式：accent 写死主题色（朱砂/鎏金），强调色预设区禁用并说明
    const inkHavocMode = theme.mode === 'ink-havoc' || theme.mode === 'ink-havoc-night';

    return (
        <Dialog open title="外观设置" onClose={onClose} className="max-w-lg border border-hairline max-h-[85vh] flex flex-col">
            {/* 审查#3修复：主题 6 格两行致弹窗增高，小视口（1024×500/600）下按钮溢出且滚动锁定——
                弹窗限高 85vh，内容区独立滚动，「完成」按钮固定底部常驻可见 */}
            <div className="p-5 overflow-y-auto min-h-0 flex-1">
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
                {/* 星舰特效：仅 spaceship 主题可见（其他主题隐藏） */}
                {spaceshipMode && <SpaceshipFxControls className="mt-5 border-t border-hairline pt-5" />}
                {/* 天宫特效：仅 ink-havoc 双主题可见（其他主题隐藏） */}
                {inkHavocMode && <InkHavocFxControls className="mt-5 border-t border-hairline pt-5" />}
                <div className="mt-5">
                    <div className="text-sm font-medium text-t2 mb-3">强调色</div>
                    {/* ink 双模式 accent 写死主题色（applyAccent 提前返回）：禁用预设并说明 */}
                    {inkHavocMode && (
                        <div className="mb-3 text-[13px] text-t3">本主题使用专属重彩配色</div>
                    )}
                    <div className="flex gap-2 flex-wrap" role="group" aria-label="强调色">
                        {ACCENT_PRESETS.map(({ hex, label }) => {
                            const selected = !inkHavocMode && normalizeAccentHex(theme.accentColor) === hex;
                            return (
                                <button
                                    key={hex}
                                    type="button"
                                    aria-pressed={selected}
                                    aria-label={`强调色 ${label}`}
                                    title={label}
                                    disabled={inkHavocMode}
                                    onClick={() => setTheme({ accentColor: hex })}
                                    className={cn(
                                        'w-8 h-8 rounded-full border-2 border-transparent transition-interactive duration-fast',
                                        'focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring',
                                        inkHavocMode ? 'opacity-40 cursor-not-allowed' : selected ? 'scale-110' : 'hover:scale-105',
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
            </div>
            <div className="px-5 pb-5 pt-3 border-t border-hairline flex justify-end shrink-0">
                <Button variant="primary" onClick={onClose}>完成</Button>
            </div>
        </Dialog>
    );
}

export default SettingsPanel;
