/**
 * ThemeProvider — 主题提供者
 * SPEC: §8.7 主题系统
 *
 * 管理主题模式切换 (light/dark/glass/spaceship/ink-havoc/ink-havoc-night/jelly) 和 CSS 变量应用
 * spaceship 模式追加特效门控 class（fx-cinematic / fx-event / motion-*），
 * 样式实现见 styles/spaceship.css
 * ink-havoc 双模式追加大闹天宫重彩门控 class（fx-ink-rich / motion-*，与星舰共用 motion-*），
 * 样式实现见 styles/ink-havoc.css
 * jelly 单模式追加果冻主题门控 class（fx-jelly-rich / motion-*，同与星舰共用 motion-*），
 * 样式实现见 styles/jelly.css
 */

import React, { useEffect, useCallback, useRef } from 'react';
import { defaultSpaceshipFx, defaultInkHavocFx, defaultJellyFx, normalizeThemeMode, useConfigStore } from '@/store/configStore';
import { applyAccent, DEFAULT_ACCENT_HEX } from '@/theme/accents';
import { useTokenWarningClass } from '@/hooks/useTokenWarningClass';
import type { ThemeConfig } from '@/types';

interface ThemeProviderProps {
    children: React.ReactNode;
}

/** spaceship 特效门控 class（非 spaceship 模式时必须对称移除） */
const SPACESHIP_FX_CLASSES = ['fx-cinematic', 'fx-event', 'motion-full', 'motion-reduced', 'motion-off'] as const;

/** ink-havoc 浓郁档门控 class（motion-* 与 spaceship 共用，已含于上方清单，无需重复移除）；
    ink-retreat 闭关模式 class 同属 ink 门控，一并对称清理 */
const INK_FX_CLASSES = ['fx-ink-rich', 'ink-retreat'] as const;

/** jelly 果冻主题浓郁档门控 class（motion-* 同与 spaceship 共用，无需重复移除） */
const JELLY_FX_CLASSES = ['fx-jelly-rich'] as const;

/** 开机自检（spaceship-boot class）停留时长，与 spaceship.css 自检动画总时长对齐 */
const SPACESHIP_BOOT_MS = 1200;

/** 入场仪式「开锣亮相」（ink-boot class）停留时长，与 ink-havoc.css 幕布/晕染动画总时长对齐 */
const INK_BOOT_MS = 1600;

/** ink 双模式集合（ink-havoc 浅·花果晨 / ink-havoc-night 深·灵霄夜） */
const INK_MODES: ReadonlySet<ThemeConfig['mode']> = new Set(['ink-havoc', 'ink-havoc-night']);

export const ThemeProvider: React.FC<ThemeProviderProps> = ({ children }) => {
    const { theme } = useConfigStore();

    // 应用主题到 document
    const applyTheme = useCallback(() => {
        const root = document.documentElement;
        const mode = normalizeThemeMode(theme.mode);

        // 移除旧的 theme class 与 spaceship/ink-havoc/jelly 特效门控 class（对称清理，清单合并去重）
        root.classList.remove('light', 'dark', 'glass', 'system', 'spaceship', 'ink-havoc', 'ink-havoc-night', 'jelly',
            ...SPACESHIP_FX_CLASSES, ...INK_FX_CLASSES, ...JELLY_FX_CLASSES);

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
        } else if (mode === 'ink-havoc' || mode === 'ink-havoc-night') {
            // 大闹天宫重彩戏曲风（浅·花果晨 / 深·灵霄夜）+ 浓郁档/动效门控 class
            root.classList.add(mode);
            const fx = theme.inkHavocFx ?? defaultInkHavocFx();
            if (fx.cinematic) root.classList.add('fx-ink-rich');
            // 闭关模式（波次3②）：装饰退场专注书写；fx-retreat 与浓郁/动效档正交叠加
            if (fx.retreat) root.classList.add('ink-retreat');
            root.classList.add(`motion-${fx.motion}`);
        } else if (mode === 'jelly') {
            // 果冻主题（香草奶油 · 法式镜面奢华，浅色单主题）+ 浓郁档/动效门控 class
            // （Q 弹主引擎与装饰层为第二阶段，本阶段 fx-jelly-rich 只放行配色/质感增量）
            root.classList.add('jelly');
            const fx = theme.jellyFx ?? defaultJellyFx();
            if (fx.cinematic) root.classList.add('fx-jelly-rich');
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

    // 入场仪式「开锣亮相」—— mode 进入 ink 集合（非 ink→ink，或 ink-havoc⇄ink-havoc-night
    // 互切）瞬间给 html 加 ink-boot class（cinematic 且 motion !== 'off' 时），~1.6s 后移除。
    // 初始挂载即 ink 视为一次「开锣」，同样触发。幕布滑开/晕染淡入动画见 ink-havoc.css。
    const prevInkModeRef = useRef<ThemeConfig['mode'] | null>(null);
    const inkBootTimerRef = useRef<number | undefined>(undefined);
    useEffect(() => {
        const mode = normalizeThemeMode(theme.mode);
        const root = document.documentElement;
        const prev = prevInkModeRef.current;
        prevInkModeRef.current = mode;

        if (INK_MODES.has(mode) && prev !== mode) {
            const fx = theme.inkHavocFx ?? defaultInkHavocFx();
            if (fx.cinematic && fx.motion !== 'off') {
                root.classList.add('ink-boot');
                window.clearTimeout(inkBootTimerRef.current);
                inkBootTimerRef.current = window.setTimeout(() => {
                    root.classList.remove('ink-boot');
                    inkBootTimerRef.current = undefined;
                }, INK_BOOT_MS);
            }
        } else if (!INK_MODES.has(mode) && prev !== null && INK_MODES.has(prev)) {
            // 离开 ink 集合：清理可能残留的 boot class / 定时器
            root.classList.remove('ink-boot');
            window.clearTimeout(inkBootTimerRef.current);
            inkBootTimerRef.current = undefined;
        }
        // 不返回 cleanup：同 spaceship-boot，避免 StrictMode 双调用清掉首个定时器。
    }, [theme]);

    return <>{children}</>;
};

export default ThemeProvider;
