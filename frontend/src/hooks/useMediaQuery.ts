/**
 * useMediaQuery — 响应式媒体查询 Hook
 * SPEC: §8.8.1
 *
 * 监听 CSS 媒体查询匹配状态，支持 SSR 安全。
 * 用于响应式断点检测、prefers-color-scheme 跟随等。
 */

import { useState, useEffect, useCallback } from 'react';

/**
 * 监听 CSS 媒体查询，返回当前是否匹配。
 *
 * @example
 * const isMobile = useMediaQuery('(max-width: 639px)');
 * const prefersDark = useMediaQuery('(prefers-color-scheme: dark)');
 * const reducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)');
 */
export function useMediaQuery(query: string): boolean {
    const getMatch = useCallback((): boolean => {
        if (typeof window === 'undefined') return false;
        return window.matchMedia(query).matches;
    }, [query]);

    const [matches, setMatches] = useState(getMatch);

    useEffect(() => {
        const mql = window.matchMedia(query);

        // 初始同步
        setMatches(mql.matches);

        const handler = (e: MediaQueryListEvent) => {
            setMatches(e.matches);
        };

        // 使用 addEventListener (现代浏览器) 或 addListener (旧浏览器兼容)
        if (mql.addEventListener) {
            mql.addEventListener('change', handler);
        } else {
            mql.addListener(handler);
        }

        return () => {
            if (mql.removeEventListener) {
                mql.removeEventListener('change', handler);
            } else {
                mql.removeListener(handler);
            }
        };
    }, [query]);

    return matches;
}

/**
 * 预定义断点 hooks — 对齐 §8.8.1 BREAKPOINTS
 */
export function useIsMobile(): boolean {
    return useMediaQuery('(max-width: 639px)');
}

export function useIsTablet(): boolean {
    return useMediaQuery('(min-width: 640px) and (max-width: 1023px)');
}

export function useIsDesktop(): boolean {
    return useMediaQuery('(min-width: 1024px)');
}

export function usePrefersDark(): boolean {
    return useMediaQuery('(prefers-color-scheme: dark)');
}

export function usePrefersReducedMotion(): boolean {
    return useMediaQuery('(prefers-reduced-motion: reduce)');
}
