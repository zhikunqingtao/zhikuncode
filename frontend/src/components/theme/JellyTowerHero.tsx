/**
 * JellyTowerHero — 果冻塔 Hero（空状态上桌秀 · 纯 SVG+CSS 零图片）
 *
 * 移植自 果冻主题皮肤-demo.html v4 §⑪ 完整版：
 *   金箔垫盘 / 酒红镜面慕斯（glacage 高光带 + 淋面滴落 + 高光 parallax 微层）/
 *   三颗马卡龙（玫瑰·琥珀·开心果，裙边 + 夹层 + 高光，叠成小塔）/
 *   顶部大樱桃 + 露珠滑落 / 两片薄荷叶 / 金箔碎点 ×6 / 射灯光束 / 塔周光晕 /
 *   衬线欢迎语（今日特供 · 法式镜面果冻塔）。
 *
 * 行为（jellySpring 驱动，只写 transform）：
 *   heroEnter 入场编排 —— 落塔 duang（sy0.72 压扁 · wobble 8° 久颤）→ 350ms 后 lit
 *   点亮（光束/光晕/金箔/薄荷叶由 CSS transition 承接）；
 *   待机呼吸 breathMod(5200, ±0.035 明显果冻颤)；
 *   马卡龙随机错动 pop（4.5–8.5s 一只）；鼠标高光 parallax（±8px lerp，收敛停帧）。
 *
 * 门控：jelly + cinematic 才渲染（calm 档/其他主题返回 null 零变化）；
 * motion-full 才挂永续循环（呼吸/错动/视差）；motion-reduced 仅保留入场反馈；
 * motion-off 与 prefers-reduced-motion 保留静态 lit 形态（CSS media 已静态点亮）。
 */

import { useEffect, useRef } from 'react';
import { defaultJellyFx, useConfigStore } from '@/store/configStore';
import { usePrefersReducedMotion } from '@/hooks/useMediaQuery';
import {
    breathMod,
    engineNow,
    isJellyMotionFull,
    popSpring,
    registerSpring,
    releaseSpring,
    wake,
} from './jellySpring';

