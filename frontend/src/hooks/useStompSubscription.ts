/**
 * useStompSubscription — STOMP topic 订阅 React Hook
 *
 * 对齐 ZhikunCode 差异化升级方案 v1.5（B/C 共用前置任务）：
 * - 升级项 B（§3.5 多 Agent 协作可观测）：订阅 /user/queue/coordinator/{workflowId} 等
 * - 升级项 C（§4.5 自动可视化）：visualization 走 /queue/messages 主通路，不依赖本 Hook
 *
 * 设计：
 * - 薄封装 stompClient.getStompClient()（v1.2 BLK-R2-1 校准：不修改 useWebSocket 返回字段）
 * - 连接未就绪时小间隔轮询，就绪后订阅
 * - 卸载时自动取消订阅；topic 变化时自动重订阅
 * - handler 走 ref 转发，避免订阅因 handler 闭包变化而抖动
 *
 * 使用示例：
 *   useStompSubscription('/user/queue/coordinator/abc', (msg) => {
 *       const event = JSON.parse(msg.body);
 *       // ...
 *   });
 *
 * 局限：
 * - STOMP 连接在断线重连后订阅会失效；当前依赖组件 unmount/remount 重新订阅。
 *   如需支持热重连自动恢复，后续可在 stompClient.ts 暴露 onConnect 监听器 API 扩展。
 */

import { useEffect, useRef } from 'react';
import type { IMessage, StompSubscription } from '@stomp/stompjs';
import { getStompClient } from '@/api/stompClient';

/** STOMP 订阅 handler 类型 */
export type StompMessageHandler = (message: IMessage) => void;

/** 轮询等待连接就绪：100ms × 50 = 最多等 5 秒 */
const CONNECT_WAIT_INTERVAL_MS = 100;
const CONNECT_WAIT_MAX_ATTEMPTS = 50;

/**
 * 订阅指定 STOMP topic。
 * @param topic   topic 地址（传 null 或 undefined 跳过订阅）
 * @param handler 收到消息时的回调；handler 变化不会触发重新订阅
 */
export function useStompSubscription(
    topic: string | null | undefined,
    handler: StompMessageHandler,
): void {
    // 使用 ref 转发 handler，避免每次 handler 变化都重建订阅
    const handlerRef = useRef<StompMessageHandler>(handler);
    useEffect(() => {
        handlerRef.current = handler;
    }, [handler]);

    useEffect(() => {
        if (!topic) return;

        let cancelled = false;
        let subscription: StompSubscription | null = null;
        let attempts = 0;
        let timer: ReturnType<typeof setTimeout> | null = null;

        const tryConnect = (): void => {
            if (cancelled) return;
            const client = getStompClient();
            if (client && client.connected) {
                try {
                    subscription = client.subscribe(topic, (msg) => {
                        // 经 ref 调用，保证 handler 始终为最新闭包
                        handlerRef.current(msg);
                    });
                } catch (err) {
                    console.warn(
                        `[useStompSubscription] subscribe failed for topic="${topic}":`,
                        err,
                    );
                }
                return;
            }
            if (attempts++ >= CONNECT_WAIT_MAX_ATTEMPTS) {
                console.warn(
                    `[useStompSubscription] gave up waiting for STOMP connection; topic="${topic}" never subscribed`,
                );
                return;
            }
            timer = setTimeout(tryConnect, CONNECT_WAIT_INTERVAL_MS);
        };

        tryConnect();

        return () => {
            cancelled = true;
            if (timer) {
                clearTimeout(timer);
                timer = null;
            }
            if (subscription) {
                try {
                    subscription.unsubscribe();
                } catch (err) {
                    console.debug(
                        `[useStompSubscription] unsubscribe failed for topic="${topic}":`,
                        err,
                    );
                }
                subscription = null;
            }
        };
    }, [topic]);
}

export default useStompSubscription;
