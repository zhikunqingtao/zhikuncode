/**
 * InkShadowPuppet — 波次3① 皮影镂空 Hero（浓郁档 · 空状态欢迎区）
 *
 * 「灯影戏」结构：空状态 Hero 标题侧后方（右下）立一尊皮影戏镂空孙悟空挥棒剪影，
 * 衬一团暖金背光——皮影在幕布上活了。
 *   背光层 .ink-shadow-backlight：radial-gradient 暖金光晕（浅档鎏金→朱砂 /
 *     深档鎏金→鲜紫，皆收 60%/50% 透明），独立缓慢呼吸（scale 1→1.06，8s）；
 *     桌面细指针 + motion-full 时随鼠标 ±10px lerp 轻移（复用波次2③ 视差纯函数）。
 *   皮影层 .ink-shadow-figure：shadow-puppet-wukong.webp（黑形镂空 alpha）作
 *     mask-image，fill 墨色（浅档 #2E2822 88% / 深档 #14100C 92%）；
 *     drop-shadow(0 0 18px rgba(224,169,46,.35)) 灯影边光；translateY ±4px 8s 自浮动。
 *
 * 门控：仅 ink 双模式 + cinematic（浓郁档）渲染；calm 档/其他主题/闭关模式返回 null。
 * 动画仅 motion-full + prefers-reduced-motion:no-preference 播放（CSS 门控块收口），
 * motion-reduced/off 与系统降级静态显示；<768px 精简装饰层惯例隐藏。
 */

import { useEffect, useRef } from 'react';
import { defaultInkHavocFx, useConfigStore } from '@/store/configStore';

/* ---- 指针视差纯函数（内联自原 cloudParallax 模块：背景视差层已按产品决策移除，
   仅皮影背光保留此能力；函数语义不变） ---- */

/** 指针位置 → 视差目标偏移（px）：相对视口中心归一化 [-1,1] × 振幅 */
function cloudParallaxTarget(
    clientX: number, clientY: number, w: number, h: number, ampX: number, ampY: number,
) {
    return { x: ((clientX / w) * 2 - 1) * ampX, y: ((clientY / h) * 2 - 1) * ampY };
}

/** 线性插值单步 */
function lerpStep(current: number, target: number, factor: number) {
    return current + (target - current) * factor;
}

/** 收敛判定：两轴残差均小于 epsilon 即停帧 */
function isSettled(
    current: { x: number; y: number }, target: { x: number; y: number }, epsilon: number,
) {
    return Math.abs(current.x - target.x) < epsilon && Math.abs(current.y - target.y) < epsilon;
}

/** 是否可挂指针视差：细指针设备 + 系统无 reduced-motion 偏好 */
function shouldAttachCloudParallax() {
    return window.matchMedia('(pointer: fine)').matches
        && !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** ink 双模式集合（门控判断用，同 InkHavocFxLayer） */
const INK_MODES = new Set(['ink-havoc', 'ink-havoc-night']);

/** 背光视差振幅（px）：克制 ±10，主运动跟随鼠标 */
const BACKLIGHT_AMP = { ampX: 10, ampY: 10 } as const;
/** lerp 缓动系数与收敛阈值（与波次2③ 云纹视差同手感） */
const LERP_FACTOR = 0.06;
const SETTLE_EPSILON = 0.08;

/** 背光鼠标视差 hook：enabled 时挂 pointermove，rAF lerp 写 transform；收敛停帧 */
function useBacklightParallax(enabled: boolean) {
    const backlightRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        if (!enabled || !shouldAttachCloudParallax()) return;

        const current = { x: 0, y: 0 };
        const target = { x: 0, y: 0 };
        let raf = 0;
        let running = false;

        const tick = () => {
            current.x = lerpStep(current.x, target.x, LERP_FACTOR);
            current.y = lerpStep(current.y, target.y, LERP_FACTOR);
            backlightRef.current?.style.setProperty(
                'transform',
                `translate3d(${current.x.toFixed(2)}px, ${current.y.toFixed(2)}px, 0)`,
            );
            if (isSettled(current, target, SETTLE_EPSILON)) {
                running = false;
            } else {
                raf = requestAnimationFrame(tick);
            }
        };

        const onPointerMove = (e: PointerEvent) => {
            const next = cloudParallaxTarget(
                e.clientX, e.clientY, window.innerWidth, window.innerHeight,
                BACKLIGHT_AMP.ampX, BACKLIGHT_AMP.ampY,
            );
            target.x = next.x;
            target.y = next.y;
            if (!running) {
                running = true;
                raf = requestAnimationFrame(tick);
            }
        };

        window.addEventListener('pointermove', onPointerMove, { passive: true });
        return () => {
            window.removeEventListener('pointermove', onPointerMove);
            cancelAnimationFrame(raf);
            running = false;
        };
    }, [enabled]);

    return backlightRef;
}

export function InkShadowPuppet() {
    const theme = useConfigStore(s => s.theme);
    // normalizeTheme 保证持久化后恒有值；未持久化前的瞬态用默认值兜底（同 InkHavocFxLayer）
    const fx = theme.inkHavocFx ?? defaultInkHavocFx();
    // 浓郁档 + 非闭关才立皮影；calm 档 Hero 保持现有简洁
    const enabled = INK_MODES.has(theme.mode) && fx.cinematic && !fx.retreat;
    // 背光视差再叠加 motion-full 门控（reduced/off 仅 CSS 呼吸/静止）
    const backlightRef = useBacklightParallax(enabled && fx.motion === 'full');

    if (!enabled) return null;

    return (
        <div className="ink-shadow-puppet" aria-hidden="true">
            {/* 背光层：外层承载 JS 视差 transform，内层承载 CSS 呼吸动画
                （分离避免 animation 覆盖 inline transform，同波次2③ 云纹双层结构） */}
            <div ref={backlightRef} className="ink-shadow-backlight">
                <div className="ink-shadow-backlight-inner" />
            </div>
            {/* 皮影层：镂空剪影 mask 染墨色，灯影边光 + 自浮动由 CSS 收口 */}
            <div className="ink-shadow-figure" />
        </div>
    );
}

export default InkShadowPuppet;
