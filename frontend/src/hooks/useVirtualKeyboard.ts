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

/**
 * 键盘弹出时的消息列表滚动补偿。
 * SPEC: §8.8.3 useKeyboardScrollCompensation
 */
export function useKeyboardScrollCompensation(
    listRef: React.RefObject<HTMLDivElement | null>,
    keyboardHeight: number
) {
    const prevKeyboardHeight = useRef(0);

    useEffect(() => {
        if (!listRef.current) return;
        const delta = keyboardHeight - prevKeyboardHeight.current;
        if (delta > 0) {
            listRef.current.scrollTop += delta;
        }
        prevKeyboardHeight.current = keyboardHeight;
    }, [keyboardHeight, listRef]);
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
