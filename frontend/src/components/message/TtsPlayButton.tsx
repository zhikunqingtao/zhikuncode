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

    const stateClasses = disabled
        ? 'text-t3 cursor-not-allowed opacity-50'
        : isPlaying
            ? 'text-err hover:bg-errsoft'
            : isLoading
                ? 'text-accent2-ink hover:bg-hover2'
                : 'text-t2 hover:bg-hover2 hover:text-t1';

    return (
        <button
            onClick={handleClick}
            disabled={disabled}
            className={`message-action-button shrink-0 flex items-center justify-center rounded-[10px] transition-colors ${stateClasses}`}
            title={titleText}
            type="button"
            aria-label={titleText}
        >
            {isLoading ? (
                <Loader2 size={18} className="animate-spin" />
            ) : isPlaying ? (
                <Square size={18} fill="currentColor" />
            ) : (
                <Volume2 size={18} />
            )}
        </button>
    );
};

export default React.memo(TtsPlayButton);
