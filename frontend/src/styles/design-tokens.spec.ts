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
import { ACCENT_DERIVED, CHART_COLORS, TOKENS } from './design-tokens';

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
