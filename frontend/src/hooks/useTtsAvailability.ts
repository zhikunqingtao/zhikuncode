/**
 * useTtsAvailability — 检测 TTS 服务是否可用
 *
 * 通过 GET /api/tts/status 检查后端 TTS 状态。
 * 播放不依赖录音能力，因此无需浏览器 MediaRecorder 检测。
 * 结果使用模块级缓存 + 共享 Promise（并发去重），
 * 多个组件实例挂载时只发起一次请求。
 */

import { useState, useEffect } from 'react';

let cachedAvailable: boolean | null = null;
let pending: Promise<boolean> | null = null;

async function fetchAvailability(): Promise<boolean> {
    if (cachedAvailable !== null) return cachedAvailable;
    if (!pending) {
        pending = fetch('/api/tts/status')
            .then(res => {
                if (!res.ok) throw new Error(`HTTP ${res.status}`);
                return res.json();
            })
            .then((data: { available: boolean }) => {
                cachedAvailable = !!data.available;
                return cachedAvailable;
            })
            .catch(() => {
                cachedAvailable = false;
                return false;
            })
            .finally(() => {
                pending = null;
            });
    }
    return pending;
}

export function useTtsAvailability(): boolean {
    const [available, setAvailable] = useState(cachedAvailable ?? false);

    useEffect(() => {
        let cancelled = false;

        fetchAvailability().then(result => {
            if (!cancelled) setAvailable(result);
        });

        return () => {
            cancelled = true;
        };
    }, []);

    return available;
}
