/**
 * messageContent — 消息可复制内容提取工具
 *
 * 为消息 hover 操作条 (MessageActions) 提供:
 * - extractMessageText: 提取 text 块 Markdown 源码 (user/assistant) 或正文 (system)
 * - extractMessageImage / extractMessageImages: 提取第一个 / 全部 image 块
 * - hasCopyableContent: 判断消息是否存在可复制内容
 * - base64ToBlob / copyImageToClipboard: 图片复制到剪贴板 (含 URL 文本降级)
 * - copyMessageWithImageRefs: 全部文本 + 全部图片链接复制为纯文本 (粘贴回输入框续聊场景)
 */

import type { Message, ContentBlock } from '@/types';

type TextBlock = Extract<ContentBlock, { type: 'text' }>;
type ImageBlock = Extract<ContentBlock, { type: 'image' }>;

/** 可复制的图片信息（来自 image ContentBlock） */
export interface CopyableImage {
    base64Data?: string;
    url?: string;
    mediaType?: string;
}

/**
 * 提取消息中的文本内容。
 * - user/assistant: 过滤 content 数组中 type === 'text' 的块，join('\n')（Markdown 源码）
 * - system: 直接返回 content 字符串
 * - 其余类型 / 无文本: 返回 null
 */
export function extractMessageText(message: Message): string | null {
    if (message.type === 'system') {
        return message.content.trim().length > 0 ? message.content : null;
    }
    if (message.type === 'user' || message.type === 'assistant') {
        const joined = message.content
            .filter((b): b is TextBlock => b.type === 'text')
            .map(b => b.text)
            .join('\n');
        return joined.trim().length > 0 ? joined : null;
    }
    return null;
}

/**
 * 提取消息中的第一个图片块（仅 user/assistant 消息）。
 * 无图片时返回 null。
 */
export function extractMessageImage(message: Message): CopyableImage | null {
    if (message.type !== 'user' && message.type !== 'assistant') return null;
    const imageBlock = message.content.find(
        (b): b is ImageBlock => b.type === 'image'
    );
    if (!imageBlock) return null;
    return {
        base64Data: imageBlock.base64Data,
        url: imageBlock.url,
        mediaType: imageBlock.mediaType,
    };
}

/**
 * 提取消息中的全部图片块（仅 user/assistant 消息），按出现顺序返回。
 * 无 base64 也无 url 的空图片块被过滤；无图片时返回空数组。
 */
export function extractMessageImages(message: Message): CopyableImage[] {
    if (message.type !== 'user' && message.type !== 'assistant') return [];
    return message.content
        .filter((b): b is ImageBlock => b.type === 'image')
        .filter(b => Boolean(b.base64Data || b.url))
        .map(b => ({
            base64Data: b.base64Data,
            url: b.url,
            mediaType: b.mediaType,
        }));
}

/**
 * 判断消息是否存在可复制内容（有文本或有图片）。
 * attachment / grouped_tool_use / collapsed_read_search / visualization 类型固定返回 false。
 */
export function hasCopyableContent(message: Message): boolean {
    if (extractMessageText(message) !== null) return true;
    const image = extractMessageImage(message);
    return image !== null && Boolean(image.base64Data || image.url);
}

/**
 * 纯 base64 字符串转 Blob（atob 手动转换，避免 data: URL fetch 的环境差异）。
 */
export function base64ToBlob(base64: string, mediaType: string): Blob {
    const byteString = atob(base64);
    const bytes = new Uint8Array(byteString.length);
    for (let i = 0; i < byteString.length; i++) {
        bytes[i] = byteString.charCodeAt(i);
    }
    return new Blob([bytes], { type: mediaType });
}

/**
 * 将图片复制到剪贴板。
 *
 * 策略:
 * 1. base64 → Blob（无 base64 时尝试 fetch url）
 * 2. Blob 经 navigator.clipboard.write([ClipboardItem]) 写入
 * 3. ClipboardItem 不支持 / 写入失败时降级为复制图片 URL 文本
 */
export async function copyImageToClipboard(image: CopyableImage): Promise<void> {
    const mediaType = image.mediaType || 'image/png';
    let blob: Blob | null = null;

    if (image.base64Data) {
        blob = base64ToBlob(image.base64Data, mediaType);
    } else if (image.url) {
        try {
            const res = await fetch(image.url);
            if (res.ok) {
                blob = await res.blob();
            }
        } catch {
            blob = null;
        }
    }

    const canWrite =
        blob !== null
        && typeof ClipboardItem !== 'undefined'
        && typeof navigator.clipboard?.write === 'function';

    if (blob && canWrite) {
        try {
            await navigator.clipboard.write([
                new ClipboardItem({ [blob.type || mediaType]: blob }),
            ]);
            return;
        } catch {
            // 降级到 URL 文本复制
        }
    }

    if (image.url && typeof navigator.clipboard?.writeText === 'function') {
        await navigator.clipboard.writeText(image.url);
        return;
    }

    throw new Error('copyImageToClipboard: no clipboard path available');
}

/**
 * 将消息的全部内容（全部文本 + 全部图片链接）复制为纯文本。
 *
 * 主要场景：复制后粘贴回 zhikuncode 输入框继续对话——
 * 图片以 URL 文字形式随行（agent 收到后自行下载查看），
 * 剪贴板不含图片文件，不触发输入框的图片附件粘贴逻辑，文字完整保留。
 *
 * 格式：文本原样（多 text 块 join 换行），随后每个图片一行：
 * - 有 URL：`[图片] https://...`（多图时 `[图片1]` `[图片2]` ...）
 * - 仅 base64 无 URL：`[图片]（内嵌图片，无链接）` 占位
 */
export async function copyMessageWithImageRefs(message: Message): Promise<void> {
    const text = extractMessageText(message) ?? '';
    const images = extractMessageImages(message);

    const parts: string[] = [];
    if (text) parts.push(text);
    images.forEach((image, index) => {
        const label = images.length > 1 ? `[图片${index + 1}]` : '[图片]';
        parts.push(image.url ? `${label} ${image.url}` : `${label}（内嵌图片，无链接）`);
    });
    const plainText = parts.join('\n');
    if (!plainText) {
        throw new Error('copyMessageWithImageRefs: nothing to copy');
    }
    if (typeof navigator.clipboard?.writeText !== 'function') {
        throw new Error('copyMessageWithImageRefs: clipboard.writeText unavailable');
    }
    await navigator.clipboard.writeText(plainText);
}
