/**
 * jellySpring 引擎测试：
 * - clamp 边界（sy∈[0.78,1.22] · sx∈[0.82,1.18] 真实生效于写入的 transform）
 * - 空闲停帧（无活动元素自动停 rAF；静止元素唤醒即收敛摘除）
 * - teardownAll 清零（停帧 + 注册表清空 + inline transform/willChange 复位，不复活）
 * - pressSpring 压下/释放目标值 + 回弹过冲；门控语义（calm / motion-off / reduced / RM 失活）
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
    SPRING_CLAMP,
    awakeCount,
    breathMod,
    clampTransform,
    hoverSpring,
    isJellyMotionFull,
    isJellyRich,
    pressSpring,
    readSpring,
    registerSpring,
    registeredCount,
    releaseSpring,
    springsActive,
    teardownAll,
    wake,
} from '../jellySpring';
import { useConfigStore } from '@/store/configStore';
import type { JellyFxConfig } from '@/types';

function setJellyFx(overrides: Partial<JellyFxConfig> = {}): void {
    useConfigStore.getState().setTheme({
        mode: 'jelly',
        jellyFx: { cinematic: true, motion: 'full', ...overrides },
    });
}

function mountEl(): HTMLDivElement {
    const el = document.createElement('div');
    document.body.appendChild(el);
    return el;
}

/** 从 inline transform 提取 scale(sx,sy) */
function readScale(el: HTMLElement): { sx: number; sy: number } {
    const m = /scale\(([\d.]+),([\d.]+)\)/.exec(el.style.transform);
    if (!m) throw new Error(`no scale in transform: "${el.style.transform}"`);
    return { sx: Number(m[1]), sy: Number(m[2]) };
}

afterEach(() => {
    vi.useRealTimers();
    teardownAll();
    document.body.replaceChildren();
    useConfigStore.getState().resetTheme();
});

describe('jellySpring · clampTransform 边界', () => {
    it('超上限/超下限双向钳制（0.78-1.22 / 0.82-1.18）', () => {
        expect(clampTransform(2, 0.5)).toEqual({ sx: SPRING_CLAMP.sxMax, sy: SPRING_CLAMP.syMin });
        expect(clampTransform(0.5, 2)).toEqual({ sx: SPRING_CLAMP.sxMin, sy: SPRING_CLAMP.syMax });
        expect(clampTransform(1.1, 1.05)).toEqual({ sx: 1.1, sy: 1.05 });
    });

    it('tick 写入的 scale 恒被钳制在 [0.82,1.18]×[0.78,1.22]（极端输入 3 / 0.4）', () => {
        vi.useFakeTimers();
        setJellyFx();
        const el = mountEl();
        const s = registerSpring(el, {});
        s.sx.x = 3;
        s.sy.x = 0.4;
        wake(s);
        vi.advanceTimersByTime(64);
        const scale = readScale(el);
        expect(scale.sx).toBeLessThanOrEqual(SPRING_CLAMP.sxMax);
        expect(scale.sx).toBeGreaterThanOrEqual(SPRING_CLAMP.sxMin);
        expect(scale.sy).toBeLessThanOrEqual(SPRING_CLAMP.syMax);
        expect(scale.sy).toBeGreaterThanOrEqual(SPRING_CLAMP.syMin);
    });
});

describe('jellySpring · 空闲停帧', () => {
    it('静止元素被唤醒后一帧内收敛摘除，rAF 自动停帧，inline transform 清空', () => {
        vi.useFakeTimers();
        setJellyFx();
        const el = mountEl();
        const s = registerSpring(el, {});
        wake(s);
        expect(awakeCount()).toBe(1);
        vi.advanceTimersByTime(48);
        expect(awakeCount()).toBe(0);
        expect(el.style.transform).toBe('');
        expect(el.style.willChange).toBe('');
    });

    it('永续 mod（呼吸）保持活动；移除 mod 后重新静止停帧', () => {
        vi.useFakeTimers();
        setJellyFx();
        const el = mountEl();
        const s = registerSpring(el, {});
        s.mod = s.modFn = breathMod(4000, 1.0, 0.02, 1.0, 0.01, 0);
        wake(s);
        vi.advanceTimersByTime(64);
        expect(awakeCount()).toBe(1);
        expect(el.style.transform).not.toBe('');
        s.mod = null;
        vi.advanceTimersByTime(64);
        expect(awakeCount()).toBe(0);
        expect(el.style.transform).toBe('');
    });
});

describe('jellySpring · teardownAll 清零', () => {
    it('切档清场：停帧 + 注册表清空 + 全部元素 inline transform/willChange 复位，且不复活', () => {
        vi.useFakeTimers();
        setJellyFx();
        const el = mountEl();
        const s = registerSpring(el, {});
        s.mod = s.modFn = (t) => ({ sy: 1.03, sx: 1 + 0.01 * Math.sin(t / 30) });
        wake(s);
        vi.advanceTimersByTime(64);
        expect(registeredCount()).toBe(1);
        expect(el.style.transform).not.toBe('');

        teardownAll();
        expect(awakeCount()).toBe(0);
        expect(registeredCount()).toBe(0);
        expect(readSpring(el)).toBeNull();
        expect(el.style.transform).toBe('');
        expect(el.style.willChange).toBe('');

        vi.advanceTimersByTime(128);
        expect(el.style.transform).toBe('');
        // releaseSpring 单元路径：单个退场同样复零
        const el2 = mountEl();
        const s2 = pressSpring(el2, {});
        el2.dispatchEvent(new Event('pointerdown'));
        el2.dispatchEvent(new Event('pointerup'));
        vi.advanceTimersByTime(32);
        releaseSpring(s2);
        expect(el2.style.transform).toBe('');
        expect(readSpring(el2)).toBeNull();
    });
});

