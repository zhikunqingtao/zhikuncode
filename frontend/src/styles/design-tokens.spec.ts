/**
 * design-tokens.spec.ts — design-tokens.ts 与 globals.css 的防漂移断言
 * SPEC: 改造指南 §4.5 / §12-4
 *
 * 读取 globals.css 文本，正则抽取 `:root` 与 `.dark` 两个 v2 块内的全部
 * `--v2-*` 声明，规范化后断言与 TOKENS.light / TOKENS.dark 完全相等；
 * 单改一侧即失败。
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
    ACCENT_DERIVED,
    CHART_COLORS,
    MONACO_ZK_THEMES,
    TOKENS,
    XTERM_ANSI,
    getChartColors,
    getMonacoZkThemes,
    getXtermPalette,
} from './design-tokens';

const css = readFileSync(join(process.cwd(), 'src', 'styles', 'globals.css'), 'utf8');

/** 值规范化：trim、压缩连续空白、hex 小写化 */
function normalize(value: string): string {
    return value
        .trim()
        .replace(/\s+/g, ' ')
        .replace(/#[0-9a-fA-F]{3,8}\b/g, (hex) => hex.toLowerCase());
}

function normalizeAll<T extends Record<string, string>>(obj: T): Record<string, string> {
    return Object.fromEntries(Object.entries(obj).map(([k, v]) => [k, normalize(v)]));
}

/** 抽取指定选择器块内的全部 --v2-* 声明（要求恰好一个块包含 v2 令牌） */
function extractV2Decls(selector: ':root' | '.dark'): Record<string, string> {
    const blockPattern = selector === ':root' ? /:root\s*\{([^}]*)\}/g : /\.dark\s*\{([^}]*)\}/g;
    const decls: Record<string, string> = {};
    let v2BlockCount = 0;
    for (const match of css.matchAll(blockPattern)) {
        const body = match[1];
        if (!body.includes('--v2-')) continue;
        v2BlockCount += 1;
        for (const decl of body.matchAll(/(--v2-[\w-]+)\s*:\s*([^;]+);/g)) {
            decls[decl[1]] = normalize(decl[2]);
        }
    }
    expect(v2BlockCount, `${selector} 应恰好有一个包含 --v2-* 的块`).toBe(1);
    return decls;
}

const rootDecls = extractV2Decls(':root');
const darkDecls = extractV2Decls('.dark');

describe('design-tokens 防漂移（globals.css ↔ design-tokens.ts）', () => {
    it('TOKENS.light 与 :root v2 声明完全相等（accent-hover/active 除外）', () => {
        // 两个 color-mix 派生值单独断言（见下），不参与 TOKENS 比对
        const { '--v2-accent-hover': _hover, '--v2-accent-active': _active, ...rest } = rootDecls;
        expect(rest).toEqual(normalizeAll(TOKENS.light));
    });

    it('TOKENS.dark 与 .dark v2 声明完全相等', () => {
        expect(darkDecls).toEqual(normalizeAll(TOKENS.dark));
    });

    it('CHART_COLORS 与 --v2-chart-1..8 一致', () => {
        const indices = [1, 2, 3, 4, 5, 6, 7, 8] as const;
        expect(indices.map((i) => rootDecls[`--v2-chart-${i}`]))
            .toEqual(CHART_COLORS.light.map(normalize));
        expect(indices.map((i) => darkDecls[`--v2-chart-${i}`]))
            .toEqual(CHART_COLORS.dark.map(normalize));
    });

    it('ACCENT_DERIVED 的两个 color-mix 表达式在 CSS 中存在且等值', () => {
        expect(rootDecls['--v2-accent-hover']).toBe(normalize(ACCENT_DERIVED.hover));
        expect(rootDecls['--v2-accent-active']).toBe(normalize(ACCENT_DERIVED.active));
        expect(css).toContain('--v2-accent-hover:color-mix(in srgb, var(--v2-accent-strong) 92%, black)');
        expect(css).toContain('--v2-accent-active:color-mix(in srgb, var(--v2-accent-strong) 84%, black)');
    });
});

