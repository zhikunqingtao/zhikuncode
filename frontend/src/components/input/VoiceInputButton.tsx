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
    compact?: boolean;
    disabledReason?: string;
}

function formatTime(seconds: number): string {
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

const VoiceInputButton: React.FC<VoiceInputButtonProps> = ({ onTranscript, disabled = false, compact = false, disabledReason }) => {
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
        : disabled && disabledReason ? disabledReason : '语音输入';

    const buttonDisabled = disabled || isTranscribing || isRequesting;

    // 声波 bar 基础高度与动画延迟，形成波浪感
    const soundwaveBars = [
        { height: 6, delay: '0ms' },
        { height: 10, delay: '150ms' },
        { height: 8, delay: '300ms' },
        { height: 12, delay: '450ms' },
    ];

    return (
        <div className="relative flex shrink-0 items-center gap-1">
            <button
                onClick={handleClick}
                disabled={buttonDisabled}
                className={`panel-control ${compact ? 'h-11 w-11 flex items-center justify-center' : 'h-10 w-10 flex items-center justify-center'} shrink-0 p-2 rounded-[10px] transition-interactive duration-fast
                    focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring
                    ${buttonDisabled
                        ? 'text-t4 cursor-not-allowed opacity-50'
                        : isRecording
                        ? 'text-err hover:bg-errsoft'
                        : isError
                        ? 'text-err'
                        : 'text-t3 hover:text-t1 hover:bg-hover2'}`}
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
            <div className={compact ? 'absolute bottom-full right-0 z-30 mb-2 flex max-w-[240px] items-center gap-1 rounded-[10px] bg-surfacev2 shadow-e2' : 'flex items-center gap-1'}>
            {isRecording && (
                <span className="flex items-center gap-0.5 h-4" aria-hidden="true">
                    {soundwaveBars.map((bar, i) => (
                        <span
                            key={i}
                            className="w-0.5 rounded-full bg-err animate-soundwave"
                            style={{ height: `${bar.height}px`, animationDelay: bar.delay }}
                        />
                    ))}
                </span>
            )}
            {isRecording && (
                <span className="text-[13px] text-t3 font-mono tabular-nums select-none">
                    {formatTime(elapsedSeconds)}
                </span>
            )}
            {isError && error && (
                <span className="text-[13px] text-err select-none whitespace-normal break-words">{error}</span>
            )}
            </div>
        </div>
    );
};

export default React.memo(VoiceInputButton);
