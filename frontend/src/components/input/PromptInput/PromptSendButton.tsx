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
}

const PromptSendButton: React.FC<PromptSendButtonProps> = ({
    runActive,
    sendDisabled,
    stopDisabled,
    onSend,
    onInterrupt,
}) => (
    <>
        <button
            onClick={onSend}
            disabled={sendDisabled}
            aria-label={runActive ? '发送运行中干预' : '发送消息'}
            title={runActive ? '发送运行中干预' : '发送消息'}
            className="shrink-0 p-2.5 rounded-lg text-white transition-colors
                       bg-blue-600 hover:bg-blue-700 disabled:opacity-50 disabled:hover:bg-blue-600"
            type="button"
        >
            <Send size={16} />
        </button>
        {runActive && (
            <button
                onClick={onInterrupt}
                disabled={stopDisabled}
                aria-label="停止当前任务"
                title="停止当前任务"
                className="shrink-0 p-2.5 rounded-lg text-white transition-colors
                           bg-red-500 hover:bg-red-600 disabled:opacity-50"
                type="button"
            >
                <Square size={16} />
            </button>
        )}
    </>
);

export default PromptSendButton;
