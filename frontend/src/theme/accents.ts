/**
 * accents.ts — 强调色机制（指南 §3.4 终值表，P1b）
 *
 * ACCENT_PRESETS：6 色及旧版蓝色 × { light, dark } 双主题令牌组。
 * - light：accent / strong（白字 ≥4.5:1）/ soft 底（10%~12%）/ ring 环（32%~35%）
 * - dark：accent 取表中 Dark 基准；strong 复用 light 值；soft 16% / ring 42%
 *   （soft/ring 的 alpha 表达式取 light 基准色 RGB 通道计算，
 *    如靛蓝 dark soft = rgba(99,102,241,.16)）
 *
 * applyAccent(hex, effectiveTheme)：向 documentElement.style 写入
 * --v2-accent / --v2-accent-strong / --v2-accent-soft / --v2-accent-ring。
 * --v2-accent-hover / --v2-accent-active 已由 CSS color-mix(var(--v2-accent-strong))
 * 派生（globals.css），无需写入。未知 hex 一律回退靛蓝（默认色）。
 */

export type EffectiveTheme = 'light' | 'dark';

export interface AccentLightValues {
    accent: string;
    strong: string;
    soft: string;
    ring: string;
}

/** dark 档：strong 复用 light 值，故只有三项 */
export interface AccentDarkValues {
    accent: string;
    soft: string;
    ring: string;
}

export interface AccentPreset {
    /** 与 ThemePicker 持久化的 theme.accentColor 严格对应（大写 hex） */
    hex: string;
    label: string;
    light: AccentLightValues;
    dark: AccentDarkValues;
}

export const DEFAULT_ACCENT_HEX = '#6366F1';

export const ACCENT_PRESETS: readonly AccentPreset[] = [
    {
        hex: '#6366F1', label: '靛蓝',
        light: { accent: '#6366F1', strong: '#4F46E5', soft: 'rgba(99,102,241,.10)', ring: 'rgba(99,102,241,.32)' },
        dark: { accent: '#818CF8', soft: 'rgba(99,102,241,.16)', ring: 'rgba(99,102,241,.42)' },
    },
    {
        hex: '#8B5CF6', label: '紫罗兰',
        light: { accent: '#8B5CF6', strong: '#7C3AED', soft: 'rgba(139,92,246,.10)', ring: 'rgba(139,92,246,.32)' },
        dark: { accent: '#A78BFA', soft: 'rgba(139,92,246,.16)', ring: 'rgba(139,92,246,.42)' },
    },
    {
        hex: '#EC4899', label: '品红',
        light: { accent: '#EC4899', strong: '#DB2777', soft: 'rgba(236,72,153,.10)', ring: 'rgba(236,72,153,.32)' },
        dark: { accent: '#F472B6', soft: 'rgba(236,72,153,.16)', ring: 'rgba(236,72,153,.42)' },
    },
    {
        hex: '#F59E0B', label: '橙',
        light: { accent: '#F59E0B', strong: '#C2410C', soft: 'rgba(245,158,11,.12)', ring: 'rgba(245,158,11,.35)' },
        dark: { accent: '#FBBF24', soft: 'rgba(245,158,11,.16)', ring: 'rgba(245,158,11,.42)' },
    },
    {
        hex: '#10B981', label: '绿',
        light: { accent: '#10B981', strong: '#047857', soft: 'rgba(16,185,129,.12)', ring: 'rgba(16,185,129,.35)' },
        dark: { accent: '#34D399', soft: 'rgba(16,185,129,.16)', ring: 'rgba(16,185,129,.42)' },
    },
    {
        hex: '#EF4444', label: '红',
        light: { accent: '#EF4444', strong: '#DC2626', soft: 'rgba(239,68,68,.10)', ring: 'rgba(239,68,68,.32)' },
        dark: { accent: '#F87171', soft: 'rgba(239,68,68,.16)', ring: 'rgba(239,68,68,.42)' },
    },
    {
        hex: '#3B82F6', label: '蓝',
        light: { accent: '#3B82F6', strong: '#1D4ED8', soft: 'rgba(59,130,246,.10)', ring: 'rgba(59,130,246,.32)' },
        dark: { accent: '#60A5FA', soft: 'rgba(59,130,246,.16)', ring: 'rgba(59,130,246,.42)' },
    },
];

/**
 * applyAccent — 根节点一次性写入 4 个 v2 强调色令牌（§3.4）
 * @param hex 持久化的强调色（theme.accentColor），大小写不敏感；未知值回退靛蓝
 * @param effectiveTheme resolveTheme 语义后的有效主题（glass 已归一为 light）
 */
export function applyAccent(hex: string, effectiveTheme: EffectiveTheme): void {
    const normalized = (hex ?? '').toLowerCase();
    const preset = ACCENT_PRESETS.find((p) => p.hex.toLowerCase() === normalized) ?? ACCENT_PRESETS[0];
    const values: AccentLightValues = effectiveTheme === 'dark'
        ? { accent: preset.dark.accent, strong: preset.light.strong, soft: preset.dark.soft, ring: preset.dark.ring }
        : preset.light;
    const style = document.documentElement.style;
    style.setProperty('--v2-accent', values.accent);
    style.setProperty('--v2-accent-ink', effectiveTheme === 'dark' ? values.accent : `color-mix(in srgb, ${values.strong} 80%, black)`);
    style.setProperty('--v2-accent-strong', values.strong);
    style.setProperty('--v2-accent-soft', values.soft);
    style.setProperty('--v2-accent-ring', values.ring);
}
