/**
 * useKeyboardScrollCompensation — §8.8.3 移动键盘滚动补偿（bug B 修复核心语义）
 * - atBottom=true：键盘弹起把可视高度压缩后，把增高 delta 追加到 scrollTop，
 *   滚动位置重新锚定真实底部；键盘收起（delta<0）不回拉；
 * - atBottom=false（用户上翻）：不做 delta 补偿，保留阅读位置（显式需求：
 *   用户上翻时不得触发补偿滚动）；
 * - prevKeyboardHeight 无条件跟踪：atBottom 翻转后不跨缺口二次补偿；
 * - 键盘开启瞬间（0→>0）无条件启动沉降锚底循环（P2b-2b）：不等 atBottom
 *   翻转 —— 实测 Virtuoso atBottomStateChange(true) 在过渡窗口内不送达
 *   React，以其为启动条件会导致循环永不接管（e2e T2 实测残留 276px 距底
 *   缺口）；接续 App 桥「弹起滚底」契约，每帧把 scrollTop 断言到真实底部，
 *   直至布局稳定；用户上滚手势立即停让。
 */

import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useKeyboardScrollCompensation, useVirtualKeyboard } from './useVirtualKeyboard';

function makeList(scrollTop: number) {
    const el = document.createElement('div');
    el.scrollTop = scrollTop;
    return { listRef: { current: el }, el };
}

/** 带真实几何（scrollHeight/clientHeight 可变）的滚动容器，驱动沉降循环 */
function makeScrollableList(scrollTop: number, scrollHeight: number, clientHeight: number) {
    const el = document.createElement('div');
    el.scrollTop = scrollTop;
    const geo = { scrollHeight, clientHeight };
    Object.defineProperty(el, 'scrollHeight', { get: () => geo.scrollHeight, configurable: true });
    Object.defineProperty(el, 'clientHeight', { get: () => geo.clientHeight, configurable: true });
    return { listRef: { current: el }, el, geo };
}

const waitFrames = (ms: number) => new Promise(r => setTimeout(r, ms));

function renderCompensation(listRef: { current: HTMLDivElement }, h: number, atBottom: boolean) {
    return renderHook(
        ({ height, at }: { height: number; at: boolean }) =>
            useKeyboardScrollCompensation(listRef, height, at),
        { initialProps: { height: h, at: atBottom } },
    );
}

describe('useKeyboardScrollCompensation', () => {
    it('atBottom=true：键盘弹起 delta 追加到 scrollTop（锚定真实底部），收起不回拉', () => {
        const { listRef, el } = makeList(100);
        const { rerender } = renderCompensation(listRef, 0, true);

        rerender({ height: 276, at: true });
        expect(el.scrollTop).toBe(376);

        // 键盘收起（delta<0）不反向回拉
        rerender({ height: 0, at: true });
        expect(el.scrollTop).toBe(376);
    });

    it('atBottom=false（用户上翻）：不补偿，保留阅读位置', () => {
        const { listRef, el } = makeList(500);
        const { rerender } = renderCompensation(listRef, 0, false);

        rerender({ height: 276, at: false });
        expect(el.scrollTop).toBe(500);
    });

    it('atBottom 翻转后无跨缺口补偿（prev 无条件跟踪，delta=0 不突跳）', () => {
        const { listRef, el } = makeList(500);
        const { rerender } = renderCompensation(listRef, 0, false);

        // 上翻期间键盘弹起：不补偿，但 prev 已跟踪到 276
        rerender({ height: 276, at: false });
        expect(el.scrollTop).toBe(500);

        // 回到底部、高度未再变化：delta=0，不二次补偿
        rerender({ height: 276, at: true });
        expect(el.scrollTop).toBe(500);
    });

    it('键盘开启瞬间 atBottom=false 亦启动沉降锚底（不等 atBottom 翻转，P2b-2b）', async () => {
        // 1000 内容高 / 400 可视高，用户上翻在顶部（gap=600）
        const { listRef, el, geo } = makeScrollableList(0, 1000, 400);
        const { rerender } = renderCompensation(listRef, 0, false);

        // 开启瞬间：atBottom 尚未翻转（实测 Virtuoso true 事件不送达），
        // 循环仍须启动并每帧断言真实底部（App 桥弹起滚底契约接续）
        rerender({ height: 212, at: false });
        await waitFrames(60);
        expect(el.scrollTop).toBe(600);

        // 布局沉降（clientHeight 收缩 40px）→ 目标底部增大，循环继续锚底
        geo.clientHeight = 360;
        await waitFrames(60);
        expect(el.scrollTop).toBe(640);
    });

    it('沉降锚底期间用户上滚手势立即停让（不再重新断言底部）', async () => {
        const { listRef, el, geo } = makeScrollableList(0, 1000, 400);
        const { rerender } = renderCompensation(listRef, 0, false);

        rerender({ height: 212, at: false });
        await waitFrames(60);
        expect(el.scrollTop).toBe(600); // 循环已锚底

        // 用户上滚手势 → 停让（WheelEvent 构造器在部分 jsdom 版本不可用，用 Event 模拟）
        const wheelUp = new Event('wheel');
        Object.defineProperty(wheelUp, 'deltaY', { value: -100 });
        el.dispatchEvent(wheelUp);

        // 内容继续增高（沉降），循环已停让：scrollTop 保持用户离开时的位置
        geo.scrollHeight = 2000;
        await waitFrames(100);
        expect(el.scrollTop).toBe(600);
    });
});


