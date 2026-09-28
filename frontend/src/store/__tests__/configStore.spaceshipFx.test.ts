import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
    defaultSpaceshipFx,
    normalizeSpaceshipFx,
    normalizeTheme,
    useConfigStore,
} from '../configStore';
import type { SpaceshipFxConfig } from '@/types';

const BASE: SpaceshipFxConfig = { cinematic: true, eventFx: true, motion: 'full' };

describe('normalizeSpaceshipFx', () => {
    it.each(['fast', 123, undefined])('motion 非法值 %j → 回退 base.motion', (motion) => {
        expect(normalizeSpaceshipFx({ cinematic: false, eventFx: false, motion }, BASE))
            .toEqual({ cinematic: false, eventFx: false, motion: BASE.motion });
    });

    it.each(['yes', 1, null])('cinematic/eventFx 非 boolean（%j）→ 回退 base', (bad) => {
        expect(normalizeSpaceshipFx({ cinematic: bad, eventFx: bad, motion: 'off' }, BASE))
            .toEqual({ cinematic: BASE.cinematic, eventFx: BASE.eventFx, motion: 'off' });
    });

    it('全合法值 → 原样保真', () => {
        const fx: SpaceshipFxConfig = { cinematic: false, eventFx: true, motion: 'reduced' };
        expect(normalizeSpaceshipFx(fx, BASE)).toEqual(fx);
    });
});

describe('normalizeTheme', () => {
    it("字符串分支：normalizeTheme('spaceship') → mode='spaceship' 且 spaceshipFx 补默认三字段", () => {
        const theme = normalizeTheme('spaceship');
        expect(theme.mode).toBe('spaceship');
        expect(theme.spaceshipFx).toEqual({ cinematic: true, eventFx: true, motion: 'full' });
    });
});

describe('configStore spaceshipFx 持久化迁移', () => {
    beforeEach(() => {
        localStorage.clear();
    });

    afterEach(() => {
        localStorage.clear();
        useConfigStore.getState().resetTheme();
    });

    it('旧持久化数据（无 spaceshipFx 字段）→ merge/migrate 路径补默认值', async () => {
        localStorage.setItem('ai-coder-config', JSON.stringify({
            version: 2,
            state: { theme: { mode: 'spaceship', accentColor: '#123456' } },
        }));
        await useConfigStore.persist.rehydrate();
        const { theme } = useConfigStore.getState();
        expect(theme.mode).toBe('spaceship');
        expect(theme.accentColor).toBe('#123456');
        expect(theme.spaceshipFx).toEqual({ cinematic: true, eventFx: true, motion: 'full' });
    });
});

describe('defaultSpaceshipFx', () => {
    afterEach(() => { vi.unstubAllGlobals(); });

    it("matchMedia('(prefers-reduced-motion: reduce)') 命中 → motion 默认 'reduced'", () => {
        vi.stubGlobal('matchMedia', vi.fn((query: string) => ({
            matches: query === '(prefers-reduced-motion: reduce)',
        })));
        expect(defaultSpaceshipFx()).toEqual({ cinematic: true, eventFx: true, motion: 'reduced' });
    });
});
