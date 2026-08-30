/**
 * VoiceInputButton — 语音输入按钮组件
 *
 * 在 toolbar 中提供麦克风录制入口，
 * 录音中以声波动画 + 时长文本呈现，识别中显示加载动画。
 */

import React from 'react';
import { Mic, Square, Loader2 } from 'lucide-react';
import { useVoiceRecorder } from '@/hooks/useVoiceRecorder';

interface VoiceInputButtonProps {
    onTranscript: (text: string) => void;
    disabled?: boolean;
}

function formatTime(seconds: number): string {
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

const VoiceInputButton: React.FC<VoiceInputButtonProps> = ({ onTranscript, disabled = false }) => {
    const { state, elapsedSeconds, error, startRecording, stopRecording } = useVoiceRecorder(onTranscript);

    const isRecording = state === 'recording';
    const isTranscribing = state === 'transcribing';
    const isRequesting = state === 'requesting';
    const isError = state === 'error';
    const isIdle = state === 'idle' || state === 'error';

    const handleClick = () => {
        if (disabled) return;
        if (isRecording) {
            stopRecording();
        } else if (isIdle) {
            startRecording();
        }
    };

    const titleText = isRecording
        ? '停止录音'
        : isTranscribing
        ? '识别中...'
        : isRequesting
        ? '请求麦克风权限...'
        : isError && error
        ? error
        : '语音输入';

    const buttonDisabled = disabled || isTranscribing || isRequesting;

    // 声波 bar 基础高度与动画延迟，形成波浪感
    const soundwaveBars = [
        { height: 6, delay: '0ms' },
        { height: 10, delay: '150ms' },
        { height: 8, delay: '300ms' },
        { height: 12, delay: '450ms' },
    ];

    return (
        <div className="relative flex items-center gap-1">
            <button
                onClick={handleClick}
                disabled={buttonDisabled}
                className={`shrink-0 p-2 rounded-lg transition-colors
                    ${buttonDisabled
                        ? 'text-gray-600 cursor-not-allowed opacity-50'
                        : isRecording
                        ? 'text-red-500 hover:bg-red-500/10'
                        : isError
                        ? 'text-red-400'
                        : 'text-gray-400 hover:text-gray-200 hover:bg-gray-800'}`}
                title={titleText}
                type="button"
                aria-label={titleText}
            >
                {isTranscribing || isRequesting ? (
                    <Loader2 size={18} className="animate-spin" />
                ) : isRecording ? (
                    <Square size={12} fill="currentColor" />
                ) : (
                    <Mic size={18} />
                )}
            </button>
            {isRecording && (
                <span className="flex items-center gap-0.5 h-4" aria-hidden="true">
                    {soundwaveBars.map((bar, i) => (
                        <span
                            key={i}
                            className="w-0.5 rounded-full bg-red-500 animate-soundwave"
                            style={{ height: `${bar.height}px`, animationDelay: bar.delay }}
                        />
                    ))}
                </span>
            )}
            {isRecording && (
                <span className="text-xs text-gray-400 font-mono tabular-nums select-none">
                    {formatTime(elapsedSeconds)}
                </span>
            )}
            {isError && error && (
                <span className="text-xs text-red-400 select-none whitespace-nowrap">{error}</span>
            )}
        </div>
    );
};

export default React.memo(VoiceInputButton);
