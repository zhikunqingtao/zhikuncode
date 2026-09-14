/**
 * PromptSendButton — 发送/停止按钮
 *
 * §8.3.1 PromptInput 拆分：从原 PromptInput.tsx 纯搬运（零行为变化）。
 * 状态机：idle（可发送）/ streaming（runActive：发送=运行中干预 + 停止钮）/ disabled。
 */

import React from 'react';
import { Send, Square } from 'lucide-react';

interface PromptSendButtonProps {
    runActive: boolean;
    sendDisabled: boolean;
    stopDisabled: boolean;
    onSend: () => void;
    onInterrupt: () => void;
    /** §7.6 移动胶囊形态：44px 圆形 accent2-strong；默认 desktop 零回归 */
    variant?: 'desktop' | 'mobile';
}

const PromptSendButton: React.FC<PromptSendButtonProps> = ({
    runActive,
    sendDisabled,
    stopDisabled,
    onSend,
    onInterrupt,
    variant = 'desktop',
}) => {
    const isMobile = variant === 'mobile';
    return (
        <>
            <button
                onClick={onSend}
                disabled={sendDisabled}
                aria-label={runActive ? '发送运行中干预' : '发送消息'}
                title={runActive ? '发送运行中干预' : '发送消息'}
                className={isMobile
                    ? `flex h-11 w-11 shrink-0 items-center justify-center rounded-full
                       bg-accent2-strong text-white shadow-e1 transition-interactive
                       duration-fast active:scale-95
                       disabled:opacity-[.38] disabled:shadow-none`
                    : `shrink-0 p-2.5 rounded-xl text-white
                       bg-accent2-strong shadow-e1 transition-interactive duration-fast
                       hover:bg-accent2-hover hover:shadow-e2 active:scale-[.98]
                       focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring
                       disabled:opacity-50 disabled:shadow-none`}
                type="button"
            >
                <Send size={isMobile ? 18 : 16} />
            </button>
            {runActive && (
                <button
                    onClick={onInterrupt}
                    disabled={stopDisabled}
                    aria-label="停止当前任务"
                    title="停止当前任务"
                    className={isMobile
                        ? `flex h-11 w-11 shrink-0 items-center justify-center rounded-full
                           bg-red-500 text-white shadow-e1 transition-interactive
                           duration-fast active:scale-95
                           disabled:opacity-[.38] disabled:shadow-none`
                        : `shrink-0 p-2.5 rounded-xl text-white
                           bg-err shadow-e1 transition-interactive duration-fast
                           hover:opacity-90 active:scale-[.98]
                           focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring
                           disabled:opacity-50`}
                    type="button"
                >
                    <Square size={isMobile ? 18 : 16} />
                </button>
            )}
        </>
    );
};

export default PromptSendButton;