/* ================= 动态派生 getter（跟随 accent；静态 CHART_COLORS 不变） ================= */

describe('getChartColors 强调色跟随', () => {
    it('light 档：品红 accent 时 [0] 为品红 light 值', () => {
        expect(getChartColors('light', '#C9578A')[0]).toBe('#C9578A');
    });

    it('dark 档：品红 accent 时 [0] 为品红 dark 档提亮值', () => {
        expect(getChartColors('dark', '#C9578A')[0]).toBe('#E58AB5');
    });

    it('未知 accent 回退青瓷（light/dark 各档）', () => {
        expect(getChartColors('light', '#123456')[0]).toBe('#12967F');
        expect(getChartColors('dark', '#123456')[0]).toBe('#7FD4E8');
    });

    it('缺省 accent 回退青瓷；accent hex 大小写不敏感', () => {
        expect(getChartColors('light')[0]).toBe('#12967F');
        expect(getChartColors('dark')[0]).toBe('#7FD4E8');
        expect(getChartColors('light', '#c9578a')[0]).toBe('#C9578A');
    });

    it('其余色位与静态 CHART_COLORS 一致，且不改动静态数组', () => {
        const dynamic = getChartColors('light', '#C9578A');
        expect(dynamic.slice(1)).toEqual([...CHART_COLORS.light].slice(1));
        expect(getChartColors('dark', '#C9578A').slice(1)).toEqual([...CHART_COLORS.dark].slice(1));
        expect(CHART_COLORS.light[0]).toBe('#5E63DE');
        expect(CHART_COLORS.dark[0]).toBe('#8A8FF0');
    });
});

describe('Monaco / xterm 主题 accent 派生', () => {
    it('getMonacoZkThemes 缺省 accent 与静态 MONACO_ZK_THEMES 完全等值', () => {
        expect(getMonacoZkThemes('light')).toEqual(MONACO_ZK_THEMES['zk-light']);
        expect(getMonacoZkThemes('dark')).toEqual(MONACO_ZK_THEMES['zk-dark']);
    });

    it('getMonacoZkThemes 品红 accent 派生 4 处 accent 色（light 档）', () => {
        const theme = getMonacoZkThemes('light', '#C9578A');
        expect(theme.colors['editor.selectionBackground']).toBe('#C9578A40');
        expect(theme.colors['editorCursor.foreground']).toBe('#C9578A');
        expect(theme.colors['editorSuggestWidget.selectedBackground']).toBe('#C9578A1F');
        expect(theme.colors['editorBracketMatch.border']).toBe('#C9578A80');
    });

    it('getMonacoZkThemes dark 档取 accent dark 值；非 accent 色不动', () => {
        const theme = getMonacoZkThemes('dark', '#C9578A');
        expect(theme.colors['editorCursor.foreground']).toBe('#E58AB5');
        expect(theme.colors['editor.selectionBackground']).toBe('#E58AB540');
        expect(theme.colors['editor.background']).toBe(MONACO_ZK_THEMES['zk-dark'].colors['editor.background']);
        expect(theme.rules).toEqual(MONACO_ZK_THEMES['zk-dark'].rules);
    });

    it('getXtermPalette 缺省 accent 与静态 XTERM_ANSI 完全等值', () => {
        expect(getXtermPalette('light')).toEqual(XTERM_ANSI.light);
        expect(getXtermPalette('dark')).toEqual(XTERM_ANSI.dark);
    });

    it('getXtermPalette dark 档品红 accent：cursor/selection 派生，ANSI 16 色不动', () => {
        const palette = getXtermPalette('dark', '#C9578A');
        expect(palette.cursor).toBe('#E58AB5');
        expect(palette.selectionBackground).toBe('#E58AB540');
        expect(palette.blue).toBe(XTERM_ANSI.dark.blue);
        expect(palette.brightRed).toBe(XTERM_ANSI.dark.brightRed);
    });
});

/* ================= WCAG AA 对比度守护（§10.1） ================= */

