/**
 * accents.ts — 强调色机制（指南 §3.4 终值表，P1b；2026-09 精简和谐版）
 *
 * ACCENT_PRESETS：6 色 × { light, dark, glass } 三主题令牌组。
 * 选色原则：与语义色（ok 绿 / warn 橙 / err 红）拉开色相，避免状态误读；
 * 色板内部色相间距 ≥30°，明度统一，白底对比度 ≥3:1（UI 组件级）。
 * - light：accent / strong（白字 ≥4.5:1）/ soft 底（10%）/ ring 环（32%）
 * - dark：accent 取表中 Dark 基准；strong 复用 light 值；soft 16% / ring 42%
 *   （soft/ring 的 alpha 表达式取 light 基准色 RGB 通道计算）
 * - glass：accent 取更清透的 Glass 基准（提饱和提明度，适配玻璃透光表面）；
 *   strong 复用 light 值；soft 12% / ring 35%（略强补偿玻璃透光衰减）
 *
 * applyAccent(hex, effectiveTheme)：向 documentElement.style 写入
 * --v2-accent / --v2-accent-strong / --v2-accent-soft / --v2-accent-ring。
 * --v2-accent-hover / --v2-accent-active 已由 CSS color-mix(var(--v2-accent-strong))
 * 派生（globals.css），无需写入。未知 hex 一律回退青瓷（默认色）。
 */

export type EffectiveTheme = 'light' | 'dark' | 'glass';

export interface AccentLightValues {
    accent: string;
    strong: string;
    soft: string;
    ring: string;
}

/** dark / glass 档：strong 复用 light 值，故只有三项 */
export interface AccentVariantValues {
    accent: string;
    soft: string;
    ring: string;
}

export interface AccentPreset {
    /** 与 ThemePicker 持久化的 theme.accentColor 严格对应（大写 hex） */
    hex: string;
    label: string;
    light: AccentLightValues;
    dark: AccentVariantValues;
    glass: AccentVariantValues;
}

export const DEFAULT_ACCENT_HEX = '#12967F';

/** 旧版预设 hex → 新版预设 hex（持久化的 theme.accentColor 兼容映射，大写） */
export const LEGACY_ACCENT_ALIASES: Readonly<Record<string, string>> = {
    // 初版 Tailwind 鲜艳色 → 现行基准（直达，避免链式中转断链）
    '#6366F1': '#5B6BE0',
    '#8B5CF6': '#8A63C9',
    '#EC4899': '#C9578A',
    '#3B82F6': '#2589D6',
    '#F59E0B': '#12967F',
    '#10B981': '#12967F',
    '#EF4444': '#C9578A',
    // 灰调版中被精简掉的语义冲突色 → 最接近的保留色
    '#C98A3A': '#12967F', // 橙（与 warn 冲突）→ 青瓷（默认）
    '#4E9E73': '#12967F', // 绿（与 ok 冲突）→ 青瓷（默认）
    '#C4453F': '#C9578A', // 红（与 err 冲突）→ 品红（暖色系最近）
    '#4C86C8': '#2589D6', // 灰蓝 → 蔚蓝（直系升级）
    // 灰调版靛蓝 → A 稿基准（云白冰川 #5B6BE0）
    '#5E63DE': '#5B6BE0',
};

/** 归一化强调色：大写 + 旧值映射；空值与未知值原样返回（由 applyAccent 回退默认） */
export function normalizeAccentHex(hex: string | undefined | null): string {
    const upper = (hex ?? '').toUpperCase();
    return LEGACY_ACCENT_ALIASES[upper] ?? upper;
}

