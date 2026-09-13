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
 * §7.2 AI 消息（Demo-A 卡式）：渐变 accent 方块头像（30px rounded-lg）+
 * Card 容器（bg-surfacev2 + border-hairline + rounded-panel + shadow-e2，
 * padding 18×20，行高 1.75）。
 * 运行状态（§7.2/§10.2）：呼吸点 animate-accent-pulse + 文本 + tabular-nums 计时，
 * 容器 aria-live="polite"，reduced-motion 静态呈现。
 *
 * 流式更新: 当消息正在流式接收时，显示 streamingContent/thinkingContent
 * 并附加闪烁光标。完成后从 message.content 渲染最终内容。
 */

import React, { useEffect, useState } from 'react';
import { Bot } from 'lucide-react';
import type { Message, ContentBlock, ToolCallState } from '@/types';
import TextBlock from './TextBlock';
import ThinkingBlock from './ThinkingBlock';
import ToolCallBlock from './ToolCallBlock';
import ImageBlock from './ImageBlock';
import TtsPlayButton from './TtsPlayButton';
import MessageActions from './MessageActions';
import { useStreamingText } from '@/hooks/useStreamingText';
import { useTtsAvailability } from '@/hooks/useTtsAvailability';

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
    const ttsAvailable = useTtsAvailability();
    const plainText = message.content
        .filter(b => b.type === 'text')
        .map(b => (b as Extract<ContentBlock, { type: 'text' }>).text)
        .join('\n')
        .trim();

    return (
        <div className="assistant-message group flex gap-3 px-4 py-3">
            {/* Avatar（§7.2：渐变 accent 方块 30px rounded-lg） */}
            <div className="flex-shrink-0 w-[30px] h-[30px] rounded-lg bg-gradient-to-br from-accent2 to-accent2-strong shadow-e1 flex items-center justify-center">
                <Bot size={16} className="text-white" />
            </div>

            {/* Card 容器（§7.2：surface + hairline + rounded-panel + shadow-e2，px-18/py-20） */}
            <div className="flex-1 min-w-0 rounded-panel border border-hairline bg-surfacev2 shadow-e2 px-[18px] py-5">
                <div className="flex items-center gap-1.5 mb-2 text-xs text-t3 font-medium">
                    <span>Assistant</span>
                    {ttsAvailable && !isStreaming && plainText && (
                        <TtsPlayButton messageId={message.uuid} text={plainText} />
                    )}
                </div>

                <div className="text-sm text-t1 leading-[1.75]">
                    {isStreaming ? (
                        <StreamingContent
                            since={message.timestamp}
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

                {/* 操作行（ghost 小钮 + 顶部 hairline） */}
                <MessageActions message={message} isStreaming={isStreaming} />
            </div>
        </div>
    );
};

// ==================== 运行状态指示（§7.2 呼吸点 + 文本 + 计时） ====================

const RunningIndicator: React.FC<{ label: string; since: number }> = ({ label, since }) => {
    const [now, setNow] = useState(() => Date.now());
    useEffect(() => {
        const timer = window.setInterval(() => setNow(Date.now()), 1000);
        return () => window.clearInterval(timer);
    }, []);
    const elapsed = Math.max(0, Math.floor((now - since) / 1000));
    const mm = String(Math.floor(elapsed / 60)).padStart(2, '0');
    const ss = String(elapsed % 60).padStart(2, '0');
    return (
        <div className="flex items-center gap-2 text-sm text-t3" aria-live="polite">
            <span className="inline-block h-2 w-2 rounded-full bg-accent2 animate-accent-pulse motion-reduce:animate-none" />
            <span>{label}</span>
            <span className="tabular-nums text-t4">{mm}:{ss}</span>
        </div>
    );
};

// ==================== Streaming Mode ====================

interface StreamingContentProps {
    since: number;
    streamingContent?: string;
    thinkingContent?: string;
    activeToolCalls?: Map<string, ToolCallState>;
}

const StreamingContent: React.FC<StreamingContentProps> = ({
    since,
    streamingContent,
    thinkingContent,
    activeToolCalls,
}) => {
    // 使用外部高性能 streaming store 获取实时文本（绕过 Immer 开销）
    const externalStreamingText = useStreamingText();
    const displayText = externalStreamingText || streamingContent;

    return (
    <>
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

        {/* 运行状态：呼吸点 + 文本 + tabular-nums 计时（禁止无文案裸转圈） */}
        {!displayText && !thinkingContent && (!activeToolCalls || activeToolCalls.size === 0) && (
            <RunningIndicator label="Thinking..." since={since} />
        )}
    </>
    );
};

// ==================== Finalized Mode ====================

interface FinalizedContentProps {
    blocks: ContentBlock[];
    activeToolCalls?: Map<string, ToolCallState>;
}

const FinalizedContent: React.FC<FinalizedContentProps> = ({ blocks, activeToolCalls }) => (
    <>
        {blocks.map((block, i) => (
            <AssistantBlockRenderer
                key={i}
                block={block}
                activeToolCalls={activeToolCalls}
            />
        ))}
    </>
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
            // P1 兑底：activeToolCalls 命中但 input 为空对象时，回退使用 block.input
            const activeInputEmpty = !!state
                && state.input != null
                && typeof state.input === 'object'
                && !Array.isArray(state.input)
                && Object.keys(state.input as Record<string, unknown>).length === 0;
            const tc: ToolCallState = state
                ? { ...state, input: activeInputEmpty ? block.input : state.input }
                : {
                    toolName: block.toolName,
                    input: block.input,
                    // 无 result 的工具仍在执行中，不得误标 completed
                    status: block.result ? (block.result.isError ? 'error' : 'completed') : 'running',
                    result: block.result,
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
                result: { content: block.content, isError: block.isError, metadata: block.metadata },
                startTime: 0,
            };
            return <ToolCallBlock toolUseId={block.toolUseId} toolCall={tc} />;
        }
        case 'image':
            return <ImageBlock base64Data={block.base64Data} src={block.url} mediaType={block.mediaType} />;
        case 'server_tool_use':
            return (
                <div className="text-xs text-t4 italic my-1">
                    Server tool: {block.toolName}
                </div>
            );
        default:
            return null;
    }
};

export default React.memo(AssistantMessage);
