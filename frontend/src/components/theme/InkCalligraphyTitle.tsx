/**
 * InkCalligraphyTitle — 波次2② 毛笔书写标题（浓郁档 · 空状态 Hero）
 *
 * 「落墨成书」：空状态大标题「今天想构建什么？」以行楷字形轮廓
 * （hero-calligraphy.ts，fontTools 自 brush-kaiti.ttf 提取，OFL 许可）逐 contour
 * 勾勒——stroke-dashoffset 1→0 按字序/ contour 序错峰（cubic-bezier(.45,0,.25,1)
 * 模拟起笔顿→行笔提→收笔回锋），最后一笔落定后 fill 淡入、描边淡出（200-400ms）。
 *
 * 门控：仅 ink 双模式 + cinematic（fx-ink-rich 浓郁档）渲染 SVG；
 * 其余（克制档/其他主题）回退 fallback 文本（EmptyHero 原 JSX，零变化）。
 * 动画仅 motion-full + prefers-reduced-motion:no-preference 播放（CSS 门控块收口）；
 * motion-reduced/off 与系统降级直接显示成品字（path 默认态即 fill 成品）。
 * 触发：挂载即播一次（空状态即首屏）；会话开始组件卸载，新会话重挂载自然重播。
 */

import type { CSSProperties, ReactNode } from 'react';
import { defaultInkHavocFx, useConfigStore } from '@/store/configStore';
import { HERO_CALLIGRAPHY } from './hero-calligraphy';

/** ink 双模式集合（门控判断用） */
const INK_MODES = new Set(['ink-havoc', 'ink-havoc-night']);

/** 书写节奏令牌（秒）：字间错峰 / 字内 contour 错峰 / 单 contour 描边时长 */
const CHAR_STEP = 0.26;
const CONTOUR_STEP = 0.09;
const STROKE_DUR = 0.75;

/** 计算单条 contour 的落笔时刻（字序大错峰 + 字内 contour 小错峰） */
export function calligraphyStrokeDelay(charIndex: number, contourIndex: number): number {
    return charIndex * CHAR_STEP + contourIndex * CONTOUR_STEP;
}

/** 全部 contour 落定时刻（末字末 contour 描边结束），fill 淡入略提前交叠衔接 */
export function calligraphyFillDelay(): number {
    const chars = HERO_CALLIGRAPHY.chars;
    const lastStrokeEnd = Math.max(
        ...chars.map((c, i) => calligraphyStrokeDelay(i, c.paths.length - 1) + STROKE_DUR),
    );
    return Math.max(0, lastStrokeEnd - 0.2);
}

export function InkCalligraphyTitle({ fallback }: { fallback: ReactNode }) {
    const theme = useConfigStore(s => s.theme);
    const fx = theme.inkHavocFx ?? defaultInkHavocFx();
    const enabled = INK_MODES.has(theme.mode) && fx.cinematic;

    // 克制档/其他主题：回退原文本标题（calm 档零变化）
    if (!enabled) return <>{fallback}</>;

    const fillDelay = calligraphyFillDelay();

    return (
        <svg
            className="ink-calli-title"
            viewBox={HERO_CALLIGRAPHY.viewBox}
            role="img"
            aria-label="今天想构建什么？"
        >
            {HERO_CALLIGRAPHY.chars.map((char, charIndex) => (
                <g
                    key={`${char.ch}-${charIndex}`}
                    transform={`translate(${char.x}, ${HERO_CALLIGRAPHY.ascent}) scale(1,-1)`}
                >
                    {/* 审查-字形修复：填充与描边分离——
                        描边层逐 contour（承载书写 dash 动画，fill:none）；
                        填充层整字合并 path + evenodd（内孔镂空，修复「想」字留白被填死） */}
                    <path
                        d={char.paths.join(' ')}
                        fillRule="evenodd"
                        className={char.accent ? 'ink-calli-fill-path is-accent' : 'ink-calli-fill-path'}
                        style={{ '--ink-calli-fill-delay': `${fillDelay}s` } as CSSProperties}
                    />
                    {char.paths.map((d, contourIndex) => (
                        <path
                            key={contourIndex}
                            d={d}
                            pathLength={1}
                            className={char.accent ? 'ink-calli-path is-accent' : 'ink-calli-path'}
                            style={{
                                '--ink-calli-delay': `${calligraphyStrokeDelay(charIndex, contourIndex)}s`,
                                '--ink-calli-fill-delay': `${fillDelay}s`,
                            } as CSSProperties}
                        />
                    ))}
                </g>
            ))}
        </svg>
    );
}

export default InkCalligraphyTitle;
