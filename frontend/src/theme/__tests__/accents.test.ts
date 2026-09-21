import { beforeEach, describe, expect, it } from 'vitest';
import {
    ACCENT_PRESETS,
    applyAccent,
    DEFAULT_ACCENT_HEX,
    normalizeAccentHex,
} from '../accents';

const TOKENS = ['--v2-accent', '--v2-accent-strong', '--v2-accent-soft', '--v2-accent-ring'] as const;

function readTokens() {
    const style = document.documentElement.style;
    return Object.fromEntries(TOKENS.map(t => [t, style.getPropertyValue(t).trim()]));
}

beforeEach(() => {
    TOKENS.forEach(t => document.documentElement.style.removeProperty(t));
});

describe('ACCENT_PRESETS 色板构成', () => {
    it('8 色按色相序排列，青瓷为默认且居首', () => {
        expect(ACCENT_PRESETS.map(p => p.label)).toEqual(['青瓷', '冰青', '蔚蓝', '靛蓝', '藤紫', '紫罗兰', '品红', '石墨']);
        expect(ACCENT_PRESETS[0].hex).toBe(DEFAULT_ACCENT_HEX);
    });

    it('每色三档齐全；dark/glass 档 strong 复用 light 值（结构上只存三项）', () => {
        for (const p of ACCENT_PRESETS) {
            expect(p.light.strong).toMatch(/^#/);
            expect(p.dark.accent).toMatch(/^#/);
            expect(p.glass.accent).toMatch(/^#/);
        }
    });

    it('与语义色冲突的旧色（红/绿/橙）及旧靛蓝已移出预设', () => {
        const hexes = ACCENT_PRESETS.map(p => p.hex);
        expect(hexes).not.toContain('#C4453F');
        expect(hexes).not.toContain('#4E9E73');
        expect(hexes).not.toContain('#C98A3A');
        expect(hexes).not.toContain('#4C86C8');
        expect(hexes).not.toContain('#5E63DE');
    });
});

describe('normalizeAccentHex 旧值映射', () => {
    it('被砍色映射到最接近的保留色', () => {
        expect(normalizeAccentHex('#4C86C8')).toBe('#2589D6'); // 灰蓝 → 蔚蓝
        expect(normalizeAccentHex('#C4453F')).toBe('#C9578A'); // 红 → 品红
        expect(normalizeAccentHex('#C98A3A')).toBe('#12967F'); // 橙 → 青瓷（默认）
        expect(normalizeAccentHex('#4E9E73')).toBe('#12967F'); // 绿 → 青瓷（默认）
        expect(normalizeAccentHex('#5E63DE')).toBe('#5B6BE0'); // 灰调靛蓝 → A 稿基准
    });

    it('初版 Tailwind 色直达新色（无链式中转）', () => {
        expect(normalizeAccentHex('#3B82F6')).toBe('#2589D6');
        expect(normalizeAccentHex('#6366F1')).toBe('#5B6BE0');
        expect(normalizeAccentHex('#EF4444')).toBe('#C9578A');
    });

    it('大小写不敏感；未知值原样返回', () => {
        expect(normalizeAccentHex('#2589d6')).toBe('#2589D6');
        expect(normalizeAccentHex('#123456')).toBe('#123456');
    });
});

describe('applyAccent 三主题分档', () => {
    it('light 档：写入 light 全套令牌', () => {
        applyAccent('#12967F', 'light');
        expect(readTokens()).toEqual({
            '--v2-accent': '#12967F',
            '--v2-accent-strong': '#0C7563',
            '--v2-accent-soft': 'rgba(18,150,127,.10)',
            '--v2-accent-ring': 'rgba(18,150,127,.32)',
        });
    });

    it('dark 档：accent 取亮变体，strong 复用 light', () => {
        applyAccent('#12967F', 'dark');
        const t = readTokens();
        expect(t['--v2-accent']).toBe('#7FD4E8');
        expect(t['--v2-accent-strong']).toBe('#0C7563');
        expect(t['--v2-accent-soft']).toBe('rgba(18,150,127,.16)');
    });

    it('glass 档：accent 取清透变体（≠ light），strong 复用 light', () => {
        applyAccent('#12967F', 'glass');
        const t = readTokens();
        expect(t['--v2-accent']).toBe('#0EA088');
        expect(t['--v2-accent-strong']).toBe('#0C7563');
        expect(t['--v2-accent-soft']).toBe('rgba(14,160,136,.12)');
        expect(t['--v2-accent-ring']).toBe('rgba(14,160,136,.35)');
    });

    it('新色蔚蓝：三档各自正确', () => {
        applyAccent('#2589D6', 'light');
        expect(readTokens()['--v2-accent']).toBe('#2589D6');
        applyAccent('#2589D6', 'dark');
        expect(readTokens()['--v2-accent']).toBe('#74B9EE');
        applyAccent('#2589D6', 'glass');
        expect(readTokens()['--v2-accent']).toBe('#1B96E4');
        expect(readTokens()['--v2-accent-strong']).toBe('#1A6BA8');
    });

    it('新色石墨：dark 档亮灰蓝', () => {
        applyAccent('#566678', 'dark');
        expect(readTokens()['--v2-accent']).toBe('#9AA8BC');
    });

    it('新色藤紫（C 稿薄藤紫 accent）：三档正确，dark 为亮紫', () => {
        applyAccent('#7B61E8', 'light');
        expect(readTokens()['--v2-accent']).toBe('#7B61E8');
        expect(readTokens()['--v2-accent-strong']).toBe('#6B4EE0');
        applyAccent('#7B61E8', 'dark');
        expect(readTokens()['--v2-accent']).toBe('#A78BFA');
        applyAccent('#7B61E8', 'glass');
        expect(readTokens()['--v2-accent']).toBe('#8A6FF2');
    });

    it('新色冰青（D 稿柔夜墨蓝 accent）：dark 档呈现原色，浅色档为可读深青', () => {
        applyAccent('#7FD4E8', 'light');
        expect(readTokens()['--v2-accent']).toBe('#2492AC');
        expect(readTokens()['--v2-accent-strong']).toBe('#1A6E84');
        applyAccent('#7FD4E8', 'dark');
        expect(readTokens()['--v2-accent']).toBe('#7FD4E8');
        expect(readTokens()['--v2-accent-soft']).toBe('rgba(127,212,232,.16)');
        applyAccent('#7FD4E8', 'glass');
        expect(readTokens()['--v2-accent']).toBe('#259AB5');
    });

    it('靛蓝采纳 A 稿基准 #5B6BE0，旧值 #5E63DE 经映射落入', () => {
        applyAccent('#5B6BE0', 'light');
        expect(readTokens()['--v2-accent']).toBe('#5B6BE0');
        applyAccent('#5E63DE', 'light');
        expect(readTokens()['--v2-accent']).toBe('#5B6BE0');
    });

    it('持久化的被砍色经映射落到新色（蓝→蔚蓝）', () => {
        applyAccent('#4C86C8', 'light');
        expect(readTokens()['--v2-accent']).toBe('#2589D6');
    });

    it('未知 hex 回退默认青瓷', () => {
        applyAccent('#123456', 'light');
        expect(readTokens()['--v2-accent']).toBe('#12967F');
    });

    it('dark 档 accent-ink 用 accent 本体；light/glass 用 strong 派生', () => {
        applyAccent('#12967F', 'dark');
        expect(document.documentElement.style.getPropertyValue('--v2-accent-ink').trim()).toBe('#7FD4E8');
        applyAccent('#12967F', 'glass');
        expect(document.documentElement.style.getPropertyValue('--v2-accent-ink').trim()).toContain('#0C7563');
    });
});
