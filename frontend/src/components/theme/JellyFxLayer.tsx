/**
 * JellyFxLayer — 果冻主题浓郁档装饰层 + 真实界面 Q 弹行为桥
 *
 * A. 装饰层（移植自 果冻主题皮肤-demo.html v4 §⑦⑪）：
 *    奶油色晕染 ×2（.jelly-wash）+ 漂浮金箔碎点 ×2（.jelly-gdot）
 *    + 背景果冻滴 ×5（.jelly-blob，deco 扩展：半透明果冻椭圆 + 顶部高光点）；
 *    呼吸/漂浮用 jellySpring 的 breathMod/floatMod 永续调制，
 *    果冻滴两两靠近时「啵」地弹开（popSpring）；fixed overlay z-30、pointer-events:none。
 *
 * B. Q 弹行为挂接（真实界面元素，全部仅浓郁档）：
 *    消息卡片 / 会话卡片 hoverSpring 微弹（lift -1.5 / hx 1.01 / hy 0.99 收敛力度）；
 *    输入框容器 focus 膨胀（textarea focus/blur → 容器 sx1.015 / sy1.03 缓慢 spring）。
 *    元素用真实类名选择器，MutationObserver（180ms 去抖）同步卡片/输入框生命周期；
 *    每个绑定独立持有 spring 与 AbortController，离场或切档只释放本桥接持有的资源。
 *
 * 门控：mode==='jelly' && jellyFx.cinematic 才挂载（calm 档/其他主题返回 null 零变化）；
 * 装饰循环与视差再叠 motion-full（motion-reduced 关循环保留事件反馈，off 全静止）；
 * prefers-reduced-motion 时装饰层由 CSS 隐藏、spring 全部失活（静态）。
 */

import { useEffect, useRef, type RefObject } from 'react';
import { defaultJellyFx, useConfigStore } from '@/store/configStore';
import { usePrefersReducedMotion } from '@/hooks/useMediaQuery';
import {
    breathMod,
    floatMod,
    getSpring,
    hoverSpring,
    popSpring,
    registerSpring,
    releaseSpring,
    springsActive,
    wake,
    type HoverOptions,
    type SpringState,
} from './jellySpring';

/* ---------- 真实界面元素选择器（命名以仓库真实类名/aria-label 为准） ---------- */

/** 卡片（hover 微弹）：助手回合卡 / 用户气泡 / 侧栏会话卡 */
const HOVER_SELECTORS = [
    'main .turn-card > section',
    'main .user-message > .bg-surfacev2',
    '.app-sidebar .session-card',
];

/** 卡片 hover 收敛力度（demo 2/3 强度：lift -1.5 / hx 1.01 / hy 0.99） */
const CARD_HOVER: HoverOptions = { k: 200, c: 13, lift: -1.5, hx: 1.01, hy: 0.99 };

/* ---------- A. 装饰层 spring（呼吸 / 漂浮 / 碰撞弹开） ---------- */

function useJellyDecorSprings(enabled: boolean, motionFull: boolean, layerRef: RefObject<HTMLDivElement>): void {
    useEffect(() => {
        const layer = layerRef.current;
        if (!enabled || !motionFull || !layer) return;
        const springs: SpringState[] = [];

        // 奶油晕染 ×2：极缓呼吸（strawberry / amber 各自周期错开）
        layer.querySelectorAll<HTMLElement>('.jelly-wash').forEach((el, i) => {
            const s = registerSpring(el, {
                k: 100,
                c: 11,
                origin: '50% 50%',
                mod: breathMod(9000 + i * 2500, 1.0, 0.035, 1.0, 0.035, i * 2.1),
            });
            s.mod = s.modFn;
            wake(s);
            springs.push(s);
        });

        // 金箔碎点（小幅快漂）+ 背景果冻滴（大幅慢漂）：floatMod 相位/周期随序号错开
        const floaters = Array.from(layer.querySelectorAll<HTMLElement>('.jelly-gdot, .jelly-blob'));
        const blobs: HTMLElement[] = [];
        floaters.forEach((el, i) => {
            const isBlob = el.classList.contains('jelly-blob');
            if (isBlob) blobs.push(el);
            const s = registerSpring(el, {
                k: isBlob ? 60 : 120,
                c: 12,
                origin: '50% 50%',
                mod: floatMod(i, isBlob ? 13000 : 7000, isBlob ? 9 : 5),
            });
            s.mod = s.modFn;
            wake(s);
            springs.push(s);
        });

        // 碰撞「啵」弹开：果冻滴中心距 < 半径和 + 60px 即双双轻压回弹
        const blobStates = blobs.map((el) => getSpring(el));
        const collisionTimer = window.setInterval(() => {
            for (let i = 0; i < blobs.length; i++) {
                for (let j = i + 1; j < blobs.length; j++) {
                    const a = blobs[i].getBoundingClientRect();
                    const b = blobs[j].getBoundingClientRect();
                    const dx = a.left + a.width / 2 - (b.left + b.width / 2);
                    const dy = a.top + a.height / 2 - (b.top + b.height / 2);
                    const reach = (a.width + b.width) / 2 + 60;
                    const sa = blobStates[i];
                    const sb = blobStates[j];
                    if (dx * dx + dy * dy < reach * reach && sa && sb) {
                        popSpring(sa, 0.7);
                        popSpring(sb, 0.7);
                    }
                }
            }
        }, 900);

        return () => {
            window.clearInterval(collisionTimer);
            springs.forEach(releaseSpring);
        };
    }, [enabled, motionFull, layerRef]);
}

