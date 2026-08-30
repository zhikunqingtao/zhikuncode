/**
 * useVoiceRecorder — 语音录制 + ASR 识别 hook
 *
 * 封装 MediaRecorder API，管理录音状态机，
 * 录制完成后自动上传至 /api/asr/recognize 进行语音识别。
 */

import { useState, useRef, useCallback, useEffect } from 'react';

export type VoiceState = 'idle' | 'requesting' | 'recording' | 'transcribing' | 'error';

const MIME_CANDIDATES = [
    'audio/webm;codecs=opus',
    'audio/webm',
    'audio/ogg;codecs=opus',
    'audio/mp4',
] as const;

function pickMimeType(): string {
    for (const mime of MIME_CANDIDATES) {
        if (typeof MediaRecorder !== 'undefined' && MediaRecorder.isTypeSupported(mime)) {
            return mime;
        }
    }
    return '';
}

function formatFromMime(mime: string): string {
    if (mime.includes('webm')) return 'webm';
    if (mime.includes('ogg')) return 'ogg';
    if (mime.includes('mp4')) return 'mp4';
    return 'webm';
}

export function useVoiceRecorder(
    onTranscript: (text: string) => void,
    maxDurationMs = 120000,
): {
    state: VoiceState;
    elapsedSeconds: number;
    error: string | null;
    startRecording: () => void;
    stopRecording: () => void;
    cancelRecording: () => void;
} {
    const [state, setState] = useState<VoiceState>('idle');
    const [elapsedSeconds, setElapsedSeconds] = useState(0);
    const [error, setError] = useState<string | null>(null);

    const mediaRecorderRef = useRef<MediaRecorder | null>(null);
    const streamRef = useRef<MediaStream | null>(null);
    const chunksRef = useRef<Blob[]>([]);
    const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
    const abortRef = useRef<AbortController | null>(null);
    const errorTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const mimeTypeRef = useRef<string>('');
    // 同步镜像 state，供 useCallback 闭包内做最新状态判断
    const stateRef = useRef<VoiceState>('idle');

    const setVoiceState = useCallback((next: VoiceState) => {
        stateRef.current = next;
        setState(next);
    }, []);

    // Cleanup helper
    const cleanup = useCallback(() => {
        if (timerRef.current) {
            clearInterval(timerRef.current);
            timerRef.current = null;
        }
        if (mediaRecorderRef.current) {
            // 先摘除 onstop 回调，避免此处的 stop() 触发上传流程（取消场景不应上传）
            mediaRecorderRef.current.onstop = null;
            if (mediaRecorderRef.current.state !== 'inactive') {
                mediaRecorderRef.current.stop();
            }
        }
        mediaRecorderRef.current = null;

        if (streamRef.current) {
            streamRef.current.getTracks().forEach(t => t.stop());
            streamRef.current = null;
        }
        if (abortRef.current) {
            abortRef.current.abort();
            abortRef.current = null;
        }
    }, []);

    // Auto-recover from error state after 3s
    useEffect(() => {
        if (state === 'error') {
            errorTimerRef.current = setTimeout(() => {
                setVoiceState('idle');
                setError(null);
            }, 3000);
            return () => {
                if (errorTimerRef.current) clearTimeout(errorTimerRef.current);
            };
        }
    }, [state, setVoiceState]);

    // Full cleanup on unmount
    useEffect(() => {
        return () => {
            cleanup();
            if (errorTimerRef.current) clearTimeout(errorTimerRef.current);
        };
    }, [cleanup]);

    const startRecording = useCallback(() => {
        // 仅 idle/error 态可启动，防止 requesting/recording/transcribing 期间重复触发
        if (stateRef.current !== 'idle' && stateRef.current !== 'error') return;

        setVoiceState('requesting');
        setElapsedSeconds(0);
        setError(null);
        chunksRef.current = [];

        const mime = pickMimeType();
        mimeTypeRef.current = mime;

        navigator.mediaDevices
            .getUserMedia({ audio: true })
            .then(stream => {
                streamRef.current = stream;

                const options: MediaRecorderOptions = {};
                if (mime) options.mimeType = mime;

                const recorder = new MediaRecorder(stream, options);
                mediaRecorderRef.current = recorder;

                recorder.ondataavailable = (e: BlobEvent) => {
                    if (e.data.size > 0) {
                        chunksRef.current.push(e.data);
                    }
                };

                recorder.start(250); // collect chunks every 250ms

                // Timer
                timerRef.current = setInterval(() => {
                    setElapsedSeconds(prev => prev + 1);
                }, 1000);

                setVoiceState('recording');
            })
            .catch((err: DOMException) => {
                let msg = '语音识别失败，请重试';
                if (err.name === 'NotAllowedError') {
                    msg = '需要麦克风权限，请在浏览器设置中允许';
                } else if (err.name === 'NotFoundError') {
                    msg = '未检测到麦克风设备';
                }
                setError(msg);
                setVoiceState('error');
            });
    }, [setVoiceState]);

    const stopRecording = useCallback(() => {
        const recorder = mediaRecorderRef.current;
        if (!recorder || recorder.state === 'inactive') return;

        // Stop timer
        if (timerRef.current) {
            clearInterval(timerRef.current);
            timerRef.current = null;
        }

        // onstop 由规范保证在最后一个 ondataavailable 之后触发，
        // 此时 chunksRef 已包含全部音频数据，无需固定延时等待
        recorder.onstop = () => {
            // 录音结束后再释放麦克风，避免干扰最后一片数据的 flush
            if (streamRef.current) {
                streamRef.current.getTracks().forEach(t => t.stop());
                streamRef.current = null;
            }

            const blob = new Blob(chunksRef.current, { type: mimeTypeRef.current || 'audio/webm' });
            const format = formatFromMime(mimeTypeRef.current);

            const formData = new FormData();
            formData.append('audio', blob, `recording.${format}`);

            const controller = new AbortController();
            abortRef.current = controller;
            // 65 秒超时：略长于后端 60 秒上限，让后端先超时并返回明确错误
            // timedOut 标志区分「超时 abort」与「用户主动取消 abort」
            let timedOut = false;
            const timeoutId = setTimeout(() => {
                timedOut = true;
                controller.abort();
            }, 65000);

            fetch('/api/asr/recognize', {
                method: 'POST',
                body: formData,
                signal: controller.signal,
            })
                .then(res => {
                    if (!res.ok) throw new Error(`HTTP ${res.status}`);
                    return res.json();
                })
                .then((data: { text: string }) => {
                    if (data.text) {
                        onTranscript(data.text);
                    }
                    setVoiceState('idle');
                })
                .catch((err: Error) => {
                    if (err.name === 'AbortError') {
                        // 用户主动取消（cancelRecording 已回 idle），保持现状
                        if (!timedOut) return;
                        // 超时：进入 error 态，3 秒后自动回 idle
                        setError('识别超时，请重试');
                        setVoiceState('error');
                        return;
                    }
                    setError('语音识别失败，请重试');
                    setVoiceState('error');
                })
                .finally(() => {
                    clearTimeout(timeoutId);
                    abortRef.current = null;
                });
        };

        // Stop recorder (triggers final ondataavailable, then onstop)
        recorder.stop();

        setVoiceState('transcribing');
    }, [onTranscript, setVoiceState]);

    const cancelRecording = useCallback(() => {
        cleanup();
        setVoiceState('idle');
        setElapsedSeconds(0);
        setError(null);
    }, [cleanup, setVoiceState]);

    // Auto-stop at maxDuration
    useEffect(() => {
        if (state === 'recording' && elapsedSeconds * 1000 >= maxDurationMs) {
            stopRecording();
        }
    }, [state, elapsedSeconds, maxDurationMs, stopRecording]);

    return {
        state,
        elapsedSeconds,
        error,
        startRecording,
        stopRecording,
        cancelRecording,
    };
}