describe('jellySpring · 样式与注册所有权', () => {
    beforeEach(() => {
        vi.useFakeTimers();
        setJellyFx();
    });

    it('空闲和释放时恢复原有 inline 样式，释放时恢复 transform-origin', () => {
        const el = mountEl();
        el.style.transform = 'rotate(6deg)';
        el.style.willChange = 'opacity';
        el.style.transformOrigin = '20% 80%';
        const s = hoverSpring(el);
        el.dispatchEvent(new Event('pointerenter'));
        vi.advanceTimersByTime(64);
        expect(el.style.transform).not.toBe('rotate(6deg)');
        el.dispatchEvent(new Event('pointerleave'));
        vi.advanceTimersByTime(4000);
        expect(el.style.transform).toBe('rotate(6deg)');
        expect(el.style.willChange).toBe('opacity');
        releaseSpring(s);
        expect(el.style.transform).toBe('rotate(6deg)');
        expect(el.style.willChange).toBe('opacity');
        expect(el.style.transformOrigin).toBe('20% 80%');
    });

    it('过期 spring 的释放和唤醒不会破坏同元素的新注册', () => {
        const el = mountEl();
        const old = registerSpring(el);
        const current = registerSpring(el);
        current.mod = breathMod(4000, 1, 0.02, 1, 0.01);
        wake(current);
        vi.advanceTimersByTime(64);
        const currentTransform = el.style.transform;
        releaseSpring(old);
        wake(old);
        expect(el.style.transform).toBe(currentTransform);
        expect(registeredCount()).toBe(1);
        expect(awakeCount()).toBe(1);
        releaseSpring(current);
        wake(current);
        vi.advanceTimersByTime(64);
        expect(readSpring(el)).toBeNull();
        expect(awakeCount()).toBe(0);
        expect(el.style.transform).toBe('');
    });
});

describe('jellySpring · pressSpring / hoverSpring', () => {
    beforeEach(() => {
        vi.useFakeTimers();
        setJellyFx();
    });

    it('按压 squash(1.18,0.82) → 释放回弹过冲 + wobble 尾巴', () => {
        const el = mountEl();
        const s = pressSpring(el, { k: 240, c: 11 });
        expect(el.style.transformOrigin).toBe('50% 100%');

        el.dispatchEvent(new Event('pointerdown'));
        expect(s.sx.t).toBe(1.18);
        expect(s.sy.t).toBe(0.82);
        vi.advanceTimersByTime(400);  // 压到位
        const pressed = readScale(el);
        expect(pressed.sy).toBeLessThan(0.9);
        expect(pressed.sy).toBeGreaterThanOrEqual(SPRING_CLAMP.syMin);
        expect(pressed.sx).toBeGreaterThan(1.05);

        el.dispatchEvent(new Event('pointerup'));
        expect(s.wob).not.toBeNull();
        let maxSy = 0;
        for (let i = 0; i < 24; i++) {
            vi.advanceTimersByTime(16);
            maxSy = Math.max(maxSy, readSpring(el)?.sy ?? 0);
        }
        expect(maxSy).toBeGreaterThan(1.0);  // 低阻尼回弹过冲（果冻感）
    });

    it('hover 抬升微弹（lift -1.5 / 1.01 / 0.99），离开复位', () => {
        const el = mountEl();
        const s = hoverSpring(el, { k: 200, c: 13, lift: -1.5, hx: 1.01, hy: 0.99 });
        el.dispatchEvent(new Event('pointerenter'));
        expect(s.ty.t).toBe(-1.5);
        expect(s.sx.t).toBe(1.01);
        expect(s.sy.t).toBe(0.99);
        el.dispatchEvent(new Event('pointerleave'));
        expect(s.ty.t).toBe(0);
        expect(s.sx.t).toBe(1);
        expect(s.sy.t).toBe(1);
    });
});

describe('jellySpring · 门控语义', () => {
    it('非 jelly 模式：wake 失活、不写 transform', () => {
        vi.useFakeTimers();
        useConfigStore.getState().setTheme({ mode: 'light' });
        const el = mountEl();
        const s = registerSpring(el, {});
        expect(springsActive()).toBe(false);
        wake(s);
        expect(awakeCount()).toBe(0);
        vi.advanceTimersByTime(64);
        expect(el.style.transform).toBe('');
    });

    it('jelly + cinematic=false（calm 档）：零弹动', () => {
        vi.useFakeTimers();
        setJellyFx({ cinematic: false });
        const el = mountEl();
        pressSpring(el, {});
        expect(isJellyRich()).toBe(false);
        expect(springsActive()).toBe(false);
        el.dispatchEvent(new Event('pointerdown'));
        vi.advanceTimersByTime(64);
        expect(el.style.transform).toBe('');
        expect(awakeCount()).toBe(0);
    });

    it('motion-off 全静止；motion-reduced 保留事件反馈但关装饰循环门控', () => {
        vi.useFakeTimers();
        setJellyFx({ motion: 'off' });
        expect(isJellyRich()).toBe(true);
        expect(springsActive()).toBe(false);

        setJellyFx({ motion: 'reduced' });
        expect(isJellyRich()).toBe(true);
        expect(springsActive()).toBe(true);
        expect(isJellyMotionFull()).toBe(false);

        setJellyFx({ motion: 'full' });
        expect(isJellyMotionFull()).toBe(true);
    });
});
