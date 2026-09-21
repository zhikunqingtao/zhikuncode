/**
 * design-tokens.ts — 与 globals.css `--v2-*` 令牌等值的 JS 镜像
 * SPEC: 改造指南 §4.5
 *
 * 供 Monaco / xterm / Mermaid / Recharts 等 JS 消费方统一取色，禁止各库各自造色。
 * ⚠ 改任何色值必须同步改 globals.css，并跑 design-tokens.spec.ts（防漂移）。
 *
 * 键名 = CSS 变量原名，保证与 globals.css 的镜像关系一目了然、可被 spec 逐键断言。
 * TOKENS.light 镜像 `:root` v2 块；TOKENS.dark 镜像 `.dark` v2 块。
 * `.dark` 块未覆盖的条目（accent 系、圆角、时长、缓动）在深色下沿用 :root 值，
 * 消费方按需从 TOKENS.light 读取（与 CSS 变量继承行为一致）。
 *
 * 动态派生（跟随强调色，不触碰静态镜像）：
 * - getChartColors(mode, accentHex?)：CHART_COLORS 拷贝 + chart-1 替换为当前 accent
 * - getMonacoZkThemes(mode, accentHex?)：MONACO_ZK_THEMES 基底 + 4 处 accent 色派生
 * - getXtermPalette(mode, accentHex?)：XTERM_ANSI 基底 + cursor/selection 派生
 * （accents.ts 不依赖本文件，无循环依赖风险）
 */
import { ACCENT_PRESETS, DEFAULT_ACCENT_HEX, normalizeAccentHex } from '@/theme/accents';

