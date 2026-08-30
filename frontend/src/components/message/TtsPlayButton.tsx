/**
 * TtsPlayButton — 消息朗读按钮
 *
 * 挂在 Assistant 消息头部，点击朗读该消息纯文本内容。
 * 播放状态来自全局 ttsStore，因此多条消息天然互斥：
 * 加载中显示 spinner，播放中显示停止方块（可点击停止）。
 */

import React from 'react';
import { Volume2, Square, Loader2 } from 'lucide-react';
import { useTtsStore } from '@/store/ttsStore';

interface TtsPlayButtonProps {
    messageId: string;
    text: string;
}

const TtsPlayButton: React.FC<TtsPlayButtonProps> = ({ messageId, text }) => {
    const playingMessageId = useTtsStore(s => s.playingMessageId);
    const playState = useTtsStore(s => s.playState);
    const play = useTtsStore(s => s.play);
    const stop = useTtsStore(s => s.stop);

    const isThis = playingMessageId === messageId;
    const isLoading = isThis && playState === 'loading';
    const isPlaying = isThis && playState === 'playing';
    const disabled = !text.trim();

    const handleClick = () => {
        if (disabled) return;
        if (isLoading || isPlaying) {
            stop();
        } else {
            void play(messageId, text);
        }
    };

    const titleText = isPlaying || isLoading ? '停止朗读' : '朗读';

    // 始终可见的按钮实体：淡边框 + 浅背景；各状态用颜色区分
    const stateClasses = disabled
        ? 'border-gray-600/40 bg-gray-700/20 text-[var(--text-muted)] cursor-not-allowed opacity-50'
        : isPlaying
            ? 'border-red-500/40 bg-red-500/15 text-red-400 hover:bg-red-500/25'
            : isLoading
                ? 'border-gray-600/60 bg-gray-700/30 text-purple-400 hover:bg-gray-600/50'
                : 'border-gray-600/60 bg-gray-700/30 text-gray-300 hover:bg-gray-600/50 hover:text-white';

    return (
        <button
            onClick={handleClick}
            disabled={disabled}
            className={`shrink-0 flex items-center rounded-md border px-1.5 py-1 transition-colors ${stateClasses}`}
            title={titleText}
            type="button"
            aria-label={titleText}
        >
            {isLoading ? (
                <Loader2 size={16} className="animate-spin" />
            ) : isPlaying ? (
                <Square size={16} fill="currentColor" />
            ) : (
                <Volume2 size={16} />
            )}
        </button>
    );
};

export default React.memo(TtsPlayButton);
