/**
 * UserMessage — 用户消息渲染组件
 *
 * SPEC: §8.2.1 UserMessage, §8.2.4J MessageType='user'
 * 渲染用户输入文本 + 附件预览。
 *
 * §7.2 用户气泡（Demo-A）：bg-accent2-soft 底 + 1px accent2-ring 边 +
 * rounded-2xl rounded-br-md（20px 主圆角 + 6px 尾角）+ max-w-[76%] +
 * padding 11×17，右对齐；禁用高饱和实底。
 */

import React from 'react';
import type { Message, ContentBlock } from '@/types';
import TextBlock from './TextBlock';
import ImageBlock from './ImageBlock';
import MessageActions from './MessageActions';

interface UserMessageProps {
    message: Extract<Message, { type: 'user' }>;
}

const UserMessage: React.FC<UserMessageProps> = ({ message }) => {
    // 防御兜底：无可渲染块（如纯 tool_result 载体未被投影路径剔除）时不渲染，避免空气泡
    const hasRenderableBlock = message.content.some(
        block => block.type === 'text' || block.type === 'image',
    );
    if (!hasRenderableBlock) return null;

    return (
        <div className="user-message group flex flex-col items-end px-4 py-3">
            {/* Label */}
            <div className="mb-1 text-xs font-medium text-t3">You</div>

            {/* Bubble（§7.2：soft 底 + ring 边 + 尾角 6px，右对齐） */}
            <div className="max-w-[76%] rounded-2xl rounded-br-md border border-accent2-ring bg-accent2-soft px-[17px] py-[11px] text-sm text-t1">
                {message.content.map((block, i) => (
                    <ContentBlockRenderer key={i} block={block} />
                ))}
            </div>

            {/* 操作行（ghost 小钮 + 顶部 hairline，见 MessageActions） */}
            <MessageActions message={message} className="self-stretch" />
        </div>
    );
};

const ContentBlockRenderer: React.FC<{ block: ContentBlock }> = ({ block }) => {
    switch (block.type) {
        case 'text':
            return <TextBlock text={block.text} />;
        case 'image':
            return <ImageBlock base64Data={block.base64Data} src={block.url} mediaType={block.mediaType} />;
        default:
            return null;
    }
};

export default React.memo(UserMessage);