export const TOKENS = {
    light: {
        '--v2-bg-app': '#F2F7F4',
        '--v2-bg-surface': '#FFFFFF',
        '--v2-bg-surface-2': '#EAF3ED',
        '--v2-bg-sunken': '#E0EDE4',
        '--v2-bg-hover': 'rgba(20,40,34,.045)',
        '--v2-bg-active': 'rgba(20,40,34,.07)',
        '--v2-border-hairline': 'rgba(20,40,34,.08)',
        '--v2-border-strong': 'rgba(20,40,34,.14)',
        '--v2-overlay': 'rgba(20,40,34,.32)',
        '--v2-text-1': '#1F2430',
        '--v2-text-2': '#49546B',
        '--v2-text-3': '#596A60',
        '--v2-text-4': '#596A60',
        '--v2-ok': '#2C724B',
        '--v2-ok-soft': 'rgba(63,143,98,.12)',
        '--v2-ok-strong': '#245E3E',
        '--v2-warn': '#8F5C12',
        '--v2-warn-soft': 'rgba(180,116,27,.12)',
        '--v2-warn-strong': '#7A4E0F',
        '--v2-err': '#B03B35',
        '--v2-err-soft': 'rgba(196,69,63,.12)',
        '--v2-err-strong': '#A3352F',
        '--v2-diff-add-bg': 'rgba(63,143,98,.10)',
        '--v2-diff-remove-bg': 'rgba(196,69,63,.10)',
        '--v2-code-bg': '#E4F0E8',
        '--v2-accent': '#12967F',
        '--v2-accent-strong': '#0C7563',
        '--v2-accent-soft': 'rgba(18,150,127,.10)',
        '--v2-accent-ring': 'rgba(18,150,127,.32)',
        '--v2-shadow-xs': '0 1px 2px rgba(20,40,34,.04)',
        '--v2-shadow-sm': '0 1px 2px rgba(20,40,34,.04),0 2px 8px rgba(20,40,34,.05)',
        '--v2-shadow-md': '0 2px 6px rgba(20,40,34,.05),0 10px 28px rgba(20,40,34,.08)',
        '--v2-shadow-lg': '0 4px 12px rgba(20,40,34,.07),0 20px 56px rgba(20,40,34,.14)',
        '--v2-shadow-inset': 'inset 2px 2px 5px rgba(20,40,34,.12),inset -2px -2px 5px rgba(255,255,255,.7)',
        '--v2-shadow-raised': '-3px -3px 8px rgba(255,255,255,.85),3px 3px 8px rgba(20,40,34,.10)',
        '--v2-shadow-raised-hover': '-1.5px -1.5px 4px rgba(255,255,255,.85),1.5px 1.5px 4px rgba(20,40,34,.10)',
        '--v2-shadow-pressed': 'inset 2px 2px 5px rgba(20,40,34,.12),inset -2px -2px 5px rgba(255,255,255,.7)',
        '--v2-shadow-soft': '-8px -8px 20px rgba(255,255,255,.85),8px 8px 20px rgba(20,40,34,.07)',
        '--v2-shadow-soft-sm': '-4px -4px 10px rgba(255,255,255,.8),4px 4px 10px rgba(20,40,34,.06)',
        '--v2-r-xs': '6px',
        '--v2-r-sm': '10px',
        '--v2-r-md': '14px',
        '--v2-r-lg': '16px',
        '--v2-r-panel': '22px',
        '--v2-r-pill': '999px',
        '--v2-dur-fast': '120ms',
        '--v2-dur-base': '180ms',
        '--v2-dur-slow': '240ms',
        '--v2-dur-sheet': '240ms',
        '--v2-ease': 'cubic-bezier(.2,.8,.2,1)',
        '--v2-ease-in-out': 'cubic-bezier(.4,0,.2,1)',
        '--v2-spring': 'cubic-bezier(.34,1.56,.64,1)',
        '--v2-chart-1': '#5E63DE',
        '--v2-chart-2': '#2C8C7E',
        '--v2-chart-3': '#B4741B',
        '--v2-chart-4': '#C4453F',
        '--v2-chart-5': '#2D7DB3',
        '--v2-chart-6': '#7E4FB8',
        '--v2-chart-7': '#6B8E23',
        '--v2-chart-8': '#596A60',
    },
    dark: {
        '--v2-bg-app': '#161A22',
        '--v2-bg-surface': '#1E2430',
        '--v2-bg-surface-2': '#262E3D',
        '--v2-bg-sunken': '#12161E',
        '--v2-bg-hover': 'rgba(148,163,184,.05)',
        '--v2-bg-active': 'rgba(148,163,184,.08)',
        '--v2-border-hairline': 'rgba(148,163,184,.12)',
        '--v2-border-strong': 'rgba(148,163,184,.18)',
        '--v2-overlay': 'rgba(0,0,0,.55)',
        '--v2-text-1': '#DCE3EE',
        '--v2-text-2': '#A3AFC2',
        '--v2-text-3': '#8B99AD',
        '--v2-text-4': '#8B99AD',
        '--v2-ok': '#7BC79A',
        '--v2-ok-soft': 'rgba(123,199,154,.14)',
        '--v2-ok-strong': '#9AD6B1',
        '--v2-warn': '#E0A94A',
        '--v2-warn-soft': 'rgba(224,169,74,.14)',
        '--v2-warn-strong': '#EBC27A',
        '--v2-err': '#F0756C',
        '--v2-err-soft': 'rgba(240,117,108,.14)',
        '--v2-err-strong': '#C4453F',
        '--v2-diff-add-bg': 'rgba(123,199,154,.12)',
        '--v2-diff-remove-bg': 'rgba(240,117,108,.12)',
        '--v2-code-bg': '#1A2029',
        '--v2-shadow-xs': '0 1px 2px rgba(0,0,0,.4)',
        '--v2-shadow-sm': '0 1px 2px rgba(0,0,0,.4),0 2px 8px rgba(0,0,0,.35)',
        '--v2-shadow-md': '0 2px 6px rgba(0,0,0,.4),0 10px 28px rgba(0,0,0,.45)',
        '--v2-shadow-lg': '0 4px 12px rgba(0,0,0,.5),0 20px 56px rgba(0,0,0,.55)',
        '--v2-shadow-inset': 'inset 2px 2px 6px rgba(0,0,0,.5)',
        '--v2-shadow-raised': 'inset 0 1px 0 rgba(255,255,255,.04),2px 3px 8px rgba(0,0,0,.45)',
        '--v2-shadow-raised-hover': 'inset 0 1px 0 rgba(255,255,255,.04),1px 1.5px 4px rgba(0,0,0,.45)',
        '--v2-shadow-pressed': 'inset 2px 2px 6px rgba(0,0,0,.5)',
        '--v2-shadow-soft': 'none',
        '--v2-shadow-soft-sm': 'none',
        '--v2-chart-1': '#8A8FF0',
        '--v2-chart-2': '#5FC4B2',
        '--v2-chart-3': '#E0A94A',
        '--v2-chart-4': '#EF6B63',
        '--v2-chart-5': '#6DB4E6',
        '--v2-chart-6': '#B58CD6',
        '--v2-chart-7': '#A3C24E',
        '--v2-chart-8': '#8B99AD',
    },
} as const;

