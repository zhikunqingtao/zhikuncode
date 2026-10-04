/**
 * InkRetreatCeremony 测试（审查#8修复验证：动效档切换不清除驻留定时器）
 * 旧缺陷复现场景：闭关仪式播放中切换「完整→精简」，effect cleanup 清掉定时器，
 * 新 effect 因 retreat 未变提前返回，phase 无人清除，匾永驻；修复后按原计划消失。
 */
import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { InkRetreatCeremony } from '../InkRetreatCeremony';
import { useConfigStore } from '@/store/configStore';

const INK_FX_FULL = { cinematic: true, motion: 'full' as const, retreat: false };

function setInkTheme(retreat: boolean, motion: 'full' | 'reduced' | 'off' = 'full') {
    useConfigStore.setState({
        theme: {
            mode: 'ink-havoc-night',
            accentColor: '#12967F',
            inkHavocFx: { ...INK_FX_FULL, motion, retreat },
        },
    });
}

describe('InkRetreatCeremony', () => {
    beforeEach(() => {
        vi.useFakeTimers();
        setInkTheme(false);
    });
    afterEach(() => {
        vi.useRealTimers();
    });

    it('retreat 翻转播放落匾，驻留 1600ms 后摘除', () => {
        render(<InkRetreatCeremony />);
        act(() => { setInkTheme(true); });
        expect(document.querySelector('.ink-retreat-plaque')).not.toBeNull();
        act(() => { vi.advanceTimersByTime(1600); });
        expect(document.querySelector('.ink-retreat-plaque')).toBeNull();
    });

    it('仪式播放中切换动效档，匾仍按原定时器消失（审查#8核心场景）', () => {
        render(<InkRetreatCeremony />);
        act(() => { setInkTheme(true); });                 // 落匾开始（驻留 1600ms）
        act(() => { vi.advanceTimersByTime(400); });
        act(() => { setInkTheme(true, 'reduced'); });      // 播放中切「完整→精简」
        expect(document.querySelector('.ink-retreat-plaque')).not.toBeNull();
        act(() => { vi.advanceTimersByTime(1200); });      // 原 1600ms 到点
        expect(document.querySelector('.ink-retreat-plaque')).toBeNull();
    });

    it('摘匾仪式（retreat→false）1000ms 后摘除', () => {
        render(<InkRetreatCeremony />);
        act(() => { setInkTheme(true); });
        act(() => { vi.advanceTimersByTime(1600); });
        act(() => { setInkTheme(false); });
        expect(document.querySelector('.ink-retreat-plaque')).not.toBeNull();
        act(() => { vi.advanceTimersByTime(1000); });
        expect(document.querySelector('.ink-retreat-plaque')).toBeNull();
    });

    it('初次挂载 retreat 已为 true 时不播仪式（刷新不无故挂匾）', () => {
        setInkTheme(true);
        render(<InkRetreatCeremony />);
        act(() => { vi.advanceTimersByTime(3000); });
        expect(document.querySelector('.ink-retreat-plaque')).toBeNull();
    });
});
