/**
 * ThemeProvider — 主题提供者
 * SPEC: §8.7 主题系统
 *
 * 管理主题模式切换 (light/dark/glass) 和 CSS 变量应用
 */

import React, { useEffect, useCallback } from 'react';
import { normalizeThemeMode, useConfigStore } from '@/store/configStore';
import { applyAccent } from '@/theme/accents';
import { resolveTheme } from '@/styles/design-tokens';

interface ThemeProviderProps {
    children: React.ReactNode;
}

export const ThemeProvider: React.FC<ThemeProviderProps> = ({ children }) => {
    const { theme } = useConfigStore();

    // 应用主题到 document
    const applyTheme = useCallback(() => {
        const root = document.documentElement;
        const mode = normalizeThemeMode(theme.mode);
        
        // 移除旧的 theme class
        root.classList.remove('light', 'dark', 'glass', 'system');
        
        // Force reflow to ensure CSS variables are recalculated immediately
        void root.offsetHeight;
        
        // 根据模式设置
        if (mode === 'glass') {
            // 液态玻璃模式: 添加 glass class，基于浅色方案
            root.classList.add('glass');
        } else {
            root.classList.add(mode);
        }
        
        // 应用强调色
        if (theme.accentColor) {
            root.style.setProperty('--accent-color', theme.accentColor);
        }
        // v2 强调色令牌（§3.4）：--accent-color 保留给旧组件；
        // v2 令牌按 effectiveTheme 写入（glass→light，主题/强调色变化时随 applyTheme 重算）
        applyAccent(theme.accentColor ?? '#6366F1', resolveTheme(mode));

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

    return <>{children}</>;
};

export default ThemeProvider;