/**
 * ACCENT_DERIVED — `--v2-accent-hover / --v2-accent-active` 的 color-mix 派生表达式。
 *
 * 这两个令牌是 CSS 运行时的 color-mix 计算值（§3.4），没有静态色值，
 * 因此不放进 TOKENS；§9.4 明确禁止 JS 用 getComputedStyle 读取 color-mix
 * 计算结果做逻辑（跨浏览器不一致），JS 侧需要 hover/active 档时直接使用
 * TOKENS.light['--v2-accent-strong']（静态回退 = strong 本身）。
 * 此处仅作镜像常量，供防漂移 spec 断言 CSS 中的表达式未被单方修改。
 */
export const ACCENT_DERIVED = {
    hover: 'color-mix(in srgb, var(--v2-accent-strong) 92%, black)',
    active: 'color-mix(in srgb, var(--v2-accent-strong) 84%, black)',
} as const;

/** §4.1 图表数据色板（Recharts / React Flow / Mermaid pie 共用，按序取色） */
export const CHART_COLORS = {
    light: ['#5E63DE', '#2C8C7E', '#B4741B', '#C4453F', '#2D7DB3', '#7E4FB8', '#6B8E23', '#596A60'],
    dark: ['#8A8FF0', '#5FC4B2', '#E0A94A', '#EF6B63', '#6DB4E6', '#B58CD6', '#A3C24E', '#8B99AD'],
} as const;

export type ThemeMode = 'light' | 'dark' | 'glass';

/**
 * resolveTheme — effectiveTheme 解析（§4.5 第二职责）
 * Glass → 'light'；其余原样返回。
 * Recharts / Mermaid / Monaco 三处统一消费，替代裸 isDark 布尔。
 */
export function resolveTheme(mode: ThemeMode): 'light' | 'dark' {
    if (mode === 'glass') return 'light';
    return mode;
}

/**
 * accentForMode — 从 ACCENT_PRESETS 查当前强调色在指定档位的 accent 值。
 * 空值/未知值回退 DEFAULT_ACCENT_HEX（青瓷）；大小写与旧版 hex 经 normalizeAccentHex 归一。
 * mode='glass' 的调用方应先经 resolveTheme 归一为 light。
 */
function accentForMode(mode: 'light' | 'dark', accentHex?: string): string {
    const normalized = normalizeAccentHex(accentHex);
    const preset = ACCENT_PRESETS.find((p) => p.hex === normalized)
        ?? ACCENT_PRESETS.find((p) => p.hex === DEFAULT_ACCENT_HEX)
        ?? ACCENT_PRESETS[0];
    return mode === 'dark' ? preset.dark.accent : preset.light.accent;
}

/**
 * getChartColors — 跟随强调色的图表色板（§4.1 动态版）。
 * 返回 CHART_COLORS[mode] 的拷贝，chart-1（[0]）替换为当前 accent 在该档位的值；
 * 静态 CHART_COLORS 保持不变（globals.css 对拍与存量消费方不受影响）。
 */
export function getChartColors(mode: 'light' | 'dark', accentHex?: string): string[] {
    const colors: string[] = [...CHART_COLORS[mode]];
    colors[0] = accentForMode(mode, accentHex);
    return colors;
}

/* ================= §4.2 Monaco zk 主题（从 TOKENS 派生，供 defineTheme 使用） ================= */

export interface MonacoThemeDef {
    base: 'vs' | 'vs-dark';
    inherit: boolean;
    rules: { token: string; foreground: string; fontStyle?: string }[];
    colors: Record<string, string>;
}