describe('useVirtualKeyboard viewport sizing', () => {
    afterEach(() => {
        vi.unstubAllGlobals();
        vi.useRealTimers();
        document.body.replaceChildren();
    });

    function viewport() {
        vi.useFakeTimers();
        const vv = Object.assign(new EventTarget(), { height: 700, offsetTop: 0, scale: 1 });
        vi.stubGlobal('visualViewport', vv);
        vi.stubGlobal('innerWidth', 390);
        const resize = (height: number, offsetTop = 0) => act(() => {
            Object.assign(vv, { height, offsetTop });
            vv.dispatchEvent(new Event('resize'));
            vi.advanceTimersByTime(20);
        });
        return { vv, resize };
    }
    const css = (name: string) => document.documentElement.style.getPropertyValue(name);
    const focusInput = () => {
        const input = document.createElement('textarea');
        document.body.appendChild(input);
        act(() => { input.focus(); vi.advanceTimersByTime(20); });
    };

    it('measures immediately, distinguishes unfocused resizes, and clears styles on disable', () => {
        const { resize } = viewport();
        const { result, rerender } = renderHook(({ enabled }) => useVirtualKeyboard(enabled), { initialProps: { enabled: true } });
        expect(css('--viewport-height')).toBe('700px');
        resize(450);
        expect(css('--viewport-height')).toBe('450px');
        expect(result.current.isKeyboardVisible).toBe(false);
        rerender({ enabled: false });
        expect(css('--viewport-height')).toBe('');
        expect(css('--viewport-offset-top')).toBe('');
        resize(600);
        expect(css('--viewport-height')).toBe('');
    });

    it('tracks keyboard height and viewport pan without treating pinch zoom or rotation as a keyboard', () => {
        const { vv, resize } = viewport();
        const { result } = renderHook(() => useVirtualKeyboard());
        focusInput();
        resize(400, 30);
        expect(result.current.keyboardHeight).toBe(300);
        expect(css('--viewport-offset-top')).toBe('30px');
        vv.scale = 2;
        resize(200, 100);
        expect(css('--viewport-height')).toBe('400px');
        vv.scale = 1;
        resize(700);
        expect(result.current.keyboardHeight).toBe(0);
        vi.stubGlobal('innerWidth', 820);
        resize(390);
        expect(result.current.keyboardHeight).toBe(0);
        expect(css('--viewport-height')).toBe('390px');
    });

    it('falls back to innerHeight when VisualViewport is unavailable', () => {
        vi.useFakeTimers();
        vi.stubGlobal('visualViewport', undefined);
        vi.stubGlobal('innerHeight', 640);
        renderHook(() => useVirtualKeyboard());
        expect(css('--viewport-height')).toBe('640px');
        act(() => {
            vi.stubGlobal('innerHeight', 400);
            window.dispatchEvent(new Event('resize'));
            vi.advanceTimersByTime(20);
        });
        expect(css('--viewport-height')).toBe('400px');
        expect(css('--keyboard-height')).toBe('0px');
    });
});
