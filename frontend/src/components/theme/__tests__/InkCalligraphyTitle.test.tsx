/**
 * InkCalligraphyTitle（波次2② 毛笔书写标题）门控与渲染测试
 * 覆盖：主题/浓郁档门控（calm 档与其他主题回退文本）、SVG 字形结构
 * （8 字 / contour 数 / 「构建」高亮）、书写错峰 delay 内联变量、
 * 以及 calligraphyStrokeDelay / calligraphyFillDelay 节奏纯函数。
 */

import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
    InkCalligraphyTitle,
    calligraphyFillDelay,
    calligraphyStrokeDelay,
} from '../InkCalligraphyTitle';
import { HERO_CALLIGRAPHY } from '../hero-calligraphy';
import { useConfigStore } from '@/store/configStore';

const FALLBACK = <span data-testid="fallback-title">今天想构建什么？</span>;

function setTheme(mode: string, cinematic: boolean, motion: 'full' | 'reduced' | 'off' = 'full') {
    act(() => {
        useConfigStore.getState().setTheme({
            mode: mode as never,
            inkHavocFx: { cinematic, motion, retreat: false },
        });
    });
}

describe('InkCalligraphyTitle 门控与渲染', () => {
    beforeEach(() => {
        act(() => {
            useConfigStore.getState().resetTheme();
        });
    });

    afterEach(() => {
        act(() => {
            useConfigStore.getState().resetTheme();
        });
    });

    it('非 ink 主题（light）→ 回退 fallback 文本，不渲染 SVG', () => {
        setTheme('light', true);
        const { container } = render(<InkCalligraphyTitle fallback={FALLBACK} />);
        expect(container.querySelector('.ink-calli-title')).toBeNull();
        expect(container.textContent).toContain('今天想构建什么？');
    });

    it('ink + cinematic=false（克制档）→ 回退 fallback 文本（calm 零变化）', () => {
        setTheme('ink-havoc-night', false);
        const { container } = render(<InkCalligraphyTitle fallback={FALLBACK} />);
        expect(container.querySelector('.ink-calli-title')).toBeNull();
        expect(container.textContent).toContain('今天想构建什么？');
    });

    it('ink-havoc-night 浓郁档 → 渲染 SVG：8 字、contour 全数、可访问名', () => {
        setTheme('ink-havoc-night', true);
        const { container } = render(<InkCalligraphyTitle fallback={FALLBACK} />);
        const svg = container.querySelector('svg.ink-calli-title');
        expect(svg).not.toBeNull();
        expect(svg!.getAttribute('aria-label')).toBe('今天想构建什么？');
        // 8 字分组
        expect(svg!.querySelectorAll('g').length).toBe(HERO_CALLIGRAPHY.chars.length);
        // contour 总数与字形数据一致（pathLength=1 单位化路径长）
        const totalContours = HERO_CALLIGRAPHY.chars.reduce((n, c) => n + c.paths.length, 0);
        const paths = svg!.querySelectorAll('path.ink-calli-path');
        expect(paths.length).toBe(totalContours);
        expect(paths[0].getAttribute('pathLength')).toBe('1');
    });

    it('「构建」两字 contour 全部带 is-accent（对应原文案 <b> 高亮）', () => {
        setTheme('ink-havoc', true);
        const { container } = render(<InkCalligraphyTitle fallback={FALLBACK} />);
        const expected = HERO_CALLIGRAPHY.chars
            .filter(c => c.accent)
            .reduce((n, c) => n + c.paths.length, 0);
        const accented = container.querySelectorAll('path.ink-calli-path.is-accent');
        expect(accented.length).toBe(expected);
        expect(expected).toBeGreaterThan(0);
    });

    it('书写错峰：--ink-calli-delay 按字序/contour 序递增，fill delay 统一内联', () => {
        setTheme('ink-havoc', true);
        const { container } = render(<InkCalligraphyTitle fallback={FALLBACK} />);
        const paths = Array.from(container.querySelectorAll<SVGPathElement>('path.ink-calli-path'));
        // 字首 contour 落笔时刻按字序严格递增（行气贯通的大错峰）；
        // 字内 contour 小错峰（字组内单调递增）。注：相邻字节奏刻意交叠
        // （下一字起笔早于前一字末笔，如行书连贯），故全局序列非单调。
        const groups = Array.from(container.querySelectorAll('svg.ink-calli-title g'));
        const headDelays = groups.map(g =>
            parseFloat(
                (g.querySelector<SVGPathElement>('path.ink-calli-path')!)
                    .style.getPropertyValue('--ink-calli-delay'),
            ),
        );
        expect(headDelays[0]).toBe(0);
        for (let i = 1; i < headDelays.length; i++) {
            expect(headDelays[i]).toBeGreaterThan(headDelays[i - 1]);
        }
        for (const g of groups) {
            const delays = Array.from(g.querySelectorAll<SVGPathElement>('path.ink-calli-path'))
                .map(p => parseFloat(p.style.getPropertyValue('--ink-calli-delay')));
            for (let i = 1; i < delays.length; i++) {
                expect(delays[i]).toBeGreaterThan(delays[i - 1]);
            }
        }
        // fill delay 全部一致且晚于最后一笔落笔时刻
        const fillDelays = new Set(paths.map(p => p.style.getPropertyValue('--ink-calli-fill-delay')));
        expect(fillDelays.size).toBe(1);
        const fillDelay = parseFloat([...fillDelays][0]);
        const lastStrokeDelay = Math.max(
            ...paths.map(p => parseFloat(p.style.getPropertyValue('--ink-calli-delay'))),
        );
        expect(fillDelay).toBeGreaterThan(lastStrokeDelay);
    });
});

describe('书写节奏纯函数', () => {
    it('calligraphyStrokeDelay：字间错峰为主、字内 contour 小错峰', () => {
        expect(calligraphyStrokeDelay(0, 0)).toBe(0);
        expect(calligraphyStrokeDelay(1, 0)).toBeGreaterThan(calligraphyStrokeDelay(0, 0));
        expect(calligraphyStrokeDelay(2, 1)).toBeGreaterThan(calligraphyStrokeDelay(2, 0));
    });

    it('calligraphyFillDelay：晚于全部 contour 落笔时刻、早于末笔收笔', () => {
        const fill = calligraphyFillDelay();
        const chars = HERO_CALLIGRAPHY.chars;
        const lastStart = Math.max(
            ...chars.map((c, i) => calligraphyStrokeDelay(i, c.paths.length - 1)),
        );
        const lastEnd = Math.max(
            ...chars.map((c, i) => calligraphyStrokeDelay(i, c.paths.length - 1) + 0.75),
        );
        expect(fill).toBeGreaterThan(lastStart);
        expect(fill).toBeLessThanOrEqual(lastEnd);
    });
});