export const MONACO_ZK_THEMES: Record<'zk-light' | 'zk-dark', MonacoThemeDef> = {
    'zk-light': {
        base: 'vs',
        inherit: true,
        rules: [
            { token: 'comment', foreground: TOKENS.light['--v2-text-3'], fontStyle: 'italic' },
            { token: 'string', foreground: '#2C724B' },
            { token: 'string.escape', foreground: '#4F5878' },
            { token: 'string.special', foreground: '#2C724B' },
            { token: 'keyword', foreground: '#6E45A6' },
            { token: 'number', foreground: '#8F5C12' },
            { token: 'type', foreground: '#4F5878' },
            { token: 'class', foreground: '#4F5878' },
            { token: 'function', foreground: '#5054C8' },
            { token: 'variable', foreground: TOKENS.light['--v2-text-1'] },
            { token: 'constant', foreground: '#8F5C12' },
            { token: 'enum', foreground: '#8F5C12' },
        ],
        colors: {
            'editor.background': TOKENS.light['--v2-code-bg'],
            'editor.foreground': TOKENS.light['--v2-text-1'],
            'editorLineNumber.foreground': TOKENS.light['--v2-text-4'],
            'editorLineNumber.activeForeground': TOKENS.light['--v2-text-2'],
            'editor.lineHighlightBackground': '#1F24300A',
            'editor.selectionBackground': '#12967F40',
            'editorCursor.foreground': TOKENS.light['--v2-accent'],
            'editorIndentGuide.background1': '#1F243014',
            'editorGutter.background': TOKENS.light['--v2-code-bg'],
            'editorWidget.background': TOKENS.light['--v2-bg-surface'],
            'editorWidget.border': '#1F243014',
            'editorSuggestWidget.selectedBackground': '#12967F1F',
            'scrollbarSlider.background': '#596A6040',
            'editorBracketMatch.border': '#12967F80',
        },
    },
    'zk-dark': {
        base: 'vs-dark',
        inherit: true,
        rules: [
            { token: 'comment', foreground: TOKENS.dark['--v2-text-3'], fontStyle: 'italic' },
            { token: 'string', foreground: '#6FA88A' },
            { token: 'string.escape', foreground: '#9DA5C4' },
            { token: 'string.special', foreground: '#6FA88A' },
            { token: 'keyword', foreground: '#B58CD6' },
            { token: 'number', foreground: '#D2A24C' },
            { token: 'type', foreground: '#9DA5C4' },
            { token: 'class', foreground: '#9DA5C4' },
            { token: 'function', foreground: '#8A8FF0' },
            { token: 'variable', foreground: TOKENS.dark['--v2-text-1'] },
            { token: 'constant', foreground: '#D2A24C' },
            { token: 'enum', foreground: '#D2A24C' },
        ],
        colors: {
            'editor.background': TOKENS.dark['--v2-code-bg'],
            'editor.foreground': TOKENS.dark['--v2-text-1'],
            'editorLineNumber.foreground': TOKENS.dark['--v2-text-4'],
            'editorLineNumber.activeForeground': TOKENS.dark['--v2-text-2'],
            'editor.lineHighlightBackground': '#DCE3EE0D',
            'editor.selectionBackground': '#7FD4E840',
            'editorCursor.foreground': '#7FD4E8',
            'editorIndentGuide.background1': '#DCE3EE12',
            'editorGutter.background': TOKENS.dark['--v2-code-bg'],
            'editorWidget.background': TOKENS.dark['--v2-bg-surface'],
            'editorWidget.border': '#DCE3EE12',
            'editorSuggestWidget.selectedBackground': '#7FD4E81F',
            'scrollbarSlider.background': '#8B99AD80',
            'editorBracketMatch.border': '#7FD4E880',
        },
    },
} as const;

/**
 * getMonacoZkThemes — 跟随强调色的 Monaco zk 主题（§4.2 动态版）。
 * 以静态 MONACO_ZK_THEMES 为基底（非 accent 色值一律不动），
 * 每档 4 处 accent 相关色由当前 accent 派生（alpha 后缀沿用既有模式）：
 * - editor.selectionBackground：accent + '40'
 * - editorCursor.foreground：accent 本色
 * - editorSuggestWidget.selectedBackground：accent + '1F'
 * - editorBracketMatch.border：accent + '80'
 * 缺省/未知 accentHex 的结果与静态 MONACO_ZK_THEMES 完全等值。
 */
