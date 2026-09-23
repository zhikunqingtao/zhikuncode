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
 * - 图文混合消息额外渲染"复制全部"按钮 (CopyPlus 图标 + 文字，"运行中"同款
 *   soft 徽章：bg-accent2-soft + border-accent2-ring + text-accent2-ink)，
 *   复制「全部文本 + 全部图片链接」纯文本；粘贴回输入框时图片以 URL 文字随行，
 *   由 agent 自行下载查看，不触发输入框的图片附件粘贴逻辑
 */

import React, { useCallback, useState } from 'react';
import { Copy, CopyPlus, Check } from 'lucide-react';
import type { Message } from '@/types';
import { formatMessageTime } from '@/utils/datetime';
import { cn } from '@/components/ui/cn';
import {
    extractMessageText,
    extractMessageImage,
    extractMessageImages,
    hasCopyableContent,
    copyImageToClipboard,
    copyMessageWithImageRefs,
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
    const [copyAllState, setCopyAllState] = useState<'idle' | 'copied' | 'failed'>('idle');

    const copyable = !isStreaming && hasCopyableContent(message);
    // 「复制全部」仅在图文混合时显示：纯文本消息与普通复制等价，纯图片消息无文本可带
    const copyAllable = !isStreaming
        && extractMessageText(message) !== null
        && extractMessageImages(message).length > 0;

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

    const handleCopyAll = useCallback(async () => {
        try {
            await copyMessageWithImageRefs(message);
            setCopyAllState('copied');
        } catch {
            setCopyAllState('failed');
        }
        setTimeout(() => setCopyAllState('idle'), COPY_ICON_RESET_MS);
    }, [message]);

    return (
        <div
            className={cn(
                'mt-2 flex items-center justify-end gap-2 border-t border-hairline pt-1.5',
                className,
            )}
        >
            {copyAllable && (
                <button
                    type="button"
                    onClick={handleCopyAll}
                    className={cn(
                        'inline-flex h-[32px] shrink-0 items-center justify-center gap-1 rounded-full border px-2.5 transition-colors duration-fast',
                        'focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent2-ink',
                        copyAllState === 'failed'
                            ? 'border-err bg-errsoft text-err'
                            : 'border-accent2-ring bg-accent2-soft text-accent2-ink hover:brightness-105',
                    )}
                    title={copyAllState === 'copied' ? '已复制全部内容' : copyAllState === 'failed' ? '复制失败' : '复制全部内容（含图片链接）'}
                    aria-label={copyAllState === 'copied' ? '已复制全部内容' : copyAllState === 'failed' ? '复制失败' : '复制全部内容（含图片链接）'}
                    data-testid="message-copy-all-button"
                >
                    {copyAllState === 'copied' ? <Check className="h-[18px] w-[18px]" /> : <CopyPlus className="h-[18px] w-[18px]" />}
                    <span className="text-[13px] font-medium leading-none">
                        {copyAllState === 'copied' ? '已复制' : copyAllState === 'failed' ? '复制失败' : '复制全部'}
                    </span>
                </button>
            )}
            {copyable && (
                <button
                    type="button"
                    onClick={handleCopy}
                    className="message-action-button inline-flex shrink-0 items-center justify-center rounded-[10px] text-t4 transition-colors duration-fast group-hover:text-t2 hover:bg-hover2 hover:!text-t1"
                    title={copied ? '已复制' : '复制'}
                    aria-label={copied ? '已复制' : '复制'}
                    data-testid="message-copy-button"
                >
                    {copied ? <Check className="h-[18px] w-[18px]" /> : <Copy className="h-[18px] w-[18px]" />}
                </button>
            )}
            <span
                className="text-[13px] text-t4 tabular-nums select-none transition-colors duration-fast group-hover:text-t3"
                data-testid="message-timestamp"
            >
                {formatMessageTime(message.timestamp)}
            </span>
        </div>
    );
};

export default React.memo(MessageActions);
