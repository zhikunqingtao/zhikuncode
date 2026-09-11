/**
 * UserMessage — 用户消息渲染组件
 *
 * SPEC: §8.2.1 UserMessage, §8.2.4J MessageType='user'
 * 渲染用户输入文本 + 附件预览。
 */

import React from 'react';
import { User } from 'lucide-react';
import type { Message, ContentBlock } from '@/types';
import TextBlock from './TextBlock';
import ImageBlock from './ImageBlock';
import MessageActions from './MessageActions';

interface UserMessageProps {
    message: Extract<Message, { type: 'user' }>;
}

const UserMessage: React.FC<UserMessageProps> = ({ message }) => {
    // 防御兜底：无可渲染块（如纯 tool_result 载体未被投影路径剔除）时不渲染头像行，避免空白 "You" 气泡
    const hasRenderableBlock = message.content.some(
        block => block.type === 'text' || block.type === 'image',
    );
    if (!hasRenderableBlock) return null;

    return (
        <div className="user-message group flex gap-3 px-4 py-3">
            {/* Avatar */}
            <div className="flex-shrink-0 w-7 h-7 rounded-full bg-blue-600 flex items-center justify-center">
                <User size={14} className="text-white" />
            </div>

            {/* Content */}
            <div className="flex-1 min-w-0">
                <div className="flex items-center justify-between mb-1">
                    <div className="text-xs text-[var(--text-secondary)] font-medium">You</div>
                    <MessageActions message={message} />
                </div>
                <div className="text-sm text-[var(--text-primary)]">
                    {message.content.map((block, i) => (
                        <ContentBlockRenderer key={i} block={block} />
                    ))}
                </div>
            </div>
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
