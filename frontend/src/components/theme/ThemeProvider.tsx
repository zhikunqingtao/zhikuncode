/**
 * ThemeProvider — 全局主题应用组件
 * SPEC: §8.6
 *
 * 放置在 App 组件树顶层。
 * 从 ConfigStore 读取 ThemeConfig，动态应用 CSS 变量到 :root。
 * 支持 light / dark / system 三种模式。
 *
 * 持久化由 Zustand persist 中间件统一管理 (§8.3 ConfigStore)，
 * 此处不再手动写入 localStorage，避免双重持久化冲突。
 */

import { useEffect, ReactNode } from 'react';
import { useConfigStore } from '@/store/configStore';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import type { ThemeConfig } from '@/types';

// ===== CSS 变量映射 — 对齐 §8.6 =====

const THEME_VARIABLES: Record<'light' | 'dark', Record<string, string>> = {
    light: {
        '--bg-primary':     '#ffffff',
        '--bg-secondary':   '#f8f9fa',
        '--text-primary':   '#1a1a2e',
        '--text-secondary': '#6b7280',
        '--border':         '#e5e7eb',
        '--accent':         'var(--accent-color)',
        '--code-bg':        '#f3f4f6',
        '--diff-add':       '#d4edda',
        '--diff-remove':    '#f8d7da',
    },
    dark: {
        '--bg-primary':     '#0d1117',
        '--bg-secondary':   '#161b22',
        '--text-primary':   '#e6edf3',
        '--text-secondary': '#8b949e',
        '--border':         '#30363d',
        '--accent':         'var(--accent-color)',
        '--code-bg':        '#1c2128',
        '--diff-add':       '#1a4d2e',
        '--diff-remove':    '#4d1a1a',
    },
};

// ===== 字号映射 =====
const FONT_SIZE_MAP: Record<string, string> = {
    small:  '13px',
    medium: '14px',
    large:  '16px',
};

// ===== 圆角映射 =====
const BORDER_RADIUS_MAP: Record<string, string> = {
    none: '0px',
    sm:   '4px',
    md:   '8px',
    lg:   '12px',
};

/**
 * 解析 'system' 模式为实际 light/dark。
 */
function useResolvedThemeMode(mode: ThemeConfig['mode']): 'light' | 'dark' {
    const prefersDark = useMediaQuery('(prefers-color-scheme: dark)');
    if (mode === 'system') return prefersDark ? 'dark' : 'light';
    return mode;
}

interface ThemeProviderProps {
    children: ReactNode;
}

export function ThemeProvider({ children }: ThemeProviderProps) {
    const theme = useConfigStore(s => s.theme);
    const resolvedMode = useResolvedThemeMode(theme.mode);

    // 动态应用 CSS 变量到 :root
    useEffect(() => {
        const root = document.documentElement;
        const vars = THEME_VARIABLES[resolvedMode];

        // 应用主题 CSS 变量
        Object.entries(vars).forEach(([key, value]) => {
            root.style.setProperty(key, value);
        });

        // 应用可配置属性
        root.style.setProperty('--accent-color', theme.accentColor);
        root.style.setProperty('--font-size', FONT_SIZE_MAP[theme.fontSize ?? 'medium'] ?? '14px');
        root.style.setProperty('--font-family-code', theme.fontFamily ?? 'monospace');
        root.style.setProperty('--border-radius', BORDER_RADIUS_MAP[theme.borderRadius ?? 'md'] ?? '8px');

        // 设置 data-theme 属性 (供 CSS 选择器使用)
        root.setAttribute('data-theme', resolvedMode);

        // 切换 Tailwind dark class
        if (resolvedMode === 'dark') {
            root.classList.add('dark');
        } else {
            root.classList.remove('dark');
        }
    }, [resolvedMode, theme.accentColor, theme.fontSize, theme.fontFamily, theme.borderRadius]);

    return <>{children}</>;
}
