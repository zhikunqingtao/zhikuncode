/**
 * zkSyntax 主题语法表测试（ink 双主题天宫色板映射 + 归一回退）
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parse } from 'postcss';
import { describe, expect, it } from 'vitest';
import {
    resolveZkSyntaxStyle,
    ZK_SYNTAX_INK_HUAGUO,
    ZK_SYNTAX_INK_LINGXIAO,
    ZK_SYNTAX_STYLES,
} from '../zkSyntax';

describe('resolveZkSyntaxStyle', () => {
    it('ink-havoc（花果晨）直达天宫浅色板：关键字土红 / 字符串石绿 / 数字胭脂 / 类型赭石 / 函数群青 / 注释松烟', () => {
        const style = resolveZkSyntaxStyle('ink-havoc');
        expect(style).toBe(ZK_SYNTAX_INK_HUAGUO);
        expect((style.keyword as { color: string }).color).toBe('#A04337');
        expect((style.string as { color: string }).color).toBe('#366B4A');
        expect((style.number as { color: string }).color).toBe('#9D2933');
        expect((style['class-name'] as { color: string }).color).toBe('#805832');
        expect((style.function as { color: string }).color).toBe('#3A5F8A');
        expect((style.comment as { color: string }).color).toBe('#695F4E');
    });

    it('ink-havoc-night（灵霄夜）直达天宫深色板：关键字朱膘 / 字符串翠绿 / 数字桃红 / 类型石青 / 函数鎏金 / 注释灰绢', () => {
        const style = resolveZkSyntaxStyle('ink-havoc-night');
        expect(style).toBe(ZK_SYNTAX_INK_LINGXIAO);
        expect((style.keyword as { color: string }).color).toBe('#E85D4A');
        expect((style.string as { color: string }).color).toBe('#46B08C');
        expect((style.number as { color: string }).color).toBe('#F47983');
        expect((style['class-name'] as { color: string }).color).toBe('#5FA8D8');
        expect((style.function as { color: string }).color).toBe('#E0A92E');
        expect((style.comment as { color: string }).color).toBe('#98A2B8');
    });

    it('非 ink 主题归一 light/dark 原表（回归保护）', () => {
        expect(resolveZkSyntaxStyle('light')).toBe(ZK_SYNTAX_STYLES.light);
        expect(resolveZkSyntaxStyle('dark')).toBe(ZK_SYNTAX_STYLES.dark);
        // spaceship 经 resolveTheme 归一为 dark
        expect(resolveZkSyntaxStyle('spaceship')).toBe(ZK_SYNTAX_STYLES.dark);
        // glass 归一为 light
        expect(resolveZkSyntaxStyle('glass')).toBe(ZK_SYNTAX_STYLES.light);
    });
});

function relativeLuminance(hex: string): number {
    const rgb = hex.slice(1).match(/../g)!.map(value => {
        const channel = Number.parseInt(value, 16) / 255;
        return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
}

describe('ink 语法九色在实际代码块背景上的对比度', () => {
    const css = parse(readFileSync(join(process.cwd(), 'src/styles/ink-havoc.css'), 'utf8'));
    const tokens = ['class-name', 'string', 'number', 'keyword', 'function', 'comment',
        'pre[class*="language-"]', 'punctuation', 'linenumber'];

    it.each(['ink-havoc', 'ink-havoc-night'])('%s：正文、注释、行号等均至少 4.5:1', mode => {
        let background = '';
        css.walkRules(`html.${mode}`, rule => {
            rule.walkDecls('--v2-bg-sunken', declaration => { background = declaration.value; });
        });
        expect(background).toMatch(/^#[0-9a-f]{6}$/i);
        const backgroundLuminance = relativeLuminance(background);
        const style = resolveZkSyntaxStyle(mode);
        for (const token of tokens) {
            const foreground = style[token].color as string;
            expect(foreground).toMatch(/^#[0-9a-f]{6}$/i);
            const foregroundLuminance = relativeLuminance(foreground);
            const contrast = (Math.max(foregroundLuminance, backgroundLuminance) + 0.05)
                / (Math.min(foregroundLuminance, backgroundLuminance) + 0.05);
            expect(contrast, `${mode} ${token} (${foreground} / ${background})`).toBeGreaterThanOrEqual(4.5);
        }
    });
});
