/**
 * ThemeProvider — 主题提供者
 * SPEC: §8.7 主题系统
 *
 * 管理主题模式切换 (light/dark/glass/spaceship) 和 CSS 变量应用
 * spaceship 模式追加特效门控 class（fx-cinematic / fx-event / motion-*），
 * 样式实现见 styles/spaceship.css
 */

import React, { useEffect, useCallback, useRef } from 'react';
import { defaultSpaceshipFx, normalizeThemeMode, useConfigStore } from '@/store/configStore';
import { applyAccent, DEFAULT_ACCENT_HEX } from '@/theme/accents';
import { useTokenWarningClass } from '@/hooks/useTokenWarningClass';
import type { ThemeConfig } from '@/types';

interface ThemeProviderProps {
    children: React.ReactNode;
}

/** spaceship 特效门控 class（非 spaceship 模式时必须对称移除） */
const SPACESHIP_FX_CLASSES = ['fx-cinematic', 'fx-event', 'motion-full', 'motion-reduced', 'motion-off'] as const;

/** 开机自检（spaceship-boot class）停留时长，与 spaceship.css 自检动画总时长对齐 */
const SPACESHIP_BOOT_MS = 1200;

export const ThemeProvider: React.FC<ThemeProviderProps> = ({ children }) => {
    const { theme } = useConfigStore();

    // 应用主题到 document
    const applyTheme = useCallback(() => {
        const root = document.documentElement;
        const mode = normalizeThemeMode(theme.mode);

        // 移除旧的 theme class 与 spaceship 特效门控 class（对称清理）
        root.classList.remove('light', 'dark', 'glass', 'system', 'spaceship', ...SPACESHIP_FX_CLASSES);

        // Force reflow to ensure CSS variables are recalculated immediately
        void root.offsetHeight;

        // 根据模式设置
        if (mode === 'glass') {
            // 液态玻璃模式: 添加 glass class，基于浅色方案
            root.classList.add('glass');
        } else if (mode === 'spaceship') {
            // 星舰 HUD 模式：深空暗色系 + 特效门控 class（fx 配置经 normalizeTheme 兜底，此处仍防御）
            root.classList.add('spaceship');
            const fx = theme.spaceshipFx ?? defaultSpaceshipFx();
            if (fx.cinematic) root.classList.add('fx-cinematic');
            if (fx.eventFx) root.classList.add('fx-event');
            root.classList.add(`motion-${fx.motion}`);
        } else {
            root.classList.add(mode);
        }
        
        // v2 强调色令牌（§3.4）：按 effectiveTheme 写入（glass 有独立清透档；主题/强调色变化时随 applyTheme 重算）。
        // 旧 --accent-color/--accent/--color-primary 链路已退役（消费方全部迁移至 v2 令牌）。
        applyAccent(theme.accentColor ?? DEFAULT_ACCENT_HEX, mode);

        // 应用字体大小
        if (theme.fontSize) {
            const fontSizeMap: Record<string, string> = {
                small: '13px',
                medium: '14px',
                large: '16px',
            };
            root.style.setProperty('--font-size', fontSizeMap[theme.fontSize] || '14px');
        }
        
        // 应用圆角
        if (theme.borderRadius) {
            // §8.2 排查结论：sm/md/lg/xl 为圆角档位键名（JS 对象 key），
            // 非 Tailwind 断点前缀，属迁移清单误报，保留不改。
            const radiusMap: Record<string, string> = {
                none: '0px',
                sm: '4px',
                md: '8px',
                lg: '12px',
                xl: '16px',
            };
            root.style.setProperty('--border-radius', radiusMap[theme.borderRadius] || '8px');
        }
    }, [theme]);

    // 初始化和主题变化时应用
    useEffect(() => {
        applyTheme();
    }, [applyTheme]);

    // 事件特效 v4①：TOKEN 警告态监听（只维护 token-warning class；视觉由
    // CSS 组合选择器 html.spaceship.fx-event.token-warning 门控）
    useTokenWarningClass();

    // 事件特效 v4②：开机自检 —— 非 spaceship → spaceship 切换瞬间给 html 加
    // spaceship-boot class（fx-event 且 motion !== 'off' 时），~1.2s 后移除。
    // 初始挂载即 spaceship 视为一次「开机」，同样触发。
    const prevModeRef = useRef<ThemeConfig['mode'] | null>(null);
    const bootTimerRef = useRef<number | undefined>(undefined);
    useEffect(() => {
        const mode = normalizeThemeMode(theme.mode);
        const root = document.documentElement;
        const prev = prevModeRef.current;
        prevModeRef.current = mode;

        if (mode === 'spaceship' && prev !== 'spaceship') {
            const fx = theme.spaceshipFx ?? defaultSpaceshipFx();
            if (fx.eventFx && fx.motion !== 'off') {
                root.classList.add('spaceship-boot');
                window.clearTimeout(bootTimerRef.current);
                bootTimerRef.current = window.setTimeout(() => {
                    root.classList.remove('spaceship-boot');
                    bootTimerRef.current = undefined;
                }, SPACESHIP_BOOT_MS);
            }
        } else if (mode !== 'spaceship' && prev === 'spaceship') {
            // 离开 spaceship：清理可能残留的 boot class / 定时器
            root.classList.remove('spaceship-boot');
            window.clearTimeout(bootTimerRef.current);
            bootTimerRef.current = undefined;
        }
        // 不返回 cleanup：定时器回调仅操作 documentElement class，卸载后触发亦无害；
        // 且避免 StrictMode 双调用把首个定时器清掉导致 class 残留。
    }, [theme]);

    return <>{children}</>;
};

export default ThemeProvider;