/** hex → [r,g,b]（0-255） */
function hexToRgb(hex: string): [number, number, number] {
    const h = hex.replace('#', '');
    return [
        parseInt(h.slice(0, 2), 16),
        parseInt(h.slice(2, 4), 16),
        parseInt(h.slice(4, 6), 16),
    ];
}

/** WCAG 2.x 相对亮度：https://www.w3.org/TR/WCAG21/#dfn-relative-luminance */
function relativeLuminance(hex: string): number {
    const [r, g, b] = hexToRgb(hex).map((c8) => {
        const cs = c8 / 255;
        return cs <= 0.04045 ? cs / 12.92 : Math.pow((cs + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** 对比度 = (L亮 + 0.05) / (L暗 + 0.05) */
function contrastRatio(fgHex: string, bgHex: string): number {
    const l1 = relativeLuminance(fgHex);
    const l2 = relativeLuminance(bgHex);
    const [hi, lo] = l1 >= l2 ? [l1, l2] : [l2, l1];
    return (hi + 0.05) / (lo + 0.05);
}

describe('WCAG AA 文本对比度（正文/常规文本 ≥ 4.5:1）', () => {
    // 每行：前景令牌、其典型承载面背景令牌（取该文字档实际落座的最低对比面）
    const pairs: [theme: 'light' | 'dark', fg: string, bg: string][] = [
        // light：header 连接态 text-t3 落 surface-2（axe 曾报 4.1:1 失败，本用例防回归）
        ['light', '--v2-text-1', '--v2-bg-surface'],
        ['light', '--v2-text-1', '--v2-bg-surface-2'],
        ['light', '--v2-text-2', '--v2-bg-surface'],
        ['light', '--v2-text-2', '--v2-bg-surface-2'],
        ['light', '--v2-text-3', '--v2-bg-surface'],
        ['light', '--v2-text-3', '--v2-bg-surface-2'],
        // dark：t3 落 surface-2 为该档最低对比组合
        ['dark', '--v2-text-1', '--v2-bg-surface-2'],
        ['dark', '--v2-text-2', '--v2-bg-surface-2'],
        ['dark', '--v2-text-3', '--v2-bg-surface-2'],
        ['dark', '--v2-text-3', '--v2-bg-surface'],
        ['dark', '--v2-ok-strong', '--v2-bg-surface-2'],
        ['dark', '--v2-warn-strong', '--v2-bg-surface-2'],
        // 本轮新增：t3 落 sunken（输入井 placeholder）、语义色落 code-bg（工具卡 JSON 降对比后的注释档）
        ['light', '--v2-text-3', '--v2-bg-sunken'],
        ['light', '--v2-text-3', '--v2-code-bg'],
        ['dark', '--v2-text-3', '--v2-bg-sunken'],
        ['dark', '--v2-text-3', '--v2-code-bg'],
    ];
    it.each(pairs)('%s %s on %s ≥ 4.5:1', (theme, fg, bg) => {
        const ratio = contrastRatio(TOKENS[theme][fg as never], TOKENS[theme][bg as never]);
        expect(ratio).toBeGreaterThanOrEqual(4.5);
    });
});

// Guard readable text when shared colours are changed, not just CSS/JS equality.
describe('shared text contrast', () => {
    const luminance = (hex: string) => {
        const channels = [1, 3, 5].map(offset => {
            const value = parseInt(hex.slice(offset, offset + 2), 16) / 255;
            return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
        });
        return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
    };
    for (const theme of ['light', 'dark'] as const) {
        it(`${theme}: text and status labels meet 4.5:1 on content surfaces`, () => {
            const palette = TOKENS[theme];
            for (const foreground of ['--v2-text-1', '--v2-text-2', '--v2-text-3', '--v2-text-4', '--v2-ok', '--v2-warn', '--v2-err'] as const) {
                for (const background of ['--v2-bg-app', '--v2-bg-surface', '--v2-bg-surface-2', '--v2-bg-sunken'] as const) {
                    const values = [luminance(palette[foreground]), luminance(palette[background])].sort((a, b) => b - a);
                    expect((values[0] + 0.05) / (values[1] + 0.05), `${foreground} on ${background}`).toBeGreaterThanOrEqual(4.5);
                }
            }
        });
    }
});
