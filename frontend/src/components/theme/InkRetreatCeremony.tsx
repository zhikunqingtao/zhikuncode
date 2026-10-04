/**
 * InkRetreatCeremony — 波次3② 闭关模式开/关仪式（ink 双模式全档）
 *
 * 「挂匾 / 摘匾」：inkHavocFx.retreat 翻转瞬间，一块「闭关」匾（书法体 + 朱砂小印）
 * 自顶部落下定格 → 900ms 后淡出（装饰随 ink-retreat class 同步退场）；
 * 摘匾（retreat→false）则匾上升淡出，装饰回归。
 *
 * 挂载于 App（与 InkHavocFxLayer 并列），订阅 retreat 变化自播自收：
 * 初次挂载不播（仅记录基态）；离开 ink 主题不播。
 * 动画时长：motion-full 落匾 1600ms / 摘匾 1000ms；motion-reduced/off 与
 * prefers-reduced-motion 简化为 200ms 淡入淡出（CSS 门控块收口，
 * 组件按 motion 档缩短驻留定时器，保证简化动画后匾不滞留）。
 * 动画只动 transform/opacity（性能红线）。
 */

import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { defaultInkHavocFx, useConfigStore } from '@/store/configStore';

/** ink 双模式集合（门控判断用，同 InkHavocFxLayer） */
const INK_MODES = new Set(['ink-havoc', 'ink-havoc-night']);

/** 匾驻留时长：落匾 1600ms（落 400 + 定格 900 + 淡出 300）/ 摘匾 1000ms；
    简化档（reduced/off）200ms 淡入淡出 + 短驻留，合计 500ms */
const DWELL_MS = {
    full: { enter: 1600, exit: 1000 },
    simple: { enter: 500, exit: 400 },
} as const;

type Phase = 'enter' | 'exit' | null;

export function InkRetreatCeremony() {
    const theme = useConfigStore(s => s.theme);
    const fx = theme.inkHavocFx ?? defaultInkHavocFx();
    const isInk = INK_MODES.has(theme.mode);
    const retreat = isInk && fx.retreat;

    const [phase, setPhase] = useState<Phase>(null);
    /** 基态：初次挂载仅记录当前 retreat，不播仪式（刷新页面不无故挂匾） */
    const prevRef = useRef<boolean | null>(null);
    const timerRef = useRef<number | undefined>(undefined);
    /** 审查#8修复：motion 以 ref 读取最新值，不进 effect 依赖——
        旧实现依赖 fx.motion，动效档切换时 cleanup 清掉驻留定时器，
        新 effect 又因 retreat 未变提前返回，phase 无人清除致匾永驻（已复现） */
    const motionRef = useRef(fx.motion);
    motionRef.current = fx.motion;

    useEffect(() => {
        const prev = prevRef.current;
        prevRef.current = retreat;
        if (prev === null || prev === retreat) return;  // 首挂载 / 无变化不播
        const next: Phase = retreat ? 'enter' : 'exit';
        setPhase(next);
        // 简化档缩短驻留：CSS 动画仅 200ms 淡入淡出，匾不滞留（读翻转瞬间的 motion）
        const dwell = motionRef.current === 'full' ? DWELL_MS.full[next] : DWELL_MS.simple[next];
        window.clearTimeout(timerRef.current);
        timerRef.current = window.setTimeout(() => {
            setPhase(null);
            timerRef.current = undefined;
        }, dwell);
    }, [retreat]);

    /* 卸载兜底清定时器（仅真卸载时执行；日常翻转由下次 clearTimeout 接管） */
    useEffect(() => () => {
        window.clearTimeout(timerRef.current);
        timerRef.current = undefined;
    }, []);

    if (!phase || !isInk) return null;

    return createPortal(
        <div className={`ink-retreat-plaque is-${phase}`} role="status" aria-live="polite">
            <span className="ink-retreat-plaque-text">闭关</span>
            <span className="ink-retreat-plaque-seal" aria-hidden="true">静</span>
        </div>,
        document.body,
    );
}

export default InkRetreatCeremony;
