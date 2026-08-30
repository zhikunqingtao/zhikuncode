/**
 * useAsrAvailability — 检测 ASR 服务是否可用
 *
 * 通过 GET /api/asr/status 检查后端 ASR 状态，
 * 同时检测安全上下文与浏览器麦克风 / MediaRecorder 支持情况。
 * 结果缓存，避免重复请求。
 */

import { useState, useEffect, useRef } from 'react';

export function useAsrAvailability(): boolean {
    const [available, setAvailable] = useState(false);
    const fetchedRef = useRef(false);

    useEffect(() => {
        if (fetchedRef.current) return;
        fetchedRef.current = true;

        // 麦克风 API 要求安全上下文 (HTTPS / localhost)，
        // 非安全上下文下 navigator.mediaDevices 不存在，按钮不应展示
        if (!window.isSecureContext) {
            setAvailable(false);
            return;
        }
        if (!navigator.mediaDevices?.getUserMedia) {
            setAvailable(false);
            return;
        }
        if (typeof MediaRecorder === 'undefined') {
            setAvailable(false);
            return;
        }

        fetch('/api/asr/status')
            .then(res => {
                if (!res.ok) throw new Error(`HTTP ${res.status}`);
                return res.json();
            })
            .then((data: { available: boolean }) => {
                setAvailable(!!data.available);
            })
            .catch(() => {
                setAvailable(false);
            });
    }, []);

    return available;
}
