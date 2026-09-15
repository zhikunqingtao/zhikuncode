/**
 * UserMessage — 用户消息渲染组件
 *
 * SPEC: §8.2.1 UserMessage, §8.2.4J MessageType='user'
 * 渲染用户输入文本 + 附件预览。
 *
 * §7.2 用户气泡（Demo-A）：bg-accent2-soft 底 + 1px accent2-ring 边 +
 * rounded-2xl rounded-br-md（20px 主圆角 + 6px 尾角）+ max-w-[92%] sm:max-w-[76%] +
 * padding 11×17，右对齐；禁用高饱和实底。
 */

import React from 'react';
import { ChevronRight } from 'lucide-react';
import type { Message, ContentBlock } from '@/types';
import TextBlock from './TextBlock';
import ImageBlock from './ImageBlock';
import MessageActions from './MessageActions';

interface UserMessageProps {
    message: Extract<Message, { type: 'user' }>;
    /** 仅简洁档启用；其他调用保持完整气泡。 */
    disclosure?: { expanded: boolean; onToggle: () => void };
}

const UserMessage: React.FC<UserMessageProps> = ({ message, disclosure }) => {
    // 防御兜底：无可渲染块（如纯 tool_result 载体未被投影路径剔除）时不渲染，避免空气泡
    const hasRenderableBlock = message.content.some(
        block => block.type === 'text' || block.type === 'image',
    );
    if (!hasRenderableBlock) return null;
    const expanded = disclosure?.expanded ?? true;
    const preview = message.content.find(block => block.type === 'text' && block.text.trim());
    const summary = preview?.type === 'text' ? preview.text.trim().slice(0, 120) : '图片附件';

    return (
        <div className="user-message group flex flex-col items-end px-3 py-3 sm:px-4">
            {/* Label */}
            <div className="mb-1 text-[13px] font-medium text-t3">我</div>

            {/* Bubble（§7.2：soft 底 + ring 边 + 尾角 6px，右对齐） */}
            <div className="min-w-0 max-w-[92%] sm:max-w-[76%] [overflow-wrap:anywhere] rounded-[14px] rounded-br-md border border-accent2-ring bg-accent2-soft px-[17px] py-[11px] text-sm text-t1">
                {disclosure && (
                    <button type="button" aria-expanded={expanded} aria-label={`用户问题，点击${expanded ? '收起' : '展开'}`}
                        onClick={disclosure.onToggle}
                        className="panel-control flex min-h-11 w-full min-w-0 items-center gap-2 text-left focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring rounded-lg">
                        <span className="shrink-0 font-medium">用户问题</span>
                        {!expanded && <span className="min-w-0 flex-1 truncate text-[13px] text-t3">{summary}</span>}
                        <span className="ml-auto shrink-0 text-[13px] text-t2">{expanded ? '收起' : '展开'}</span>
                        <ChevronRight size={13} aria-hidden="true" className={`shrink-0 text-t3 ${expanded ? 'rotate-90' : ''}`} />
                    </button>
                )}
                {expanded && <>
                    {message.content.map((block, i) => (
                        <ContentBlockRenderer key={i} block={block} />
                    ))}
                    {/* 时间与操作属于这条问题，收入气泡内，不用横线切成两块。 */}
                    <MessageActions message={message} className="mt-1.5 flex-wrap gap-x-1.5 gap-y-0 border-t-0 pt-0" />
                </>}
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
