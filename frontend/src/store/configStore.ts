/**
 * ConfigStore — 配置状态管理
 * SPEC: §8.3 Store #4
 * 持久化: localStorage (persist middleware)
 * 跨Tab: BroadcastChannel
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { persist, createJSONStorage } from 'zustand/middleware';
import { subscribeWithSelector } from 'zustand/middleware';
import type { ThemeConfig, OutputStyleDef, Config } from '@/types';

export interface ConfigStoreState {
    // 状态
    theme: ThemeConfig;
    locale: string;
    autoCompact: { enabled: boolean; threshold: number };
    verbose: boolean;
    expandedView: boolean;
    outputStyle: { availableStyles: OutputStyleDef[]; activeStyleName: string | null };

    // Actions
    setTheme: (update: Partial<ThemeConfig>) => void;
    resetTheme: () => void;
    setLocale: (locale: string) => void;
    loadConfig: () => Promise<void>;
    saveConfig: (updates: Partial<Config>) => Promise<void>;
    setOutputStyles: (styles: OutputStyleDef[]) => void;
    setActiveOutputStyle: (name: string | null) => void;
}

const DEFAULT_THEME: ThemeConfig = {
    mode: 'system',
    accentColor: '#3b82f6',
    fontSize: 'medium',
    fontFamily: 'monospace',
    borderRadius: 'md',
};

export const useConfigStore = create<ConfigStoreState>()(
    subscribeWithSelector(
        persist(
            immer((set) => ({
                theme: { ...DEFAULT_THEME },
                locale: 'zh-CN',
                autoCompact: { enabled: true, threshold: 80 },
                verbose: false,
                expandedView: false,
                outputStyle: { availableStyles: [] as OutputStyleDef[], activeStyleName: null as string | null },

                setTheme: (update) => set(d => { Object.assign(d.theme, update); }),
                resetTheme: () => set(d => { d.theme = { ...DEFAULT_THEME }; }),
                setLocale: (locale) => set(d => { d.locale = locale; }),
                loadConfig: async () => {
                    // §8.3 loadConfig 实现: 3 次指数退避 + localStorage 降级
                    const RETRY_COUNT = 3;
                    const BASE_DELAY = 300;
                    for (let i = 0; i <= RETRY_COUNT; i++) {
                        try {
                            const resp = await fetch('/api/config');
                            if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
                            const config = await resp.json();
                            set(d => {
                                if (config.theme) d.theme = config.theme;
                                if (config.locale) d.locale = config.locale;
                                if (config.autoCompact) d.autoCompact = config.autoCompact;
                                if (config.verbose !== undefined) d.verbose = config.verbose;
                                if (config.expandedView !== undefined) d.expandedView = config.expandedView;
                                if (config.outputStyle) d.outputStyle = config.outputStyle;
                            });
                            localStorage.setItem('config_cache', JSON.stringify(config));
                            return;
                        } catch {
                            if (i < RETRY_COUNT) {
                                await new Promise(r => setTimeout(r, BASE_DELAY * Math.pow(2, i)));
                            }
                        }
                    }
                    // 降级使用 localStorage 缓存
                    const cached = localStorage.getItem('config_cache');
                    if (cached) {
                        try {
                            const config = JSON.parse(cached);
                            set(d => {
                                if (config.theme) d.theme = config.theme;
                                if (config.locale) d.locale = config.locale;
                                if (config.autoCompact) d.autoCompact = config.autoCompact;
                                if (config.verbose !== undefined) d.verbose = config.verbose;
                                if (config.expandedView !== undefined) d.expandedView = config.expandedView;
                                if (config.outputStyle) d.outputStyle = config.outputStyle;
                            });
                            return;
                        } catch { /* ignore parse error */ }
                    }
                    console.warn('[ConfigStore] loadConfig failed, using defaults');
                },
                saveConfig: async (updates) => {
                    set(d => { Object.assign(d, updates); });
                    await fetch('/api/config', {
                        method: 'PUT',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(updates),
                    });
                },
                setOutputStyles: (styles) => set(d => { d.outputStyle.availableStyles = styles; }),
                setActiveOutputStyle: (name) => set(d => { d.outputStyle.activeStyleName = name; }),
            })),
            {
                name: 'ai-coder-config',
                storage: createJSONStorage(() => localStorage),
                partialize: (s) => ({
                    theme: s.theme,
                    locale: s.locale,
                    autoCompact: s.autoCompact,
                    verbose: s.verbose,
                    expandedView: s.expandedView,
                    outputStyle: s.outputStyle,
                }),
                version: 2,
                migrate: (persisted: unknown, version: number) => {
                    const data = persisted as Record<string, unknown>;
                    if (version <= 1) {
                        const oldTheme = typeof data.theme === 'string' ? data.theme : 'system';
                        return {
                            ...data,
                            theme: { mode: oldTheme, accentColor: '#3b82f6', fontSize: 'medium', fontFamily: 'monospace', borderRadius: 'md' },
                            autoCompact: (data.autoCompact as Record<string, unknown>) ?? { enabled: true, threshold: 80 },
                            expandedView: data.expandedView ?? false,
                            outputStyle: data.outputStyle ?? { availableStyles: [], activeStyleName: null },
                        };
                    }
                    return data;
                },
            }
        )
    )
);
