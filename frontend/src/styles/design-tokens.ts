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
 */
export const TOKENS = {
    light: {
        '--v2-bg-app': '#F5F6F8',
        '--v2-bg-surface': '#FFFFFF',
        '--v2-bg-surface-2': '#F8F9FC',
        '--v2-bg-sunken': '#F0F2F7',
        '--v2-bg-hover': 'rgba(16,24,40,.045)',
        '--v2-bg-active': 'rgba(16,24,40,.07)',
        '--v2-border-hairline': 'rgba(16,24,40,.08)',
        '--v2-border-strong': 'rgba(16,24,40,.14)',
        '--v2-overlay': 'rgba(16,24,40,.32)',
        '--v2-text-1': '#202737',
        '--v2-text-2': '#505D70',
        '--v2-text-3': '#606C7E',
        '--v2-text-4': '#606C7E',
        '--v2-ok': '#16704D',
        '--v2-ok-soft': 'rgba(18,166,107,.12)',
        '--v2-ok-strong': '#065F46',
        '--v2-warn': '#92400E',
        '--v2-warn-soft': 'rgba(217,143,31,.12)',
        '--v2-warn-strong': '#92400E',
        '--v2-err': '#B3303B',
        '--v2-err-soft': 'rgba(220,76,72,.12)',
        '--v2-err-strong': '#B91C1C',
        '--v2-diff-add-bg': 'rgba(18,166,107,.10)',
        '--v2-diff-remove-bg': 'rgba(220,76,72,.10)',
        '--v2-accent': '#6366F1',
        '--v2-accent-strong': '#4F46E5',
        '--v2-accent-soft': 'rgba(99,102,241,.10)',
        '--v2-accent-ring': 'rgba(99,102,241,.32)',
        '--v2-shadow-xs': '0 1px 2px rgba(16,24,40,.04)',
        '--v2-shadow-sm': '0 1px 2px rgba(16,24,40,.04),0 2px 8px rgba(16,24,40,.05)',
        '--v2-shadow-md': '0 2px 6px rgba(16,24,40,.05),0 10px 28px rgba(16,24,40,.08)',
        '--v2-shadow-lg': '0 4px 12px rgba(16,24,40,.07),0 20px 56px rgba(16,24,40,.14)',
        '--v2-shadow-inset': 'inset 0 2px 5px rgba(16,24,40,.07)',
        '--v2-shadow-soft': '-8px -8px 20px rgba(255,255,255,.85),8px 8px 20px rgba(16,24,40,.07)',
        '--v2-shadow-soft-sm': '-4px -4px 10px rgba(255,255,255,.8),4px 4px 10px rgba(16,24,40,.06)',
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
        '--v2-chart-1': '#6366F1',
        '--v2-chart-2': '#0D9488',
        '--v2-chart-3': '#D97706',
        '--v2-chart-4': '#E11D48',
        '--v2-chart-5': '#0284C7',
        '--v2-chart-6': '#9333EA',
        '--v2-chart-7': '#65A30D',
        '--v2-chart-8': '#64748B',
    },
    dark: {
        '--v2-bg-app': '#101217',
        '--v2-bg-surface': '#191C24',
        '--v2-bg-surface-2': '#1C212B',
        '--v2-bg-sunken': '#11151C',
        '--v2-bg-hover': 'rgba(255,255,255,.05)',
        '--v2-bg-active': 'rgba(255,255,255,.08)',
        '--v2-border-hairline': 'rgba(255,255,255,.08)',
        '--v2-border-strong': 'rgba(255,255,255,.15)',
        '--v2-overlay': 'rgba(0,0,0,.55)',
        '--v2-text-1': '#EBEDF3',
        '--v2-text-2': '#B9C2D1',
        '--v2-text-3': '#9CA8BA',
        '--v2-text-4': '#9CA8BA',
        '--v2-ok': '#80D2AC',
        '--v2-ok-soft': 'rgba(52,211,153,.14)',
        '--v2-ok-strong': '#6EE7B7',
        '--v2-warn': '#FBBF24',
        '--v2-warn-soft': 'rgba(251,191,36,.14)',
        '--v2-warn-strong': '#FDE68A',
        '--v2-err': '#EE9DA6',
        '--v2-err-soft': 'rgba(248,113,113,.14)',
        '--v2-err-strong': '#DC2626',
        '--v2-diff-add-bg': 'rgba(52,211,153,.12)',
        '--v2-diff-remove-bg': 'rgba(248,113,113,.12)',
        '--v2-shadow-xs': '0 1px 2px rgba(0,0,0,.4)',
        '--v2-shadow-sm': '0 1px 2px rgba(0,0,0,.4),0 2px 8px rgba(0,0,0,.35)',
        '--v2-shadow-md': '0 2px 6px rgba(0,0,0,.4),0 10px 28px rgba(0,0,0,.45)',
        '--v2-shadow-lg': '0 4px 12px rgba(0,0,0,.5),0 20px 56px rgba(0,0,0,.55)',
        '--v2-shadow-inset': 'inset 0 2px 6px rgba(0,0,0,.5)',
        '--v2-shadow-soft': 'none',
        '--v2-shadow-soft-sm': 'none',
        '--v2-chart-1': '#818CF8',
        '--v2-chart-2': '#2DD4BF',
        '--v2-chart-3': '#FBBF24',
        '--v2-chart-4': '#FB7185',
        '--v2-chart-5': '#38BDF8',
        '--v2-chart-6': '#C084FC',
        '--v2-chart-7': '#A3E635',
        '--v2-chart-8': '#94A3B8',
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
    light: ['#6366F1', '#0D9488', '#D97706', '#E11D48', '#0284C7', '#9333EA', '#65A30D', '#64748B'],
    dark: ['#818CF8', '#2DD4BF', '#FBBF24', '#FB7185', '#38BDF8', '#C084FC', '#A3E635', '#94A3B8'],
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
            { token: 'string', foreground: '#0E8A64' },
            { token: 'keyword', foreground: '#6D28D9' },
            { token: 'number', foreground: '#C2410C' },
            { token: 'type', foreground: '#B45309' },
            { token: 'class', foreground: '#B45309' },
            { token: 'function', foreground: '#4F46E5' },
            { token: 'variable', foreground: TOKENS.light['--v2-text-1'] },
            { token: 'constant', foreground: '#C8427D' },
            { token: 'enum', foreground: '#C8427D' },
        ],
        colors: {
            'editor.background': TOKENS.light['--v2-bg-sunken'],
            'editor.foreground': TOKENS.light['--v2-text-1'],
            'editorLineNumber.foreground': TOKENS.light['--v2-text-4'],
            'editorLineNumber.activeForeground': TOKENS.light['--v2-text-2'],
            'editor.lineHighlightBackground': '#0F172A0A',
            'editor.selectionBackground': '#6366F140',
            'editorCursor.foreground': TOKENS.light['--v2-accent'],
            'editorIndentGuide.background1': '#0F172A14',
            'editorGutter.background': TOKENS.light['--v2-bg-sunken'],
            'editorWidget.background': TOKENS.light['--v2-bg-surface'],
            'editorWidget.border': '#0F172A14',
            'editorSuggestWidget.selectedBackground': '#6366F11F',
            'scrollbarSlider.background': '#CBD5E180',
            'editorBracketMatch.border': '#6366F180',
        },
    },
    'zk-dark': {
        base: 'vs-dark',
        inherit: true,
        rules: [
            { token: 'comment', foreground: TOKENS.dark['--v2-text-3'], fontStyle: 'italic' },
            { token: 'string', foreground: '#3BC49B' },
            { token: 'keyword', foreground: '#A78BFA' },
            { token: 'number', foreground: '#F0A94E' },
            { token: 'type', foreground: '#F0C24E' },
            { token: 'class', foreground: '#F0C24E' },
            { token: 'function', foreground: '#818CF8',
            },
            { token: 'variable', foreground: TOKENS.dark['--v2-text-1'] },
            { token: 'constant', foreground: '#EC7CAC' },
            { token: 'enum', foreground: '#EC7CAC' },
        ],
        colors: {
            'editor.background': TOKENS.dark['--v2-bg-sunken'],
            'editor.foreground': TOKENS.dark['--v2-text-1'],
            'editorLineNumber.foreground': TOKENS.dark['--v2-text-4'],
            'editorLineNumber.activeForeground': TOKENS.dark['--v2-text-2'],
            'editor.lineHighlightBackground': '#FFFFFF0D',
            'editor.selectionBackground': '#818CF840',
            'editorCursor.foreground': '#818CF8',
            'editorIndentGuide.background1': '#FFFFFF12',
            'editorGutter.background': TOKENS.dark['--v2-bg-sunken'],
            'editorWidget.background': TOKENS.dark['--v2-bg-surface'],
            'editorWidget.border': '#FFFFFF12',
            'editorSuggestWidget.selectedBackground': '#818CF81F',
            'scrollbarSlider.background': '#47556980',
            'editorBracketMatch.border': '#818CF880',
        },
    },
} as const;

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
        selectionBackground: '#6366F140',
        black: TOKENS.light['--v2-text-1'],
        red: '#C23A36',
        green: '#0E8A64',
        yellow: '#B45309',
        blue: '#4F46E5',
        magenta: '#C8427D',
        cyan: '#0E7490',
        white: '#D5DAE3',
        brightBlack: TOKENS.light['--v2-text-3'],
        brightRed: '#E0534F',
        brightGreen: '#17A67B',
        brightYellow: TOKENS.light['--v2-warn'],
        brightBlue: TOKENS.light['--v2-accent'],
        brightMagenta: '#E15C97',
        brightCyan: '#0891B2',
        brightWhite: TOKENS.light['--v2-bg-app'],
    },
    dark: {
        background: TOKENS.dark['--v2-bg-sunken'],
        foreground: TOKENS.dark['--v2-text-1'],
        cursor: '#818CF8',
        selectionBackground: '#818CF840',
        black: TOKENS.dark['--v2-bg-surface-2'],
        red: '#F17570',
        green: TOKENS.dark['--v2-ok'],
        yellow: '#F0C24E',
        blue: '#818CF8',
        magenta: '#EC7CAC',
        cyan: '#5EEAD4',
        white: TOKENS.dark['--v2-text-1'],
        brightBlack: TOKENS.dark['--v2-text-4'],
        brightRed: '#FCA5A1',
        brightGreen: '#6EE7B7',
        brightYellow: '#FDE68A',
        brightBlue: '#A5B4FC',
        brightMagenta: '#F9A8D4',
        brightCyan: '#99F6E4',
        brightWhite: '#FFFFFF',
    },
} as const;

/** ANSI 数字码 → AnsiPalette 键名（供 ANSI-to-HTML 渲染器查色） */
export const ANSI_CODE_TO_KEY: Record<string, keyof AnsiPalette> = {
    '30': 'black', '31': 'red', '32': 'green', '33': 'yellow',
    '34': 'blue', '35': 'magenta', '36': 'cyan', '37': 'white',
    '90': 'brightBlack', '91': 'brightRed', '92': 'brightGreen', '93': 'brightYellow',
    '94': 'brightBlue', '95': 'brightMagenta', '96': 'brightCyan', '97': 'brightWhite',
} as const;
