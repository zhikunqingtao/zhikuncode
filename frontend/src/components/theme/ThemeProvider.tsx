/**
 * ThemeProvider — 主题提供者
 * SPEC: §8.7 主题系统
 *
 * 管理主题模式切换 (light/dark/system/glass) 和 CSS 变量应用
 */

import React, { useEffect, useCallback } from 'react';
import { useConfigStore } from '@/store/configStore';

interface ThemeProviderProps {
    children: React.ReactNode;
}

export const ThemeProvider: React.FC<ThemeProviderProps> = ({ children }) => {
    const { theme } = useConfigStore();

    // 应用主题到 document
    const applyTheme = useCallback(() => {
        const root = document.documentElement;
        
        // 移除旧的 theme class
        root.classList.remove('light', 'dark', 'glass');
        
        // 根据模式设置
        if (theme.mode === 'glass') {
            // 液态玻璃模式: 添加 glass class，基于浅色方案
            root.classList.add('glass');
        } else if (theme.mode === 'system') {
            // 检测系统偏好
            const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
            root.classList.add(prefersDark ? 'dark' : 'light');
        } else {
            root.classList.add(theme.mode);
        }
        
        // 应用强调色
        if (theme.accentColor) {
            root.style.setProperty('--accent-color', theme.accentColor);
        }
        
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

    // 监听系统主题变化 (仅 system 模式需要)
    useEffect(() => {
        if (theme.mode !== 'system') return;
        
        const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
        const handler = () => applyTheme();
        
        mediaQuery.addEventListener('change', handler);
        return () => mediaQuery.removeEventListener('change', handler);
    }, [theme.mode, applyTheme]);

    // 初始化和主题变化时应用
    useEffect(() => {
        applyTheme();
    }, [applyTheme]);

    return <>{children}</>;
};

export default ThemeProvider;