/* ---------- B. 真实界面 Q 弹行为桥 ---------- */

interface SpringBinding {
    spring: SpringState;
    controller: AbortController;
    textarea?: HTMLTextAreaElement;
}

function useJellySpringBridge(enabled: boolean): void {
    useEffect(() => {
        if (!enabled) return;
        const bindings = new Map<HTMLElement, SpringBinding>();
        const hoverSelector = HOVER_SELECTORS.join(', ');
        const inputSelector = '.chat-composer-surface';

        const releaseBinding = (el: HTMLElement, binding: SpringBinding): void => {
            binding.controller.abort();
            releaseSpring(binding.spring);
            bindings.delete(el);
        };
        const bindCardHover = (el: HTMLElement): void => {
            if (bindings.has(el)) return;
            const controller = new AbortController();
            const spring = hoverSpring(el, { ...CARD_HOVER, signal: controller.signal });
            bindings.set(el, { spring, controller });
        };
        const bindInputFocus = (el: HTMLElement): void => {
            if (bindings.has(el)) return;
            const textarea = el.querySelector('textarea');
            if (!textarea) return;
            const controller = new AbortController();
            const { signal } = controller;
            const spring = registerSpring(el, { k: 130, c: 12, origin: '50% 100%' });
            bindings.set(el, { spring, controller, textarea });
            const onFocus = (): void => {
                if (!springsActive()) return;
                spring.sx.t = 1.015;
                spring.sy.t = 1.03;
                wake(spring);
            };
            textarea.addEventListener('focus', onFocus, { signal });
            textarea.addEventListener('blur', () => {
                if (!springsActive()) return;
                spring.sx.t = 1;
                spring.sy.t = 1;
                wake(spring);
            }, { signal });
            // 自动聚焦可能早于扫描；切回动效档时也同步当前焦点。
            if (document.activeElement === textarea) onFocus();
        };

        const scan = (): void => {
            for (const [el, binding] of bindings) {
                const stillMatches = binding.textarea
                    ? el.matches(inputSelector) && el.querySelector('textarea') === binding.textarea
                    : el.matches(hoverSelector);
                if (!el.isConnected || !stillMatches) releaseBinding(el, binding);
            }
            document.querySelectorAll<HTMLElement>(hoverSelector).forEach(bindCardHover);
            document.querySelectorAll<HTMLElement>(inputSelector).forEach(bindInputFocus);
        };
        scan();

        // 流式渲染期间合并扫描；同时回收已卸载节点及被替换 textarea 的旧监听。
        let scanTimer = 0;
        const observer = new MutationObserver(() => {
            if (scanTimer) return;
            scanTimer = window.setTimeout(() => {
                scanTimer = 0;
                scan();
            }, 180);
        });
        observer.observe(document.body, { childList: true, subtree: true });

        return () => {
            observer.disconnect();
            if (scanTimer) window.clearTimeout(scanTimer);
            bindings.forEach((binding, el) => releaseBinding(el, binding));
        };
    }, [enabled]);
}

/* ---------- 组件 ---------- */

export function JellyFxLayer() {
    const theme = useConfigStore((s) => s.theme);
    // normalizeTheme 保证持久化后恒有值；未持久化前的瞬态用默认值兜底（同 InkHavocFxLayer）
    const fx = theme.jellyFx ?? defaultJellyFx();
    // 门控：jelly + 浓郁档（jelly 无 retreat 子状态）；motion-full 再决定装饰循环是否开跑
    const enabled = theme.mode === 'jelly' && fx.cinematic;
    const reducedMotion = usePrefersReducedMotion();
    const eventsEnabled = enabled && fx.motion !== 'off' && !reducedMotion;
    const motionFull = eventsEnabled && fx.motion === 'full';
    const layerRef = useRef<HTMLDivElement>(null);

    useJellyDecorSprings(enabled, motionFull, layerRef);
    useJellySpringBridge(eventsEnabled);

    if (!enabled) return null;

    return (
        <div ref={layerRef} className="jelly-deco" aria-hidden="true">
            {/* 奶油色晕染 ×2（草莓粉 / 琥珀暖光，极低浓度） */}
            <div className="jelly-wash jelly-wash-1" />
            <div className="jelly-wash jelly-wash-2" />
            {/* 漂浮金箔碎点 ×2 */}
            <div className="jelly-gdot jelly-gdot-1" />
            <div className="jelly-gdot jelly-gdot-2" />
            {/* 背景果冻滴 ×5（半透明果冻椭圆 + 顶部高光点，缓慢漂浮 · 靠近「啵」弹开） */}
            <div className="jelly-blob jelly-blob-1" />
            <div className="jelly-blob jelly-blob-2" />
            <div className="jelly-blob jelly-blob-3" />
            <div className="jelly-blob jelly-blob-4" />
            <div className="jelly-blob jelly-blob-5" />
        </div>
    );
}

export default JellyFxLayer;
