/**
 * useStreamingText — 独立流式文本状态 (绕过 Zustand/Immer 开销)
 * SPEC: §4.2 流式渲染性能优化
 *
 * 使用 useSyncExternalStore 将 streamingContent 提取为独立订阅，
 * 仅 StreamingText 组件订阅此状态，避免其他组件重渲染。
 * RAF 批量合并 delta，降低到每帧更新一次。
 */

import { useSyncExternalStore } from 'react';

// 外部存储 (绕过 Zustand，避免 Immer 开销)
let streamingContent = '';
const listeners = new Set<() => void>();

export const streamingStore = {
    getSnapshot: () => streamingContent,
    subscribe: (listener: () => void) => {
        listeners.add(listener);
        return () => { listeners.delete(listener); };
    },
    append: (delta: string) => {
        streamingContent += delta;
        // 不立即通知，在 RAF 中批量通知
    },
    flush: () => {
        listeners.forEach(l => l());
    },
    clear: () => {
        const content = streamingContent;
        streamingContent = '';
        listeners.forEach(l => l());
        return content;
    },
};

// RAF 批量通知
let notifyRafId: number | null = null;

export function appendStreamDelta(delta: string) {
    streamingStore.append(delta);
    if (notifyRafId === null) {
        notifyRafId = requestAnimationFrame(() => {
            notifyRafId = null;
            streamingStore.flush();
        });
    }
}

export function flushStreamingBuffer() {
    if (notifyRafId !== null) {
        cancelAnimationFrame(notifyRafId);
        notifyRafId = null;
    }
    streamingStore.flush();
}

// React Hook:
const EMPTY_SNAPSHOT = '';
export function useStreamingText(): string {
    return useSyncExternalStore(
        streamingStore.subscribe,
        streamingStore.getSnapshot,
        () => EMPTY_SNAPSHOT  // getServerSnapshot: SSR 兼容
    );
}
