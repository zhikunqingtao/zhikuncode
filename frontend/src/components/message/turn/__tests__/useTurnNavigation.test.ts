import { act, renderHook } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { VirtuosoHandle } from 'react-virtuoso';
import { useTurnNavigation } from '../useTurnNavigation';

afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

it('初始定位覆盖首次跳转时，等待目标挂载后按真实位置滚动', () => {
    vi.useFakeTimers();
    vi.spyOn(performance, 'now').mockImplementation(() => Date.now());
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
    const scroller = document.createElement('div');
    scroller.getBoundingClientRect = () => ({ top: 50 } as DOMRect);
    scroller.scrollTo = vi.fn();
    const scrollToIndex = vi.fn();
    const entry = { key: 'turn-0', title: '第 1 轮', turnIndex: 0 };
    const scrollerRef = { current: scroller };
    const virtuosoRef = { current: { scrollToIndex } as unknown as VirtuosoHandle };
    const { result, unmount } = renderHook(() => useTurnNavigation([entry], false, null, scrollerRef, virtuosoRef));
    act(() => result.current.select(entry));
    act(() => vi.advanceTimersByTime(150));
    expect(scrollToIndex.mock.calls.length).toBeGreaterThan(1);
    const anchor = document.createElement('div');
    anchor.dataset.turnIndex = '0';
    anchor.getBoundingClientRect = () => ({ top: 300 } as DOMRect);
    scroller.append(anchor);
    act(() => vi.advanceTimersByTime(100));
    expect(scroller.scrollTo).toHaveBeenCalledWith({ top: 250, behavior: 'smooth' });
    expect(result.current.activeKey).toBe('turn-0');
    unmount();
});
