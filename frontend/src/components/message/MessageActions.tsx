/**
 * MessageActions — 消息操作行
 *
 * 每条 user/assistant 消息底部渲染：
 * - 复制按钮 (Copy 图标，点击后切换 Check 图标 2 秒恢复)
 * - 时分秒时间戳 (X月X日（周X）HH:MM:SS)
 *
 * §7.2 消息操作行：ghost 小钮默认 text-t4，hover 消息块（父级 group）升 text-t2，
 * 行顶 hairline 分隔。复制规则:
 * - 有文本 → 复制 text 块 Markdown 源码 (navigator.clipboard.writeText)
 * - 无文本但有图片 → base64 转 Blob 复制图片 (navigator.clipboard.write + ClipboardItem，
 *   不支持或失败时降级复制图片 URL 文本)
 * - 无可复制内容 / 流式进行中 → 不渲染复制按钮 (时间戳照常显示)
 */

import React, { useCallback, useState } from 'react';
import { Copy, Check } from 'lucide-react';
import type { Message } from '@/types';
import { formatMessageTime } from '@/utils/datetime';
import { cn } from '@/components/ui/cn';
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
    className?: string;
}

/** 复制成功图标反馈时长 (ms)，与 CodeBlock 保持一致 */
const COPY_ICON_RESET_MS = 2000;

const MessageActions: React.FC<MessageActionsProps> = ({ message, isStreaming = false, className }) => {
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
        <div
            className={cn(
                'mt-2 flex items-center justify-end gap-2 border-t border-hairline pt-1.5',
                className,
            )}
        >
            {copyable && (
                <button
                    type="button"
                    onClick={handleCopy}
                    className="rounded-md p-1 text-t4 transition-colors duration-fast group-hover:text-t2 hover:bg-hover2 hover:!text-t1"
                    title={copied ? '已复制' : '复制'}
                    aria-label={copied ? '已复制' : '复制'}
                    data-testid="message-copy-button"
                >
                    {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
                </button>
            )}
            <span
                className="text-xs text-t4 tabular-nums select-none transition-colors duration-fast group-hover:text-t3"
                data-testid="message-timestamp"
            >
                {formatMessageTime(message.timestamp)}
            </span>
        </div>
    );
};

export default React.memo(MessageActions);
