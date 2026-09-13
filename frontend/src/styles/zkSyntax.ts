/**
 * zkSyntax — react-syntax-highlighter(Prism) 的 zk 语法样式（指南 §4.2）。
 *
 * 与 zkMonaco 同源：直接从 MONACO_ZK_THEMES 的 rules 派生 Prism 样式对象，
 * 保证代码块语法色与 Monaco §4.2 语法表逐色一致、零新增色值。
 * 色表本身定义于 design-tokens.ts，此处只做 token 名映射，不改配色。
 */
import type { CSSProperties } from 'react';
import { MONACO_ZK_THEMES, TOKENS } from './design-tokens';

export type ZkSyntaxStyle = Record<string, CSSProperties>;

/** Monaco rule token → Prism token 键名（覆盖 react-syntax-highlighter 常用键） */
const PRISM_TOKEN_GROUPS: { rule: string; keys: string[] }[] = [
    { rule: 'comment', keys: ['comment', 'prolog', 'cdata'] },
    { rule: 'string', keys: ['string', 'char', 'attr-value', 'regex'] },
    { rule: 'keyword', keys: ['keyword', 'tag', 'important', 'rule', 'doctype'] },
    { rule: 'number', keys: ['number', 'boolean', 'unit'] },
    { rule: 'class', keys: ['class-name', 'maybe-class-name', 'builtin', 'namespace', 'url-reference'] },
    { rule: 'function', keys: ['function', 'function-variable', 'selector'] },
    { rule: 'variable', keys: ['variable', 'attr-name', 'property', 'parameter'] },
    { rule: 'constant', keys: ['constant', 'symbol'] },
];

function buildZkSyntaxStyle(theme: 'zk-light' | 'zk-dark'): ZkSyntaxStyle {
    const def = MONACO_ZK_THEMES[theme];
    const tokens = theme === 'zk-light' ? TOKENS.light : TOKENS.dark;
    const ruleStyle = (name: string): CSSProperties => {
        const rule = def.rules.find(r => r.token === name);
        if (!rule) return {};
        return {
            color: rule.foreground,
            ...(rule.fontStyle ? { fontStyle: rule.fontStyle } : {}),
        };
    };
    const base: CSSProperties = {
        color: tokens['--v2-text-1'],
        background: 'transparent',
        textShadow: 'none',
    };
    const style: ZkSyntaxStyle = {
        'code[class*="language-"]': base,
        'pre[class*="language-"]': base,
    };
    for (const { rule, keys } of PRISM_TOKEN_GROUPS) {
        const s = ruleStyle(rule);
        for (const key of keys) style[key] = s;
    }
    const aux: CSSProperties = { color: tokens['--v2-text-2'] };
    for (const key of ['punctuation', 'operator', 'delimiter']) style[key] = aux;
    style.linenumber = { color: tokens['--v2-text-4'] };
    return style;
}

/** 预构建双主题样式（静态常量，模块加载时一次性派生） */
export const ZK_SYNTAX_STYLES: Record<'light' | 'dark', ZkSyntaxStyle> = {
    light: buildZkSyntaxStyle('zk-light'),
    dark: buildZkSyntaxStyle('zk-dark'),
};