export const ACCENT_PRESETS: readonly AccentPreset[] = [
    {
        hex: '#12967F', label: '青瓷',
        light: { accent: '#12967F', strong: '#0C7563', soft: 'rgba(18,150,127,.10)', ring: 'rgba(18,150,127,.32)' },
        dark: { accent: '#7FD4E8', soft: 'rgba(18,150,127,.16)', ring: 'rgba(18,150,127,.42)' },
        glass: { accent: '#0EA088', soft: 'rgba(14,160,136,.12)', ring: 'rgba(14,160,136,.35)' },
    },
    {
        // 柔夜墨蓝稿（D）的 accent #7FD4E8 为深底亮色：light/glass 档必须配深变体（白底 ≥3:1）
        hex: '#7FD4E8', label: '冰青',
        light: { accent: '#2492AC', strong: '#1A6E84', soft: 'rgba(36,146,172,.10)', ring: 'rgba(36,146,172,.32)' },
        dark: { accent: '#7FD4E8', soft: 'rgba(127,212,232,.16)', ring: 'rgba(127,212,232,.42)' },
        glass: { accent: '#259AB5', soft: 'rgba(37,154,181,.12)', ring: 'rgba(37,154,181,.35)' },
    },
    {
        hex: '#2589D6', label: '蔚蓝',
        light: { accent: '#2589D6', strong: '#1A6BA8', soft: 'rgba(37,137,214,.10)', ring: 'rgba(37,137,214,.32)' },
        dark: { accent: '#74B9EE', soft: 'rgba(37,137,214,.16)', ring: 'rgba(37,137,214,.42)' },
        glass: { accent: '#1B96E4', soft: 'rgba(27,150,228,.12)', ring: 'rgba(27,150,228,.35)' },
    },
    {
        // 基准值采纳云白冰川稿（A）的 #5B6BE0（与旧 #5E63DE 肉眼不可辨，旧值经 LEGACY 映射）
        hex: '#5B6BE0', label: '靛蓝',
        light: { accent: '#5B6BE0', strong: '#5054C8', soft: 'rgba(91,107,224,.10)', ring: 'rgba(91,107,224,.32)' },
        dark: { accent: '#8A8FF0', soft: 'rgba(91,107,224,.16)', ring: 'rgba(91,107,224,.42)' },
        glass: { accent: '#6C73EC', soft: 'rgba(108,115,236,.12)', ring: 'rgba(108,115,236,.35)' },
    },
    {
        // 薄藤紫稿（C）的 accent：AI 品牌紫调，与紫罗兰同族但更鲜亮
        hex: '#7B61E8', label: '藤紫',
        light: { accent: '#7B61E8', strong: '#6B4EE0', soft: 'rgba(123,97,232,.10)', ring: 'rgba(123,97,232,.32)' },
        dark: { accent: '#A78BFA', soft: 'rgba(123,97,232,.16)', ring: 'rgba(123,97,232,.42)' },
        glass: { accent: '#8A6FF2', soft: 'rgba(138,111,242,.12)', ring: 'rgba(138,111,242,.35)' },
    },
    {
        hex: '#8A63C9', label: '紫罗兰',
        light: { accent: '#8A63C9', strong: '#7451B5', soft: 'rgba(138,99,201,.10)', ring: 'rgba(138,99,201,.32)' },
        dark: { accent: '#B58CD6', soft: 'rgba(138,99,201,.16)', ring: 'rgba(138,99,201,.42)' },
        glass: { accent: '#9D6FDD', soft: 'rgba(157,111,221,.12)', ring: 'rgba(157,111,221,.35)' },
    },
    {
        hex: '#C9578A', label: '品红',
        light: { accent: '#C9578A', strong: '#B34677', soft: 'rgba(201,87,138,.10)', ring: 'rgba(201,87,138,.32)' },
        dark: { accent: '#E58AB5', soft: 'rgba(201,87,138,.16)', ring: 'rgba(201,87,138,.42)' },
        glass: { accent: '#D86399', soft: 'rgba(216,99,153,.12)', ring: 'rgba(216,99,153,.35)' },
    },
    {
        hex: '#566678', label: '石墨',
        light: { accent: '#566678', strong: '#42506A', soft: 'rgba(86,102,120,.10)', ring: 'rgba(86,102,120,.32)' },
        dark: { accent: '#9AA8BC', soft: 'rgba(86,102,120,.16)', ring: 'rgba(86,102,120,.42)' },
        glass: { accent: '#5F7A94', soft: 'rgba(95,122,148,.12)', ring: 'rgba(95,122,148,.35)' },
    },
];

/**
 * applyAccent — 根节点一次性写入 4 个 v2 强调色令牌（§3.4）
 * @param hex 持久化的强调色（theme.accentColor），大小写不敏感；未知值回退青瓷
 * @param effectiveTheme 有效主题（glass 拥有独立清透档，不再归一为 light）
 */
export function applyAccent(hex: string, effectiveTheme: EffectiveTheme): void {
    const normalized = normalizeAccentHex(hex);
    const preset = ACCENT_PRESETS.find((p) => p.hex === normalized) ?? ACCENT_PRESETS[0];
    const variant = effectiveTheme === 'dark' ? preset.dark : effectiveTheme === 'glass' ? preset.glass : null;
    const values: AccentLightValues = variant
        ? { accent: variant.accent, strong: preset.light.strong, soft: variant.soft, ring: variant.ring }
        : preset.light;
    const style = document.documentElement.style;
    style.setProperty('--v2-accent', values.accent);
    style.setProperty('--v2-accent-ink', effectiveTheme === 'dark' ? values.accent : `color-mix(in srgb, ${values.strong} 80%, black)`);
    style.setProperty('--v2-accent-strong', values.strong);
    style.setProperty('--v2-accent-soft', values.soft);
    style.setProperty('--v2-accent-ring', values.ring);
}
