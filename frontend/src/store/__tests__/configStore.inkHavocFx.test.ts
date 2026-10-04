import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
    defaultInkHavocFx,
    normalizeInkHavocFx,
    normalizeTheme,
    useConfigStore,
} from '../configStore';
import type { InkHavocFxConfig } from '@/types';

const BASE: InkHavocFxConfig = { cinematic: true, motion: 'full', retreat: false };

describe('defaultInkHavocFx', () => {
    afterEach(() => { vi.unstubAllGlobals(); });

    it('无 reduced-motion 偏好 → 默认 { cinematic: true, motion: full, retreat: false }', () => {
        expect(defaultInkHavocFx()).toEqual({ cinematic: true, motion: 'full', retreat: false });
    });

    it("matchMedia('(prefers-reduced-motion: reduce)') 命中 → motion 默认 'reduced'", () => {
        vi.stubGlobal('matchMedia', vi.fn((query: string) => ({
            matches: query === '(prefers-reduced-motion: reduce)',
        })));
        expect(defaultInkHavocFx()).toEqual({ cinematic: true, motion: 'reduced', retreat: false });
    });
});

describe('normalizeInkHavocFx', () => {
    it.each(['fast', 123, undefined])('motion 非法值 %j → 回退 base.motion', (motion) => {
        expect(normalizeInkHavocFx({ cinematic: false, motion }, BASE))
            .toEqual({ cinematic: false, motion: BASE.motion, retreat: false });
    });

    it.each(['yes', 1, null])('cinematic 非 boolean（%j）→ 回退 base', (bad) => {
        expect(normalizeInkHavocFx({ cinematic: bad, motion: 'off' }, BASE))
            .toEqual({ cinematic: BASE.cinematic, motion: 'off', retreat: false });
    });

    it.each(['yes', 1, null, undefined])('retreat 非 boolean（%j）→ 回退 base.retreat', (bad) => {
        expect(normalizeInkHavocFx({ cinematic: true, motion: 'full', retreat: bad }, BASE))
            .toEqual(BASE);
        expect(normalizeInkHavocFx({ retreat: bad }, { ...BASE, retreat: true }))
            .toEqual({ ...BASE, retreat: true });
    });

    it('retreat 合法 boolean → 原样保真', () => {
        expect(normalizeInkHavocFx({ cinematic: true, motion: 'reduced', retreat: true }, BASE))
            .toEqual({ cinematic: true, motion: 'reduced', retreat: true });
    });

    it.each([null, undefined, 42, 'ink', [true]])('整体非法值 %j → 全字段回退 base', (bad) => {
        expect(normalizeInkHavocFx(bad, BASE)).toEqual(BASE);
    });

    it('全合法值 → 原样保真', () => {
        const fx: InkHavocFxConfig = { cinematic: false, motion: 'reduced', retreat: true };
        expect(normalizeInkHavocFx(fx, BASE)).toEqual(fx);
    });
});

describe('normalizeTheme', () => {
    it("字符串分支：normalizeTheme('ink-havoc') → mode='ink-havoc' 且 inkHavocFx 补默认三字段", () => {
        const theme = normalizeTheme('ink-havoc');
        expect(theme.mode).toBe('ink-havoc');
        expect(theme.inkHavocFx).toEqual({ cinematic: true, motion: 'full', retreat: false });
    });

    it("字符串分支：normalizeTheme('ink-havoc-night') → mode='ink-havoc-night'", () => {
        const theme = normalizeTheme('ink-havoc-night');
        expect(theme.mode).toBe('ink-havoc-night');
        expect(theme.inkHavocFx).toEqual({ cinematic: true, motion: 'full', retreat: false });
    });

    it('对象分支：部分 inkHavocFx 字段逐项归一，mode 白名单兜底', () => {
        const theme = normalizeTheme({ mode: 'ink-havoc', inkHavocFx: { cinematic: 'bad', motion: 'off', retreat: true } });
        expect(theme.mode).toBe('ink-havoc');
        expect(theme.inkHavocFx).toEqual({ cinematic: true, motion: 'off', retreat: true });
    });

    it('对象分支：非法 mode 回退 light，inkHavocFx 仍补默认', () => {
        const theme = normalizeTheme({ mode: 'opera', inkHavocFx: { cinematic: false, motion: 'reduced', retreat: true } });
        expect(theme.mode).toBe('light');
        expect(theme.inkHavocFx).toEqual({ cinematic: false, motion: 'reduced', retreat: true });
    });
});

describe('configStore inkHavocFx 持久化迁移', () => {
    beforeEach(() => {
        localStorage.clear();
    });

    afterEach(() => {
        localStorage.clear();
        useConfigStore.getState().resetTheme();
    });

    it('旧持久化数据（无 inkHavocFx 字段）→ merge/migrate 路径补默认值', async () => {
        localStorage.setItem('ai-coder-config', JSON.stringify({
            version: 2,
            state: { theme: { mode: 'ink-havoc-night', accentColor: '#123456' } },
        }));
        await useConfigStore.persist.rehydrate();
        const { theme } = useConfigStore.getState();
        expect(theme.mode).toBe('ink-havoc-night');
        expect(theme.accentColor).toBe('#123456');
        expect(theme.inkHavocFx).toEqual({ cinematic: true, motion: 'full', retreat: false });
    });
});
