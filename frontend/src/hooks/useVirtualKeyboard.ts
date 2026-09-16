/**
 * useVirtualKeyboard — 移动端虚拟键盘感知 Hook
 * SPEC: §8.8.3
 *
 * 使用 VisualViewport API 检测虚拟键盘弹出/收起，
 * 通过 CSS 变量 --keyboard-height / --viewport-height 驱动布局重排。
 * 统一处理 iOS Safari 与 Android Chrome 的差异。
 */

import { useState, useEffect, useRef } from 'react';

/** 键盘配置常量 — 对齐 §8.8.3 KEYBOARD_CONFIG */
const KEYBOARD_CONFIG = {
    /** 键盘弹出判定阈值 — visualViewport.height 减少超过此值视为键盘弹出 */
    keyboardThreshold: 150,
    /** 键盘弹出后滚动延迟 — 等待布局稳定后再滚动到输入框 */
    scrollIntoViewDelay: 300,
    /** 输入框底部安全距离 */
    inputBottomPadding: 8,
} as const;

export interface VirtualKeyboardState {
    keyboardHeight: number;
    isKeyboardVisible: boolean;
}

/**
 * 检测虚拟键盘状态，自动设置 CSS 变量。
 *
 * @returns { keyboardHeight, isKeyboardVisible }
 */
export function useVirtualKeyboard(enabled = true): VirtualKeyboardState {
    const [keyboardHeight, setKeyboardHeight] = useState(0);
    const [isKeyboardVisible, setIsKeyboardVisible] = useState(false);
    useEffect(() => {
        if (!enabled) return;
        const vv = window.visualViewport;
        let baselineHeight = vv?.height ?? window.innerHeight;
        let layoutWidth = window.innerWidth;
        let frame = 0;

        const update = () => {
            // Pinch zoom must remain browser-controlled, not resize the app as a keyboard.
            if (vv && Math.abs(vv.scale - 1) > 0.01) return;
            const height = vv?.height ?? window.innerHeight;
            const focused = document.activeElement;
            const editing = focused instanceof HTMLElement
                && (focused.matches('textarea, input:not([type="button"]):not([type="checkbox"]):not([type="radio"]):not([type="submit"])')
                    || focused.isContentEditable === true);
            // Rotation and unfocused toolbar changes establish a new baseline.
            if (!editing || layoutWidth !== window.innerWidth) baselineHeight = height;
            layoutWidth = window.innerWidth;
            baselineHeight = Math.max(baselineHeight, height);
            const delta = baselineHeight - height;
            const visible = editing && delta > KEYBOARD_CONFIG.keyboardThreshold;
            setKeyboardHeight(visible ? delta : 0);
            setIsKeyboardVisible(visible);
            const style = document.documentElement.style;
            style.setProperty('--keyboard-height', `${visible ? delta : 0}px`);
            style.setProperty('--viewport-height', `${height}px`);
            style.setProperty('--viewport-offset-top', `${vv?.offsetTop ?? 0}px`);
        };
        const scheduleUpdate = () => {
            cancelAnimationFrame(frame);
            frame = requestAnimationFrame(update);
        };
        update();
        vv?.addEventListener('resize', scheduleUpdate);
        vv?.addEventListener('scroll', scheduleUpdate);
        window.addEventListener('resize', scheduleUpdate);
        document.addEventListener('focusin', scheduleUpdate);
        document.addEventListener('focusout', scheduleUpdate);
        return () => {
            cancelAnimationFrame(frame);
            vv?.removeEventListener('resize', scheduleUpdate);
            vv?.removeEventListener('scroll', scheduleUpdate);
            window.removeEventListener('resize', scheduleUpdate);
            document.removeEventListener('focusin', scheduleUpdate);
            document.removeEventListener('focusout', scheduleUpdate);
            const style = document.documentElement.style;
            style.removeProperty('--keyboard-height');
            style.removeProperty('--viewport-height');
            style.removeProperty('--viewport-offset-top');
            setKeyboardHeight(0);
            setIsKeyboardVisible(false);
        };
    }, [enabled]);

    return { keyboardHeight, isKeyboardVisible };
}

/**
 * 键盘弹出后自动将输入框滚动到可见区域。
 * SPEC: §8.8.3 useScrollInputIntoView
 */
