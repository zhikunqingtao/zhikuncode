/**
 * TtsStore — TTS 播放状态管理
 *
 * 全局单例播放状态，保证多条消息之间互斥：
 * 播放新消息会先停止上一条（abort 未完成的请求 + pause 正在播放的音频）。
 *
 * 注意: HTMLAudioElement / AbortController 不能放进 immer state
 * (immer 会冻结对象，调用其方法会报错)，改用模块级闭包变量持有。
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';

let currentAudio: HTMLAudioElement | null = null;
let currentAbort: AbortController | null = null;
/**
 * 播放代次版本号：每次 play()/stop() 自增。
 * 异步流程（fetch 返回、audio.play() 前、onended/onerror）跨越异步边界后校验自身代次，
 * 版本不匹配说明已被新的播放请求取代，静默退出，避免快速切换 A→B 时 A 的 stale 回调覆盖 B 的状态。
 */
let generation = 0;

export type TtsPlayState = 'idle' | 'loading' | 'playing' | 'error';

export interface TtsStoreState {
    /** 当前播放(或加载/出错)的消息 uuid，无则为 null */
    playingMessageId: string | null;
    playState: TtsPlayState;

    play: (messageId: string, text: string) => Promise<void>;
    stop: () => void;
}

export const useTtsStore = create<TtsStoreState>()(
    subscribeWithSelector(immer((set, get) => ({
        playingMessageId: null,
        playState: 'idle' as TtsPlayState,

        stop: () => {
            // 任何在途请求/回调立即失效
            generation++;
            if (currentAbort) {
                currentAbort.abort();
                currentAbort = null;
            }
            if (currentAudio) {
                currentAudio.pause();
                currentAudio = null;
            }
            set(d => {
                d.playState = 'idle';
                d.playingMessageId = null;
            });
        },

        play: async (messageId, text) => {
            // 互斥: 先终止上一次播放/请求
            get().stop();

            generation++;
            const myGen = generation;

            set(d => {
                d.playingMessageId = messageId;
                d.playState = 'loading';
            });

            const abort = new AbortController();
            currentAbort = abort;

            try {
                const res = await fetch('/api/tts/synthesize', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ text }),
                    signal: abort.signal,
                });
                if (myGen !== generation) return;
                if (!res.ok) throw new Error(`HTTP ${res.status}`);

                const { audioUrl } = await res.json() as { audioUrl: string };
                if (myGen !== generation) return;

                const audio = new Audio(audioUrl);
                currentAudio = audio;

                audio.onended = () => {
                    if (myGen !== generation) return;
                    set(d => {
                        d.playState = 'idle';
                        d.playingMessageId = null;
                    });
                    currentAudio = null;
                };
                audio.onerror = () => {
                    if (myGen !== generation) return;
                    set(d => {
                        d.playState = 'error';
                    });
                };

                await audio.play();
                if (myGen !== generation) return;
                set(d => {
                    d.playState = 'playing';
                });
            } catch (e) {
                // 用户主动切换/停止，不算错误
                if (e instanceof Error && e.name === 'AbortError') return;
                if (myGen !== generation) return;

                set(d => {
                    d.playState = 'error';
                    d.playingMessageId = messageId;
                });
                // 错误提示 2s 后自动复位 (仅当期间未被其他消息接管)
                setTimeout(() => {
                    if (myGen !== generation) return;
                    set(d => {
                        if (d.playingMessageId === messageId) {
                            d.playState = 'idle';
                            d.playingMessageId = null;
                        }
                    });
                }, 2000);
            }
        },
    })))
);
