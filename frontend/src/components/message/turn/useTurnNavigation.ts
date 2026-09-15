import { useCallback, useEffect, useRef, useState, type RefObject } from 'react';
import type { VirtuosoHandle } from 'react-virtuoso';
import { useTurnViewStore } from '@/store/turnViewStore';
import type { TurnNavigationEntry } from './turnNavigation';

export function useTurnNavigation(entries: TurnNavigationEntry[], enabled: boolean, sessionId: string | null,
    scrollerRef: RefObject<HTMLDivElement | null>, virtuosoRef: RefObject<VirtuosoHandle | null>) {
    const [activeKey, setActiveKey] = useState<string | null>(null);
    const cancelRef = useRef<() => void>(() => {});
    const cancelNavigation = useCallback(() => cancelRef.current(), []);
    useEffect(() => cancelNavigation, [cancelNavigation]);
    const select = useCallback((entry: TurnNavigationEntry) => {
        cancelRef.current();
        if (sessionId && entry.expandKey) useTurnViewStore.getState().setSectionExpanded(sessionId, entry.expandKey, true);
        let frame = 0;
        let cancelled = false;
        const started = performance.now();
        let lastMountAttempt = started;
        let lastTop: number | undefined;
        let stable = 0;
        const cancel = () => { cancelled = true; cancelAnimationFrame(frame); };
        cancelRef.current = cancel;
        // 先挂载虚拟轮，再按真实分节位置定位；连续测量稳定后才开始平滑滚动。
        virtuosoRef.current?.scrollToIndex({ index: entry.turnIndex, align: 'start', behavior: 'auto' });
        const locate = () => {
            if (cancelled) return;
            const scroller = scrollerRef.current;
            const anchor = scroller?.querySelector<HTMLElement>(entry.expandKey
                ? `[data-navigation-key="${entry.key}"]` : `[data-turn-index="${entry.turnIndex}"]`);
            if (scroller && anchor) {
                const top = scroller.scrollTop + anchor.getBoundingClientRect().top - scroller.getBoundingClientRect().top;
                stable = lastTop !== undefined && Math.abs(top - lastTop) < 1 ? stable + 1 : 0;
                lastTop = top;
                if (stable >= 3) {
                    scroller.scrollTo({ top, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
                    setActiveKey(entry.key);
                    return;
                }
            } else if (performance.now() - lastMountAttempt >= 100) {
                // 切会话时 Virtuoso 的初始定位可能晚于首次点击，等待目标轮真正挂载。
                virtuosoRef.current?.scrollToIndex({ index: entry.turnIndex, align: 'start', behavior: 'auto' });
                lastMountAttempt = performance.now();
            }
            if (performance.now() - started < 2000) frame = requestAnimationFrame(locate);
        };
        frame = requestAnimationFrame(locate);
    }, [sessionId, scrollerRef, virtuosoRef]);

    useEffect(() => {
        if (!enabled) return;
        const scroller = scrollerRef.current;
        if (!scroller) return;
        let frame = 0;
        const update = () => {
            frame = 0;
            const top = scroller.getBoundingClientRect().top + 16;
            let selected: string | null = null;
            for (const entry of entries) {
                const anchor = scroller.querySelector<HTMLElement>(entry.expandKey
                    ? `[data-navigation-key="${entry.key}"]` : `[data-turn-index="${entry.turnIndex}"]`);
                if (!anchor) continue;
                if (selected === null || anchor.getBoundingClientRect().top <= top) selected = entry.key;
                if (anchor.getBoundingClientRect().top > top) break;
            }
            if (selected !== null) setActiveKey(selected);
        };
        const schedule = () => { if (!frame) frame = requestAnimationFrame(update); };
        scroller.addEventListener('scroll', schedule, { passive: true });
        scroller.addEventListener('wheel', cancelNavigation, { passive: true });
        scroller.addEventListener('touchstart', cancelNavigation, { passive: true });
        const observer = new MutationObserver(schedule);
        observer.observe(scroller, { childList: true, subtree: true });
        const resize = new ResizeObserver(schedule);
        resize.observe(scroller);
        schedule();
        return () => {
            cancelAnimationFrame(frame);
            observer.disconnect(); resize.disconnect();
            scroller.removeEventListener('scroll', schedule);
            scroller.removeEventListener('wheel', cancelNavigation);
            scroller.removeEventListener('touchstart', cancelNavigation);
            cancelNavigation();
        };
    }, [entries, enabled, sessionId, scrollerRef, cancelNavigation]);
    return { activeKey, select, cancelNavigation };
}
