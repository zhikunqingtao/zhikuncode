/**
 * MessageActions — 消息 hover 操作条
 *
 * 每条 user/assistant 消息在鼠标 hover 时显示（依赖父级消息根节点的 `group` class）:
 * - 复制按钮 (Copy 图标，点击后切换 Check 图标 2 秒恢复)
 * - 时分秒时间戳 (X月X日（周X）HH:MM:SS)
 *
 * 复制规则:
 * - 有文本 → 复制 text 块 Markdown 源码 (navigator.clipboard.writeText)
 * - 无文本但有图片 → base64 转 Blob 复制图片 (navigator.clipboard.write + ClipboardItem，
 *   不支持或失败时降级复制图片 URL 文本)
 * - 无可复制内容 / 流式进行中 → 不渲染复制按钮 (时间戳照常显示)
 */

import React, { useCallback, useState } from 'react';
import { Copy, Check } from 'lucide-react';
import type { Message } from '@/types';
import { formatMessageTime } from '@/utils/datetime';
import {
    extractMessageText,
    extractMessageImage,
    hasCopyableContent,
    copyImageToClipboard,
} from '@/utils/messageContent';

interface MessageActionsProps {
    message: Message;
    /** 流式进行中的 assistant 消息不显示复制按钮 */
    isStreaming?: boolean;
}

/** 复制成功图标反馈时长 (ms)，与 CodeBlock 保持一致 */
const COPY_ICON_RESET_MS = 2000;

const MessageActions: React.FC<MessageActionsProps> = ({ message, isStreaming = false }) => {
    const [copied, setCopied] = useState(false);

    const copyable = !isStreaming && hasCopyableContent(message);

    const handleCopy = useCallback(async () => {
        try {
            const text = extractMessageText(message);
            if (text !== null) {
                await navigator.clipboard.writeText(text);
            } else {
                const image = extractMessageImage(message);
                if (!image) return;
                await copyImageToClipboard(image);
            }
            setCopied(true);
            setTimeout(() => setCopied(false), COPY_ICON_RESET_MS);
        } catch {
            // 剪贴板权限被拒等场景静默失败，不打断 UI
        }
    }, [message]);

    return (
        <div className="flex items-center gap-2 opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none group-hover:pointer-events-auto">
            {copyable && (
                <button
                    type="button"
                    onClick={handleCopy}
                    className="rounded p-1 text-[var(--text-muted)] hover:bg-[var(--bg-hover)] hover:text-[var(--text-primary)] transition-colors"
                    title={copied ? '已复制' : '复制'}
                    aria-label={copied ? '已复制' : '复制'}
                    data-testid="message-copy-button"
                >
                    {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
                </button>
            )}
            <span
                className="text-xs text-[var(--text-muted)] tabular-nums select-none"
                data-testid="message-timestamp"
            >
                {formatMessageTime(message.timestamp)}
            </span>
        </div>
    );
};

export default React.memo(MessageActions);
