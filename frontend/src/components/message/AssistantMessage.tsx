/**
 * AssistantMessage — 助手消息渲染组件
 *
 * SPEC: §8.2.1 AssistantMessage, §8.2.4D 消息渲染管线
 * 渲染助手回复:
 * - StreamingText (流式文本 + 光标动画)
 * - ThinkingBlock (可折叠思考过程)
 * - ToolCallBlock (工具调用卡片)
 * - ImageBlock (图片内容块)
 *
 * 流式更新: 当消息正在流式接收时，显示 streamingContent/thinkingContent
 * 并附加闪烁光标。完成后从 message.content 渲染最终内容。
 */

import React from 'react';
import { Bot } from 'lucide-react';
import type { Message, ContentBlock, ToolCallState } from '@/types';
import TextBlock from './TextBlock';
import ThinkingBlock from './ThinkingBlock';
import ToolCallBlock from './ToolCallBlock';
import ImageBlock from './ImageBlock';
import { useStreamingText } from '@/hooks/useStreamingText';

interface AssistantMessageProps {
    message: Extract<Message, { type: 'assistant' }>;
    /** 是否正在流式接收此消息 */
    isStreaming?: boolean;
    /** 流式文本内容 (从 MessageStore.streamingContent) */
    streamingContent?: string;
    /** 流式思考内容 (从 MessageStore.thinkingContent) */
    thinkingContent?: string;
    /** 活跃的工具调用 (从 MessageStore.activeToolCalls) */
    activeToolCalls?: Map<string, ToolCallState>;
}

const AssistantMessage: React.FC<AssistantMessageProps> = ({
    message,
    isStreaming = false,
    streamingContent,
    thinkingContent,
    activeToolCalls,
}) => {
    return (
        <div className="assistant-message flex gap-3 px-4 py-3">
            {/* Avatar */}
            <div className="flex-shrink-0 w-7 h-7 rounded-full bg-purple-600 flex items-center justify-center">
                <Bot size={14} className="text-white" />
            </div>

            {/* Content */}
            <div className="flex-1 min-w-0">
                <div className="text-xs text-[var(--text-secondary)] mb-1 font-medium">Assistant</div>

                {isStreaming ? (
                    <StreamingContent
                        streamingContent={streamingContent}
                        thinkingContent={thinkingContent}
                        activeToolCalls={activeToolCalls}
                    />
                ) : (
                    <FinalizedContent
                        blocks={message.content}
                        activeToolCalls={activeToolCalls}
                    />
                )}
            </div>
        </div>
    );
};

// ==================== Streaming Mode ====================

interface StreamingContentProps {
    streamingContent?: string;
    thinkingContent?: string;
    activeToolCalls?: Map<string, ToolCallState>;
}

const StreamingContent: React.FC<StreamingContentProps> = ({
    streamingContent,
    thinkingContent,
    activeToolCalls,
}) => {
    // 使用外部高性能 streaming store 获取实时文本（绕过 Immer 开销）
    const externalStreamingText = useStreamingText();
    const displayText = externalStreamingText || streamingContent;

    return (
    <div className="text-sm text-[var(--text-primary)]">
        {/* Thinking (streaming) */}
        {thinkingContent && (
            <ThinkingBlock content={thinkingContent} streaming />
        )}

        {/* Text (streaming) */}
        {displayText && (
            <TextBlock text={displayText} streaming />
        )}

        {/* Active tool calls */}
        {activeToolCalls && activeToolCalls.size > 0 && (
            <div className="mt-1">
                {Array.from(activeToolCalls.entries()).map(([id, tc]) => (
                    <ToolCallBlock key={id} toolUseId={id} toolCall={tc} />
                ))}
            </div>
        )}

        {/* Show waiting indicator if nothing visible yet */}
        {!displayText && !thinkingContent && (!activeToolCalls || activeToolCalls.size === 0) && (
            <div className="flex items-center gap-2 text-[var(--text-muted)] text-sm">
                <span className="inline-block w-2 h-4 bg-purple-400 animate-pulse rounded-sm" />
                <span>Thinking...</span>
            </div>
        )}
    </div>
    );
};

// ==================== Finalized Mode ====================

interface FinalizedContentProps {
    blocks: ContentBlock[];
    activeToolCalls?: Map<string, ToolCallState>;
}

const FinalizedContent: React.FC<FinalizedContentProps> = ({ blocks, activeToolCalls }) => (
    <div className="text-sm text-[var(--text-primary)]">
        {blocks.map((block, i) => (
            <AssistantBlockRenderer
                key={i}
                block={block}
                activeToolCalls={activeToolCalls}
            />
        ))}
    </div>
);

// ==================== Block Router ====================

interface AssistantBlockRendererProps {
    block: ContentBlock;
    activeToolCalls?: Map<string, ToolCallState>;
}

const AssistantBlockRenderer: React.FC<AssistantBlockRendererProps> = ({ block, activeToolCalls }) => {
    switch (block.type) {
        case 'text':
            return <TextBlock text={block.text} />;
        case 'thinking':
            return <ThinkingBlock content={block.thinking} />;
        case 'redacted_thinking':
            return <ThinkingBlock content="" redacted />;
        case 'tool_use': {
            // Try to find state from activeToolCalls, fallback to basic info
            const state = activeToolCalls?.get(block.toolUseId);
            const tc: ToolCallState = state ?? {
                toolName: block.toolName,
                input: block.input,
                status: 'completed',
                startTime: 0,
            };
            return <ToolCallBlock toolUseId={block.toolUseId} toolCall={tc} />;
        }
        case 'tool_result': {
            // Tool results are displayed within their ToolCallBlock
            // Standalone rendering for cases where tool_use block is not adjacent
            const tc: ToolCallState = {
                toolName: 'Tool',
                input: {},
                status: block.isError ? 'error' : 'completed',
                result: { content: block.content, isError: block.isError },
                startTime: 0,
            };
            return <ToolCallBlock toolUseId={block.toolUseId} toolCall={tc} />;
        }
        case 'image':
            return <ImageBlock base64Data={block.base64Data} mediaType={block.mediaType} />;
        case 'server_tool_use':
            return (
                <div className="text-xs text-gray-500 italic my-1">
                    Server tool: {block.toolName}
                </div>
            );
        default:
            return null;
    }
};

export default React.memo(AssistantMessage);
