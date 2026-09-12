/**
 * PromptTextarea — 受控 textarea + 自适应高度 + @文件补全触发
 *
 * §8.3.1 PromptInput 拆分：从原 PromptInput.tsx 纯搬运（零行为变化）。
 * 仅负责输入框呈现与输入事件采集；状态与快捷键逻辑均在 usePromptState。
 */

import React, { useEffect } from 'react';

interface PromptTextareaProps {
    value: string;
    onValueChange: (text: string) => void;
    onCursorChange: (pos: number | null) => void;
    onAtQueryChange: (query: string | null) => void;
    onSlashIntent: (startsWithSlash: boolean) => void;
    onKeyDown: (e: React.KeyboardEvent<HTMLTextAreaElement>) => void;
    onPaste: (e: React.ClipboardEvent<HTMLTextAreaElement>) => void;
    textareaRef: React.RefObject<HTMLTextAreaElement>;
    compacting: boolean;
    runActive: boolean;
    simpleMode: boolean;
    disabled: boolean;
}

const PromptTextarea: React.FC<PromptTextareaProps> = ({
    value,
    onValueChange,
    onCursorChange,
    onAtQueryChange,
    onSlashIntent,
    onKeyDown,
    onPaste,
    textareaRef,
    compacting,
    runActive,
    simpleMode,
    disabled,
}) => {
    // Auto-resize textarea height
    useEffect(() => {
        const el = textareaRef.current;
        if (el) {
            el.style.height = 'auto';
            el.style.height = Math.min(el.scrollHeight, 200) + 'px';
        }
    }, [value, textareaRef]);

    return (
        <textarea
            ref={textareaRef}
            value={value}
            onChange={e => {
                const text = e.target.value;
                const cursor = e.target.selectionStart || 0;
                onCursorChange(e.target.selectionStart ?? null);
                onValueChange(text);

                // 检测 @ 触发
                const textBeforeCursor = text.slice(0, cursor);
                const atMatch = textBeforeCursor.match(/@([\w./\-]*)$/);
                if (atMatch) {
                    onAtQueryChange(atMatch[1]);
                } else {
                    onAtQueryChange(null);
                }

                // 检测 / 命令触发
                onSlashIntent(text.startsWith('/'));
            }}
            onKeyDown={onKeyDown}
            onPaste={onPaste}
            onSelect={() => {
                onCursorChange(textareaRef.current?.selectionStart ?? null);
            }}
            onBlur={() => {
                onCursorChange(textareaRef.current?.selectionStart ?? null);
            }}
            placeholder={
                compacting
                    ? '正在压缩上下文，请稍候…'
                    : runActive
                    ? '输入对当前任务的新指令，将在当前操作完成后应用'
                    : simpleMode
                    ? '描述你希望完成或继续修改的事情…'
                    : `输入消息…（/ 查看命令，${navigator.platform.includes('Mac') ? '⌘' : 'Ctrl+'}K 打开命令面板）`
            }
            disabled={disabled}
            aria-label="输入消息"
            aria-multiline="true"
            className="flex-1 resize-none rounded-lg border border-gray-700 bg-gray-900
                       px-3 py-2 text-sm text-gray-100
                       focus:outline-none focus:ring-2 focus:ring-blue-500/50
                       disabled:opacity-50 placeholder-gray-500"
            rows={1}
            autoFocus
        />
    );
};

export default PromptTextarea;
