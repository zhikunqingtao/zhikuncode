/**
 * BroadcastChannel 跨 Tab 同步中间件
 * SPEC: §8.3 — 通过 BroadcastChannel API 实现跨 Tab 状态同步
 *
 * 重要: 内置 isBroadcasting 标志防止无限循环:
 * Tab A 改状态 → 广播 → Tab B 收到 → 设状态 → 广播 → Tab A 收到 → 死循环
 */

import type { StateCreator, StoreMutatorIdentifier } from 'zustand';

type BroadcastMessage<T> = {
    type: 'STATE_UPDATE';
    state: Partial<T>;
    senderId: string;
};

/** 每个 Tab 的唯一标识，避免收到自己的广播 */
const TAB_ID = `tab_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;

/** 检测 BroadcastChannel 是否可用 */
const hasBroadcastChannel = typeof BroadcastChannel !== 'undefined';

/**
 * 创建 BroadcastChannel 跨 Tab 同步中间件。
 *
 * @param channelName BroadcastChannel 名称 (每个 store 应唯一)
 * @param partialize  可选的状态过滤函数，只广播需要同步的字段
 */
export function broadcastMiddleware<T>(
    channelName: string,
    partialize?: (state: T) => Partial<T>
) {
    return <
        Mps extends [StoreMutatorIdentifier, unknown][] = [],
        Mcs extends [StoreMutatorIdentifier, unknown][] = []
    >(
        config: StateCreator<T, Mps, Mcs>
    ): StateCreator<T, Mps, Mcs> => {
        if (!hasBroadcastChannel) {
            // 环境不支持 BroadcastChannel (SSR/Node.js/老浏览器)，直通
            return config;
        }

        return (set, get, api) => {
            const channel = new BroadcastChannel(channelName);
            let isBroadcasting = false; // 防止无限循环

            // 接收其他 Tab 的广播
            channel.onmessage = (event: MessageEvent<BroadcastMessage<T>>) => {
                const data = event.data;
                if (data?.type === 'STATE_UPDATE' && data.senderId !== TAB_ID) {
                    isBroadcasting = true;
                    try {
                        // replace: true 表示替换而非合并
                        set(data.state as T extends object ? Partial<T> : T, true as any);
                    } finally {
                        isBroadcasting = false;
                    }
                }
            };

            // 包装 set 函数，在状态变更时广播
            const wrappedSet = ((...args: any[]) => {
                (set as any)(...args);

                if (!isBroadcasting) {
                    try {
                        const currentState = get();
                        const stateToSync = partialize
                            ? partialize(currentState)
                            : currentState;

                        const message: BroadcastMessage<T> = {
                            type: 'STATE_UPDATE',
                            state: stateToSync as Partial<T>,
                            senderId: TAB_ID,
                        };
                        channel.postMessage(message);
                    } catch (e) {
                        // 序列化失败时静默忽略 (如 state 包含函数)
                        console.debug('[BroadcastMiddleware] Failed to broadcast:', e);
                    }
                }
            }) as typeof set;

            // 清理: 页面卸载时关闭 channel
            if (typeof window !== 'undefined') {
                window.addEventListener('beforeunload', () => {
                    channel.close();
                });
            }

            return config(wrappedSet, get, api);
        };
    };
}