export function useScrollInputIntoView(
    inputRef: React.RefObject<HTMLTextAreaElement | null>,
    isKeyboardVisible: boolean
) {
    useEffect(() => {
        if (!isKeyboardVisible || !inputRef.current) return;

        const timer = setTimeout(() => {
            inputRef.current?.scrollIntoView({
                behavior: 'smooth',
                block: 'nearest',
            });
        }, KEYBOARD_CONFIG.scrollIntoViewDelay);

        return () => clearTimeout(timer);
    }, [isKeyboardVisible, inputRef]);
}

/** 帧调度：浏览器用 rAF 与绘制同步；jsdom 等无 rAF 环境退化为 ~16ms 定时器 */
function scheduleFrame(cb: () => void): () => void {
    if (typeof requestAnimationFrame === 'function') {
        const id = requestAnimationFrame(cb);
        return () => cancelAnimationFrame(id);
    }
    const id = setTimeout(cb, 16);
    return () => clearTimeout(id);
}

/** 布局沉降循环配置 —— 键盘弹起后 CSS 过渡（0.25s）+ Virtuoso 重测均为异步多帧 */
const SETTLE_CONFIG = {
    /** 距底 ≤1px 连续稳定帧数，达到即视为布局已沉降 */
    stableFrames: 5,
    /** 沉降循环兜底时长（须覆盖 100ms 防抖 + 250ms padding 过渡 + Virtuoso 重测） */
    maxDurationMs: 1200,
} as const;

/**
 * 键盘弹出时的消息列表滚动补偿。
 * SPEC: §8.8.3 useKeyboardScrollCompensation
 *
 * 一次性 delta 补偿：仅在底部附近（isAtBottom，默认 true，向后兼容）把
 * 「键盘增高量 delta」追加到 scrollTop —— 容器可视高度被键盘压缩后仍锚定
 * 真实底部；用户上翻阅读时不补偿，scrollTop 不动即保留其顶部锚点。
 * prevKeyboardHeight 无条件跟踪，避免 atBottom 翻转后跨缺口补偿。
 *
 * 布局沉降持续锚底（P2b-2b 修复）：键盘避让的 padding-bottom CSS 过渡
 * （0.25s）与 Virtuoso 行高/总高重测在键盘弹起后异步多帧进行，一次性补偿/
 * App 桥滚底以「当前几何」落地后，clientHeight 继续收缩（实测 212px）会
 * 残留距底缺口（实测 276px）。故启动短时帧循环，每帧把 scrollTop 重新断言
 * 到真实底部，直至布局稳定（≤1px 连续 5 帧）或超时兜底。
 *
 * 启动时机：① 键盘开启瞬间（0→>0）无条件启动 —— 实测（P2b-2b 探针）
 * Virtuoso 的 atBottomStateChange(true) 在过渡窗口内从未送达 React
 * （重测/scrollToIndex 期间被抑制），以 atBottom 翻转为启动条件会导致
 * 循环永不接管；App 桥「弹起滚底」契约保证开启瞬间列表正被送往底部，
 * 本循环接续锚底、与之正交。② 键盘开启期间 atBottom=true（如「回到最新」
 * 回到底部）——既有路径。③ 键盘开启期间用户上翻阅读（atBottom=false）
 * 不启动，不打扰阅读位置。
 *
 * 不与用户争抢：用户上滚手势（wheel 上滚 / touchmove）立即停让；锚底确立
 * （循环观察到 atBottom=true）后 atBottom 翻 false（大纲点选等程序化跳
 * 转）亦停让——锚底确立前的 atBottom=false 不作停让依据（开启瞬间该值
 * 本就为 false 且翻转不可靠，见上）。键盘收起、组件卸载时清理定时器与
 * 监听。仅移动挂载本 hook，桌面零副作用。
 */
