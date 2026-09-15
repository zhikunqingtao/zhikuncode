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
    /** §7.6 移动胶囊形态：44px 点击高度；发送使用图标与文字，停止保留紧凑图标；默认 desktop 零回归 */
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
                    ? `flex h-11 shrink-0 items-center justify-center rounded-full
                       text-white transition-interactive
                       duration-fast active:scale-95 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent2-ink
                       disabled:opacity-[.38] disabled:shadow-none`
                    : `flex h-10 w-10 shrink-0 items-center justify-center rounded-[10px] text-white
                       bg-accent2-strong shadow-e1 transition-interactive duration-fast
                       hover:bg-accent2-hover active:scale-[.98]
                       focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring
                       disabled:opacity-50 disabled:shadow-none`}
                type="button"
            >
                <span className={isMobile ? "flex h-8 items-center justify-center gap-1 rounded-full bg-accent2-strong px-2.5 text-sm font-medium" : "contents"}><Send size={isMobile ? 14 : 18} />{isMobile && <span>发送</span>}</span>
            </button>
            {runActive && (
                <button
                    onClick={onInterrupt}
                    disabled={stopDisabled}
                    aria-label="停止当前任务"
                    title="停止当前任务"
                    className={isMobile
                        ? `flex h-11 w-11 shrink-0 items-center justify-center rounded-full
                           text-white transition-interactive
                           duration-fast active:scale-95
                           disabled:opacity-[.38] disabled:shadow-none`
                        : `flex h-10 w-10 shrink-0 items-center justify-center rounded-[10px] text-white
                           bg-err shadow-e1 transition-interactive duration-fast
                           hover:opacity-90 active:scale-[.98]
                           focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring
                           disabled:opacity-50`}
                    type="button"
                >
                    <span className={isMobile ? "flex h-5 w-5 items-center justify-center rounded-full bg-err" : "contents"}><Square size={isMobile ? 12 : 18} /></span>
                </button>
            )}
        </>
    );
};

export default PromptSendButton;
