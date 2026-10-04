/**
 * InkSealStamp — 盖印仪式：toast 左侧小朱印（大闹天宫重彩风 · 波次1增强①）
 * 设计来源：黑神话「上香存档」仪式——操作落定要有一枚看得见的印。
 *
 * 门控：仅 theme.mode 为 ink-havoc/ink-havoc-night 且 inkHavocFx.cinematic（浓郁档）
 * 时渲染，其余（含克制档 / 其他主题）返回 null —— calm 档 toast 保持常规形态。
 * 印章白文按通知级别：成=成功 / 警=警告 / 误=错误 / 报=信息。
 *
 * 落定角度 ±3° 每次挂载随机一次（--ink-seal-rotate 内联变量，useMemo 固定，
 * 避免重渲染跳动）；「趋势-加速-落定」三段动画与墨点溅出全部在
 * styles/ink-havoc.css「盖印仪式」区块（仅 motion-full 播放，reduced/off 静态显示）。
 */

import { useMemo } from 'react';
import type { CSSProperties } from 'react';
import { defaultInkHavocFx, useConfigStore } from '@/store/configStore';
import type { NotificationItem } from '@/types';

/** ink 双模式集合（门控判断用，同 InkHavocFxLayer） */
const INK_MODES = new Set(['ink-havoc', 'ink-havoc-night']);

/** 印章白文：按通知级别取字（缺省回「报」） */
const SEAL_GLYPH: Record<NotificationItem['level'], string> = {
    success: '成',
    warning: '警',
    error: '误',
    info: '报',
};

export interface InkSealStampProps {
    level: NotificationItem['level'];
}

export function InkSealStamp({ level }: InkSealStampProps) {
    const theme = useConfigStore(s => s.theme);
    // normalizeTheme 保证持久化后恒有值；未持久化前的瞬态用默认值兜底（同 InkHavocFxLayer）
    const fx = theme.inkHavocFx ?? defaultInkHavocFx();
    const enabled = INK_MODES.has(theme.mode) && fx.cinematic;

    // 落定角度：±3° 随机（每次挂载生成一次后固定）
    const rotate = useMemo(() => `${(Math.random() * 6 - 3).toFixed(2)}deg`, []);

    if (!enabled) return null;

    return (
        <span
            className="ink-seal-stamp"
            data-level={level}
            aria-hidden="true"
            style={{ '--ink-seal-rotate': rotate } as CSSProperties}
        >
            {SEAL_GLYPH[level] ?? '报'}
        </span>
    );
}

export default InkSealStamp;
