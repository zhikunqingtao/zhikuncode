/**
 * configStore · jellyFx 归一化测试（仿 configStore.inkHavocFx.test.ts 先例）：
 * - defaultJellyFx：系统 reduced-motion 偏好 → motion 默认 'reduced'
 * - normalizeJellyFx：非法值逐项回退 base/默认，三档 motion 恒为合法值
 * - normalizeTheme：mode 白名单 + jellyFx 补默认；旧持久化数据迁移补默认
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
    defaultJellyFx,
    normalizeJellyFx,
    normalizeTheme,
    useConfigStore,
} from '../configStore';
import type { JellyFxConfig } from '@/types';

const BASE: JellyFxConfig = { cinematic: true, motion: 'full' };

describe('defaultJellyFx', () => {
    afterEach(() => { vi.unstubAllGlobals(); });

    it('无 reduced-motion 偏好 → 默认 { cinematic: true, motion: full }', () => {
        expect(defaultJellyFx()).toEqual({ cinematic: true, motion: 'full' });
    });

    it("matchMedia('(prefers-reduced-motion: reduce)') 命中 → motion 默认 'reduced'", () => {
        vi.stubGlobal('matchMedia', vi.fn((query: string) => ({
            matches: query === '(prefers-reduced-motion: reduce)',
        })));
        expect(defaultJellyFx()).toEqual({ cinematic: true, motion: 'reduced' });
    });
});

describe('normalizeJellyFx', () => {
    it.each(['fast', 123, null, undefined])('motion 非法值 %j → 回退 base.motion', (motion) => {
        expect(normalizeJellyFx({ cinematic: false, motion }, BASE))
            .toEqual({ cinematic: false, motion: BASE.motion });
    });

    it.each(['yes', 1, null, undefined])('cinematic 非 boolean（%j）→ 回退 base.cinematic', (bad) => {
        expect(normalizeJellyFx({ cinematic: bad, motion: 'off' }, BASE))
            .toEqual({ cinematic: BASE.cinematic, motion: 'off' });
        expect(normalizeJellyFx({ cinematic: bad }, { cinematic: false, motion: 'reduced' }))
            .toEqual({ cinematic: false, motion: 'reduced' });
    });

    it.each([null, undefined, 42, 'jelly', [true]])('整体非法值 %j → 全字段回退 base', (bad) => {
        expect(normalizeJellyFx(bad, BASE)).toEqual(BASE);
    });

    it('全合法值 → 原样保真（含三档 motion）', () => {
        for (const motion of ['full', 'reduced', 'off'] as const) {
            const fx: JellyFxConfig = { cinematic: false, motion };
            expect(normalizeJellyFx(fx, BASE)).toEqual(fx);
        }
    });
});

describe('normalizeTheme · jelly 分支', () => {
    it("字符串分支：normalizeTheme('jelly') → mode='jelly' 且 jellyFx 补默认两字段", () => {
        const theme = normalizeTheme('jelly');
        expect(theme.mode).toBe('jelly');
        expect(theme.jellyFx).toEqual({ cinematic: true, motion: 'full' });
    });

    it('对象分支：部分 jellyFx 字段逐项归一，mode 白名单兜底', () => {
        const theme = normalizeTheme({ mode: 'jelly', jellyFx: { cinematic: 'bad', motion: 'off' } });
        expect(theme.mode).toBe('jelly');
        expect(theme.jellyFx).toEqual({ cinematic: true, motion: 'off' });
    });

    it('对象分支：非法 mode 回退 light，jellyFx 仍补默认', () => {
        const theme = normalizeTheme({ mode: 'candy', jellyFx: { cinematic: false, motion: 'reduced' } });
        expect(theme.mode).toBe('light');
        expect(theme.jellyFx).toEqual({ cinematic: false, motion: 'reduced' });
    });
});

describe('configStore jellyFx 持久化迁移', () => {
    beforeEach(() => {
        localStorage.clear();
    });

    afterEach(() => {
        localStorage.clear();
        useConfigStore.getState().resetTheme();
    });

    it('旧持久化数据（无 jellyFx 字段）→ merge/migrate 路径补默认值', async () => {
        localStorage.setItem('ai-coder-config', JSON.stringify({
            version: 2,
            state: { theme: { mode: 'jelly', accentColor: '#8E1F3C' } },
        }));
        await useConfigStore.persist.rehydrate();
        const { theme } = useConfigStore.getState();
        expect(theme.mode).toBe('jelly');
        expect(theme.accentColor).toBe('#8E1F3C');
        expect(theme.jellyFx).toEqual({ cinematic: true, motion: 'full' });
    });
});