export function useKeyboardScrollCompensation(
    listRef: React.RefObject<HTMLDivElement | null>,
    keyboardHeight: number,
    isAtBottom = true
) {
    const prevKeyboardHeight = useRef(0);
    // atBottom 最新值镜像：帧循环异步读取，避免闭包捕获过期值
    const isAtBottomRef = useRef(isAtBottom);
    // 沉降循环停止句柄（null = 未运行）；手动管理以便跨 effect 重跑存活
    const stopSettleRef = useRef<(() => void) | null>(null);
    // 锚底确立标记：循环启动后观察到 atBottom=true 即置位；此后 atBottom
    // 翻 false（大纲点选等程序化跳转）才作为停让信号。确立前的 false
    // 不作依据——开启瞬间 atBottom 本就为 false 且翻转送达不可靠。
    const hasAnchoredRef = useRef(false);

    useEffect(() => {
        isAtBottomRef.current = isAtBottom;
    }, [isAtBottom]);

    // 卸载兜底清理（帧循环定时器 + 滚动手势监听）
    useEffect(() => () => stopSettleRef.current?.(), []);

    useEffect(() => {
        const scroller = listRef.current;
        const delta = keyboardHeight - prevKeyboardHeight.current;
        const opening = prevKeyboardHeight.current === 0 && keyboardHeight > 0;
        prevKeyboardHeight.current = keyboardHeight;

        // 键盘收起：停止沉降循环（delta 补偿本就只追增高，收起不回拉）
        if (keyboardHeight <= 0) {
            stopSettleRef.current?.();
            stopSettleRef.current = null;
            return;
        }
        if (!scroller) return;

        // 一次性 delta 补偿（键盘弹起/继续增高瞬间）仅在底部附近追加，
        // 保留既有语义；用户上翻阅读时不补偿
        if (delta > 0 && isAtBottom) scroller.scrollTop += delta;

        // 沉降循环幂等：已在运行则不重复挂监听/定时器
        if (stopSettleRef.current) return;

        // 启动时机：开启瞬间无条件启动（App 桥「弹起滚底」契约接续锚底，
        // 不等 atBottom 翻转——实测其 true 事件在过渡窗口内不送达）；
        // 非开启瞬间仅在底部附近启动（开启期间回到底部的既有路径）；
        // 开启期间上翻阅读不启动，不打扰阅读位置
        if (!opening && !isAtBottom) return;

        let cancelNextFrame: (() => void) | null = null;
        let stableFrames = 0;
        const startedAt = performance.now();
        hasAnchoredRef.current = isAtBottomRef.current;

        const stop = () => {
            cancelNextFrame?.();
            cancelNextFrame = null;
            scroller.removeEventListener('wheel', handleWheel);
            scroller.removeEventListener('touchmove', handleTouchMove);
            stopSettleRef.current = null;
        };
        // 用户上滚手势 → 立即停让（下滚/点按不打断锚底）
        const handleWheel = (e: WheelEvent) => {
            if (e.deltaY < 0) stop();
        };
        const handleTouchMove = () => stop();

        const step = () => {
            // 锚底确立后被程序化跳离（atBottom 翻 false，如大纲点选）→ 停让；
            // 确立前的 false 不停让（开启瞬间 atBottom 翻转不可靠）
            if (isAtBottomRef.current) {
                hasAnchoredRef.current = true;
            } else if (hasAnchoredRef.current) {
                stop();
                return;
            }
            const target = scroller.scrollHeight - scroller.clientHeight;
            const gap = target - scroller.scrollTop;
            if (gap > 1) {
                scroller.scrollTop = target;
                stableFrames = 0;
            } else {
                stableFrames += 1;
            }
            if (stableFrames >= SETTLE_CONFIG.stableFrames
                || performance.now() - startedAt > SETTLE_CONFIG.maxDurationMs) {
                stop();
                return;
            }
            cancelNextFrame = scheduleFrame(step);
        };

        scroller.addEventListener('wheel', handleWheel, { passive: true });
        scroller.addEventListener('touchmove', handleTouchMove, { passive: true });
        stopSettleRef.current = stop;
        cancelNextFrame = scheduleFrame(step);
    }, [keyboardHeight, isAtBottom, listRef]);
}

/**
 * 网络感知优化 — 根据网络状况调整流式更新频率。
 * SPEC: §8.8.5
 */
export function useNetworkAwareConfig(): { streamBatchInterval: number } {
    const [streamBatchInterval, setStreamBatchInterval] = useState(16);

    useEffect(() => {
        const conn = (navigator as unknown as { connection?: { effectiveType: string; addEventListener: (e: string, h: () => void) => void; removeEventListener: (e: string, h: () => void) => void } }).connection;
        if (!conn) return;

        const updateConfig = () => {
            if (conn.effectiveType === '2g' || conn.effectiveType === 'slow-2g') {
                setStreamBatchInterval(100);
            } else if (conn.effectiveType === '3g') {
                setStreamBatchInterval(50);
            } else {
                setStreamBatchInterval(16);
            }
        };

        conn.addEventListener('change', updateConfig);
        updateConfig();
        return () => conn.removeEventListener('change', updateConfig);
    }, []);

    return { streamBatchInterval };
}