export function JellyTowerHero() {
    const theme = useConfigStore((s) => s.theme);
    // normalizeTheme 保证持久化后恒有值；未持久化前的瞬态用默认值兜底（同 JellyFxLayer）
    const fx = theme.jellyFx ?? defaultJellyFx();
    const enabled = theme.mode === 'jelly' && fx.cinematic;
    const reducedMotion = usePrefersReducedMotion();
    const eventsEnabled = enabled && fx.motion !== 'off' && !reducedMotion;
    const motionFull = eventsEnabled && fx.motion === 'full';

    const heroRef = useRef<HTMLElement>(null);
    const towerRef = useRef<HTMLDivElement>(null);
    const hlRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        const hero = heroRef.current;
        const towerEl = towerRef.current;
        if (!enabled || !hero || !towerEl) return;
        if (!eventsEnabled) {
            hero.classList.add('lit');
            return;
        }

        const highlight = hlRef.current;
        const highlightTransform = highlight?.style.transform ?? '';
        const timers: number[] = [];
        const towerSpring = registerSpring(towerEl, { k: 160, c: 11, origin: '50% 100%' });
        const macSprings = Array.from(hero.querySelectorAll<HTMLElement>('.jelly-mac'))
            .map((el) => registerSpring(el, { k: 200, c: 12, origin: '50% 100%' }));

        /* ---------- 入场编排：落塔 duang → lit 点亮 ---------- */
        hero.classList.remove('lit');
        towerSpring.ty.x = -40;
        towerSpring.ty.v = 0;
        towerSpring.sy.x = 0.72;   // 落塔压得更扁更宽（demo 最终版）
        towerSpring.sx.x = 1.22;
        towerSpring.sx.v = 0;
        towerSpring.sy.v = 0;
        towerSpring.ty.t = 0;
        towerSpring.sx.t = 1;
        towerSpring.sy.t = 1;
        towerSpring.wob = { t0: engineNow() + 380, A: 8, lam: 2.2, om: 12 };  // 回弹后久颤
        wake(towerSpring);
        // 动效档 350ms 后点亮（与落塔回弹同步）。
        timers.push(window.setTimeout(() => hero.classList.add('lit'), 350));

        /* ---------- 待机呼吸 + 马卡龙错动 + 高光视差（仅 motion-full 永续循环） ---------- */
        let hlX = 0;
        let hlTarget = 0;
        let hlRaf = 0;
        const hlTick = (): void => {
            hlRaf = 0;
            hlX += (hlTarget - hlX) * 0.08;
            if (highlight) highlight.style.transform = `translateX(${hlX.toFixed(2)}px)`;
            if (Math.abs(hlTarget - hlX) > 0.05) hlRaf = requestAnimationFrame(hlTick);
        };
        const onPointerMove = (e: PointerEvent): void => {
            if (!isJellyMotionFull()) return;
            hlTarget = ((e.clientX / window.innerWidth) * 2 - 1) * 8;
            if (!hlRaf) hlRaf = requestAnimationFrame(hlTick);
        };

        if (motionFull) {
            // 待机明显果冻颤：breathMod 永续（rAF 随塔呼吸不停）
            towerSpring.mod = towerSpring.modFn = breathMod(5200, 1.0, 0.035, 1.0, -0.030, 0);
            wake(towerSpring);
            // 马卡龙待机错动：随机一只轻压弹回（4.5–8.5s）
            const scheduleMacJiggle = (): void => {
                timers.push(window.setTimeout(() => {
                    if (!isJellyMotionFull() || !macSprings.length) return;
                    popSpring(macSprings[Math.floor(Math.random() * macSprings.length)], 0.42);
                    scheduleMacJiggle();
                }, 4500 + Math.random() * 4000));
            };
            scheduleMacJiggle();
            // 高光 parallax：鼠标横向移动 ±8px，lerp 跟随（细指针设备；无 matchMedia 环境跳过）
            if (typeof window.matchMedia === 'function' && window.matchMedia('(pointer: fine)').matches) {
                window.addEventListener('pointermove', onPointerMove, { passive: true });
            }
        }

        return () => {
            timers.forEach((id) => window.clearTimeout(id));
            window.removeEventListener('pointermove', onPointerMove);
            if (hlRaf) cancelAnimationFrame(hlRaf);
            if (highlight) highlight.style.transform = highlightTransform;
            releaseSpring(towerSpring);
            macSprings.forEach(releaseSpring);
        };
    }, [enabled, eventsEnabled, motionFull]);

    if (!enabled) return null;

    return (
        <section ref={heroRef} className="jelly-hero" aria-hidden="true">
            <div className="jelly-hero-stage">
                {/* 射灯光束（暖金圆锥，自顶部中央打下） */}
                <div className="jelly-hero-beam" />
                {/* 塔周光晕 */}
                <div className="jelly-hero-glow" />
                {/* 塔容器（spring 形变目标） */}
                <div ref={towerRef} className="jelly-tower">
                    <div className="jelly-hero-shadow" />
                    {/* 金箔垫盘 */}
                    <div className="jelly-tower-plate" />
                    {/* 酒红镜面慕斯（底层主角） */}
                    <div className="jelly-tower-mousse">
                        <div ref={hlRef} className="jelly-mousse-hl" />
                        <div className="jelly-mousse-drip"><i /><i /><i /><i /><i /></div>
                    </div>
                    {/* 薄荷叶（斜倚慕斯左上） */}
                    <svg className="jelly-tower-leaf jelly-leaf-1" width="34" height="22" viewBox="0 0 40 26" aria-hidden="true">
                        <path d="M4 22 C 10 8, 26 2, 38 6 C 34 18, 18 26, 4 22 Z" fill="#7A9B5A" stroke="#5E7A44" strokeWidth="1" />
                        <path d="M7 21 C 17 16, 27 11, 36 7" fill="none" stroke="#5E7A44" strokeWidth=".8" opacity=".7" />
                    </svg>
                    {/* 三颗马卡龙（叠成小塔，错开微斜；slot 承载定位/微斜，spring 形变落在内层本体不与 CSS transform 打架） */}
                    <div className="jelly-mac-slot jelly-slot-rose">
                        <div className="jelly-mac jelly-mac-rose"><span className="jelly-mac-hl" /></div>
                    </div>
                    <div className="jelly-mac-slot jelly-slot-amber">
                        <div className="jelly-mac jelly-mac-amber"><span className="jelly-mac-hl" /></div>
                    </div>
                    <div className="jelly-mac-slot jelly-slot-pist">
                        <div className="jelly-mac jelly-mac-pist"><span className="jelly-mac-hl" /></div>
                    </div>
                    {/* 顶部大樱桃（镜面 radial 高光 + 果柄 + 底面影） */}
                    <svg className="jelly-tower-cherry" width="40" height="47" viewBox="0 0 34 40" aria-hidden="true">
                        <defs>
                            <radialGradient id="jelly-cherry-tower-grad" cx="38%" cy="30%" r="78%">
                                <stop offset="0%" stopColor="#C23A5E" />
                                <stop offset="45%" stopColor="#8E1F3C" />
                                <stop offset="100%" stopColor="#5E0F27" />
                            </radialGradient>
                        </defs>
                        <ellipse cx="16" cy="37.5" rx="8" ry="1.7" fill="rgba(60,10,25,.22)" />
                        <path d="M17 18 C 17 9, 22 4, 29 2.5" fill="none" stroke="#7A5230" strokeWidth="1.7" strokeLinecap="round" />
                        <circle cx="15.5" cy="27" r="9.5" fill="url(#jelly-cherry-tower-grad)" />
                        <ellipse cx="11.8" cy="22.3" rx="3.2" ry="1.7" fill="#fff" opacity=".9" transform="rotate(-25 11.8 22.3)" />
                        <circle cx="13.2" cy="20.6" r="1" fill="#fff" />
                    </svg>
                    {/* 露珠（沿樱桃滑落） */}
                    <span className="jelly-dew" aria-hidden="true" />
                    {/* 薄荷叶（垫盘右下） */}
                    <svg className="jelly-tower-leaf jelly-leaf-2" width="30" height="20" viewBox="0 0 40 26" aria-hidden="true">
                        <path d="M4 22 C 10 8, 26 2, 38 6 C 34 18, 18 26, 4 22 Z" fill="#7A9B5A" stroke="#5E7A44" strokeWidth="1" />
                        <path d="M7 21 C 17 16, 27 11, 36 7" fill="none" stroke="#5E7A44" strokeWidth=".8" opacity=".7" />
                    </svg>
                    {/* 金箔碎点 ×6（垫盘周围错落升起） */}
                    <i className="jelly-tower-gd jelly-gd-1" /><i className="jelly-tower-gd jelly-gd-2" />
                    <i className="jelly-tower-gd jelly-gd-3" /><i className="jelly-tower-gd jelly-gd-4" />
                    <i className="jelly-tower-gd jelly-gd-5" /><i className="jelly-tower-gd jelly-gd-6" />
                </div>
            </div>
            {/* Hero 欢迎语（衬线 · 法式优雅） */}
            <p className="jelly-hero-cap">今日特供 · <b>法式镜面果冻塔</b></p>
            <p className="jelly-hero-sub">镜面慕斯 × 马卡龙 × 樱桃露珠 · 一场甜品上桌秀</p>
        </section>
    );
}

export default JellyTowerHero;
