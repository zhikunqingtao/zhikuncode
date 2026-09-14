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
    /** 布局调整防抖 — 防止键盘动画过程中频繁重排 */
    resizeDebounceMs: 100,
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
export function useVirtualKeyboard(): VirtualKeyboardState {
    const [keyboardHeight, setKeyboardHeight] = useState(0);
    const [isKeyboardVisible, setIsKeyboardVisible] = useState(false);
    const initialViewportHeight = useRef(0);

    useEffect(() => {
        const vv = window.visualViewport;
        if (!vv) return;

        initialViewportHeight.current = vv.height;
        let debounceTimer: ReturnType<typeof setTimeout>;

        const handleResize = () => {
            clearTimeout(debounceTimer);
            debounceTimer = setTimeout(() => {
                const kbHeight = initialViewportHeight.current - vv.height;
                const isVisible = kbHeight > KEYBOARD_CONFIG.keyboardThreshold;

                setKeyboardHeight(isVisible ? kbHeight : 0);
                setIsKeyboardVisible(isVisible);

                // 设置 CSS 变量 — 供整个应用使用
                document.documentElement.style.setProperty(
                    '--keyboard-height', `${isVisible ? kbHeight : 0}px`
                );
                document.documentElement.style.setProperty(
                    '--viewport-height', `${vv.height}px`
                );
            }, KEYBOARD_CONFIG.resizeDebounceMs);
        };

        vv.addEventListener('resize', handleResize);
        vv.addEventListener('scroll', handleResize);
        return () => {
            vv.removeEventListener('resize', handleResize);
            vv.removeEventListener('scroll', handleResize);
            clearTimeout(debounceTimer);
        };
    }, []);

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
 * isAtBottom（默认 true，向后兼容）：仅在底部附近才把「键盘增高量 delta」
 * 追加到 scrollTop —— 容器可视高度被键盘压缩后仍锚定真实底部；
 * 用户上翻阅读时传 false：scrollTop 不动即保留其顶部锚点，不发生位移。
 * prevKeyboardHeight 无条件跟踪，避免 atBottom 翻转后跨缺口补偿。
 *
 * 布局沉降持续锚底（P2b-2b 修复）：一次性 delta 补偿后，键盘避让的
 * padding-bottom CSS 过渡（0.25s）与 Virtuoso 行高/总高重测仍在异步多帧
 * 进行，scrollTop 不变而 clientHeight/scrollHeight 继续变化，残留距底缺口
 * （实测 276px）。故键盘开启且处于锚底状态时启动短时帧循环，每帧把
 * scrollTop 重新断言到真实底部，直至布局稳定（≤1px 连续 5 帧）或超时兜底。
 * 启动时机涵盖「弹起瞬间已在底部」与「键盘开启期间回到底部」（如 App 桥
 * 弹起滚底完成后 atBottom 翻转）两种锚底路径。
 * 不与用户争抢：用户上滚手势（wheel 上滚 / touchmove）或 atBottom 翻转为
 * false（大纲点选等程序化跳转）立即停让；键盘收起、组件卸载时清理
 * 定时器与监听。仅移动挂载本 hook，桌面零副作用。
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

    useEffect(() => {
        isAtBottomRef.current = isAtBottom;
    }, [isAtBottom]);

    // 卸载兜底清理（帧循环定时器 + 滚动手势监听）
    useEffect(() => () => stopSettleRef.current?.(), []);

    useEffect(() => {
        const scroller = listRef.current;
        const delta = keyboardHeight - prevKeyboardHeight.current;
        prevKeyboardHeight.current = keyboardHeight;

        // TEMP-DEBUG（诊断后移除）
        const dbg = ((window as unknown as { __kbDbg?: unknown[] }).__kbDbg ??= []);
        dbg.push({ t: Math.round(performance.now()), ev: 'effect', keyboardHeight, isAtBottom, delta, hasScroller: !!scroller });

        // 键盘收起：停止沉降循环（delta 补偿本就只追增高，收起不回拉）
        if (keyboardHeight <= 0) {
            stopSettleRef.current?.();
            stopSettleRef.current = null;
            return;
        }
        if (!scroller || !isAtBottom) return;

        // 一次性 delta 补偿（键盘弹起/继续增高瞬间，保留既有语义）
        if (delta > 0) scroller.scrollTop += delta;

        // 沉降循环幂等：已在运行则不重复挂监听/定时器
        if (stopSettleRef.current) return;

        let cancelNextFrame: (() => void) | null = null;
        let stableFrames = 0;
        const startedAt = performance.now();

        const stop = (reason?: string) => {
            dbg.push({ t: Math.round(performance.now()), ev: 'stop', reason }); // TEMP-DEBUG
            cancelNextFrame?.();
            cancelNextFrame = null;
            scroller.removeEventListener('wheel', handleWheel);
            scroller.removeEventListener('touchmove', handleTouchMove);
            stopSettleRef.current = null;
        };
        // 用户上滚手势 → 立即停让（下滚/点按不打断锚底）
        const handleWheel = (e: WheelEvent) => {
            if (e.deltaY < 0) stop('wheel-up');
        };
        const handleTouchMove = () => stop('touchmove');

        let pins = 0; // TEMP-DEBUG
        const step = () => {
            // 用户/程序化滚离底部（atBottom 翻转，如大纲点选跳转）→ 停让
            if (!isAtBottomRef.current) {
                stop('atBottom-false');
                return;
            }
            const target = scroller.scrollHeight - scroller.clientHeight;
            const gap = target - scroller.scrollTop;
            if (gap > 1) {
                scroller.scrollTop = target;
                stableFrames = 0;
                if (pins < 8) { pins += 1; dbg.push({ t: Math.round(performance.now()), ev: 'pin', gap: Math.round(gap) }); } // TEMP-DEBUG
            } else {
                stableFrames += 1;
            }
            if (stableFrames >= SETTLE_CONFIG.stableFrames
                || performance.now() - startedAt > SETTLE_CONFIG.maxDurationMs) {
                stop(stableFrames >= SETTLE_CONFIG.stableFrames ? 'stable' : 'timeout');
                return;
            }
            cancelNextFrame = scheduleFrame(step);
        };

        scroller.addEventListener('wheel', handleWheel, { passive: true });
        scroller.addEventListener('touchmove', handleTouchMove, { passive: true });
        stopSettleRef.current = stop;
        dbg.push({ t: Math.round(performance.now()), ev: 'start' }); // TEMP-DEBUG
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