export function getMonacoZkThemes(mode: 'light' | 'dark', accentHex?: string): MonacoThemeDef {
    const base = MONACO_ZK_THEMES[mode === 'light' ? 'zk-light' : 'zk-dark'];
    const accent = accentForMode(mode, accentHex);
    return {
        base: base.base,
        inherit: base.inherit,
        rules: base.rules.map((rule) => ({ ...rule })),
        colors: {
            ...base.colors,
            'editor.selectionBackground': `${accent}40`,
            'editorCursor.foreground': accent,
            'editorSuggestWidget.selectedBackground': `${accent}1F`,
            'editorBracketMatch.border': `${accent}80`,
        },
    };
}

/* ================= §4.3 xterm / ANSI 16 色（供 xterm theme 与 ANSI-to-HTML 使用） ================= */

export interface AnsiPalette {
    black: string; red: string; green: string; yellow: string;
    blue: string; magenta: string; cyan: string; white: string;
    brightBlack: string; brightRed: string; brightGreen: string; brightYellow: string;
    brightBlue: string; brightMagenta: string; brightCyan: string; brightWhite: string;
    background: string; foreground: string; cursor: string; selectionBackground: string;
}

export const XTERM_ANSI: Record<'light' | 'dark', AnsiPalette> = {
    light: {
        background: TOKENS.light['--v2-bg-sunken'],
        foreground: TOKENS.light['--v2-text-1'],
        cursor: TOKENS.light['--v2-accent'],
        selectionBackground: '#12967F40',
        black: TOKENS.light['--v2-text-1'],
        red: '#B03B35',
        green: '#2C724B',
        yellow: '#8F5C12',
        blue: '#5054C8',
        magenta: '#6E45A6',
        cyan: '#2C7A78',
        white: '#D9D5CD',
        brightBlack: TOKENS.light['--v2-text-3'],
        brightRed: '#C4453F',
        brightGreen: '#3F8F62',
        brightYellow: TOKENS.light['--v2-warn'],
        brightBlue: TOKENS.light['--v2-accent'],
        brightMagenta: '#8A63C9',
        brightCyan: '#3A9E9B',
        brightWhite: TOKENS.light['--v2-bg-app'],
    },
    dark: {
        background: TOKENS.dark['--v2-bg-sunken'],
        foreground: TOKENS.dark['--v2-text-1'],
        cursor: '#7FD4E8',
        selectionBackground: '#7FD4E840',
        black: TOKENS.dark['--v2-bg-surface-2'],
        red: '#EF6B63',
        green: TOKENS.dark['--v2-ok'],
        yellow: '#E0A94A',
        blue: '#8A8FF0',
        magenta: '#B58CD6',
        cyan: '#6FC2BE',
        white: TOKENS.dark['--v2-text-1'],
        brightBlack: TOKENS.dark['--v2-text-4'],
        brightRed: '#F59A94',
        brightGreen: '#9AD6B1',
        brightYellow: '#EBC27A',
        brightBlue: '#AEB2F5',
        brightMagenta: '#CDB0E6',
        brightCyan: '#9ADBD7',
        brightWhite: '#FFFFFF',
    },
} as const;

/**
 * getXtermPalette — 跟随强调色的 xterm zk 色板（§4.3 动态版）。
 * 以静态 XTERM_ANSI 为基底（ANSI 16 色不动），cursor 与
 * selectionBackground（accent + '40' alpha）由当前 accent 派生；
 * 缺省/未知 accentHex 的结果与静态 XTERM_ANSI 完全等值。
 */
export function getXtermPalette(mode: 'light' | 'dark', accentHex?: string): AnsiPalette {
    const accent = accentForMode(mode, accentHex);
    return { ...XTERM_ANSI[mode], cursor: accent, selectionBackground: `${accent}40` };
}

/** ANSI 数字码 → AnsiPalette 键名（供 ANSI-to-HTML 渲染器查色） */
export const ANSI_CODE_TO_KEY: Record<string, keyof AnsiPalette> = {
    '30': 'black', '31': 'red', '32': 'green', '33': 'yellow',
    '34': 'blue', '35': 'magenta', '36': 'cyan', '37': 'white',
    '90': 'brightBlack', '91': 'brightRed', '92': 'brightGreen', '93': 'brightYellow',
    '94': 'brightBlue', '95': 'brightMagenta', '96': 'brightCyan', '97': 'brightWhite',
} as const;
