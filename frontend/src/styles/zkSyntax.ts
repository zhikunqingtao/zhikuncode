/**
 * zkSyntax — react-syntax-highlighter(Prism) 的 zk 语法样式（指南 §4.2）。
 *
 * 浅 / 深两套样式对象显式列出，色值与 design-tokens.ts MONACO_ZK_THEMES 同源：
 * 键（JSON key / 类型 / 类名）、字符串、数字、关键字、函数五色 + 文字三档（t1/t2/t3）。
 * 浅色四色为定稿色经 WCAG 4.5:1 同色相加深后的终值；深色使用定稿原值。
 */
import type { CSSProperties } from 'react';
import { TOKENS, resolveTheme } from './design-tokens';
import type { ThemeMode } from './design-tokens';

export type ZkSyntaxStyle = Record<string, CSSProperties>;

interface SyntaxPalette {
    key: string;
    string: string;
    number: string;
    keyword: string;
    fn: string;
    comment: string;
    text1: string;
    text2: string;
    text4: string;
}

const LIGHT_PALETTE: SyntaxPalette = {
    key: '#4F5878',
    string: '#2C724B',
    number: '#8F5C12',
    keyword: '#6E45A6',
    fn: '#5054C8',
    comment: TOKENS.light['--v2-text-3'],
    text1: TOKENS.light['--v2-text-1'],
    text2: TOKENS.light['--v2-text-2'],
    text4: TOKENS.light['--v2-text-4'],
};

const DARK_PALETTE: SyntaxPalette = {
    key: '#9DA5C4',
    string: '#6FA88A',
    number: '#D2A24C',
    keyword: '#B58CD6',
    fn: '#8A8FF0',
    comment: TOKENS.dark['--v2-text-3'],
    text1: TOKENS.dark['--v2-text-1'],
    text2: TOKENS.dark['--v2-text-2'],
    text4: TOKENS.dark['--v2-text-4'],
};

/** 花果晨语法色板：保留重彩色相，按代码块绢本沉底校准至至少 4.5:1。 */
const INK_HUAGUO_PALETTE: SyntaxPalette = {
    key: '#805832',      // 赭石（类型/类名/属性）
    string: '#366B4A',   // 石绿压深
    number: '#9D2933',   // 胭脂
    keyword: '#A04337',  // 土红（九色鹿北魏土红）
    fn: '#3A5F8A',       // 群青（天庭冷色）
    comment: '#695F4E',  // 松烟
    text1: '#2E2822',    // 墨色
    text2: '#6B5F4E',    // 墨灰褐（标点/operator）
    text4: '#6D5F41',    // 赭色（行号）
};

/** 灵霄夜（ink-havoc-night 深）语法色板：提亮版，取皮肤令牌（朱膘/翠绿/鎏金/石青/桃红/灰绢） */
const INK_LINGXIAO_PALETTE: SyntaxPalette = {
    key: '#5FA8D8',      // 石青亮
    string: '#46B08C',   // 翠绿
    number: '#F47983',   // 桃红
    keyword: '#E85D4A',  // 朱膘（朱砂提亮）
    fn: '#E0A92E',       // 鎏金（金箍棒）
    comment: '#98A2B8',  // 灰绢
    text1: '#EDE5D0',    // 月白
    text2: '#A0A8BC',    // 灰蓝（标点/operator）
    text4: '#7684A3',    // 蓝灰（行号）
};

function buildZkSyntaxStyle(p: SyntaxPalette): ZkSyntaxStyle {
    const base: CSSProperties = { color: p.text1, background: 'transparent', textShadow: 'none' };
    const comment: CSSProperties = { color: p.comment, fontStyle: 'italic' };
    const key: CSSProperties = { color: p.key };
    const str: CSSProperties = { color: p.string };
    const num: CSSProperties = { color: p.number };
    const kw: CSSProperties = { color: p.keyword };
    const fn: CSSProperties = { color: p.fn };
    const variable: CSSProperties = { color: p.text1 };
    const aux: CSSProperties = { color: p.text2 };
    return {
        'code[class*="language-"]': base,
        'pre[class*="language-"]': base,
        comment, prolog: comment, cdata: comment,
        string: str, char: str, 'attr-value': str, regex: str,
        keyword: kw, tag: kw, important: kw, rule: kw, doctype: kw,
        number: num, boolean: num, unit: num, constant: num, symbol: num,
        'class-name': key, 'maybe-class-name': key, builtin: key, namespace: key, 'url-reference': key,
        property: key, 'attr-name': key,
        function: fn, 'function-variable': fn, selector: fn,
        variable, parameter: variable,
        punctuation: aux, operator: aux, delimiter: aux,
        linenumber: { color: p.text4 },
    };
}

/** 浅色样式对象（显式终值） */
export const ZK_SYNTAX_LIGHT: ZkSyntaxStyle = buildZkSyntaxStyle(LIGHT_PALETTE);

/** 深色样式对象（定稿原值） */
export const ZK_SYNTAX_DARK: ZkSyntaxStyle = buildZkSyntaxStyle(DARK_PALETTE);

/** 预构建双主题样式（静态常量，模块加载时一次性派生） */
export const ZK_SYNTAX_STYLES: Record<'light' | 'dark', ZkSyntaxStyle> = {
    light: ZK_SYNTAX_LIGHT,
    dark: ZK_SYNTAX_DARK,
};

/** 花果晨语法样式（ink-havoc 浅主题代码块） */
export const ZK_SYNTAX_INK_HUAGUO: ZkSyntaxStyle = buildZkSyntaxStyle(INK_HUAGUO_PALETTE);

/** 灵霄夜语法样式（ink-havoc-night 深主题代码块） */
export const ZK_SYNTAX_INK_LINGXIAO: ZkSyntaxStyle = buildZkSyntaxStyle(INK_LINGXIAO_PALETTE);

/**
 * resolveZkSyntaxStyle — 按主题模式取语法样式（ink 双主题直达专属色板，
 * 其余主题经 resolveTheme 归一 light/dark；新增主题只需扩展本函数）
 */
export function resolveZkSyntaxStyle(mode: string): ZkSyntaxStyle {
    if (mode === 'ink-havoc') return ZK_SYNTAX_INK_HUAGUO;
    if (mode === 'ink-havoc-night') return ZK_SYNTAX_INK_LINGXIAO;
    return ZK_SYNTAX_STYLES[resolveTheme(mode as ThemeMode)];
}
