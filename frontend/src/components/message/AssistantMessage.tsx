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
import { BrandLogo } from '@/components/ui/BrandLogo';
import type { Message, ContentBlock, ToolCallState } from '@/types';
import TextBlock from './TextBlock';
import ThinkingBlock from './ThinkingBlock';
import ToolCallBlock from './ToolCallBlock';
import AssistantMessageActions from './AssistantMessageActions';
import { AssistantBlockRenderer } from './assistantBlockRenderer';
import { useStreamingText } from '@/hooks/useStreamingText';

interface AssistantMessageProps {
    /** 嵌入整轮助手卡片时，仅渲染正文与操作行。 */
    embedded?: boolean;
    hideActions?: boolean;
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
    embedded = false,
    hideActions = false,
    streamingContent,
    thinkingContent,
    activeToolCalls,
}) => {
    return (
        <div className={embedded ? "assistant-message group min-w-0" : "assistant-message group flex gap-3 px-4 py-3"}>
            {!embedded && <BrandLogo className="h-8 w-8" />}

            {/* Card 容器（§7.2：surface + hairline + rounded-panel + shadow-e2，px-18/py-20） */}
            <div className={embedded ? "min-w-0" : "flex-1 min-w-0 rounded-panel border border-hairline bg-surfacev2 shadow-e1 px-[18px] py-5"}>
                {!embedded && <div className="flex items-center gap-1.5 mb-2 text-[13px] text-t3 font-medium">
                    <span>zhikuncode</span>
                </div>}

                <div className="text-sm text-t1 leading-[1.75]">
                    {isStreaming ? (
                        <StreamingContent
                            since={message.timestamp}
                            streamingContent={streamingContent}
                            thinkingContent={thinkingContent}
                            activeToolCalls={activeToolCalls && new Map(
                                [...activeToolCalls].filter(([id]) => message.content.some(
                                    block => block.type === 'tool_use' && block.toolUseId === id)),
                            )}
                        />
                    ) : (
                        <FinalizedContent
                            blocks={message.content}
                            activeToolCalls={activeToolCalls}
                        />
                    )}
                </div>

                {/* 操作行（TTS + 复制 + 时间戳；与轮次分组路径共享 AssistantMessageActions） */}
                {!hideActions && <AssistantMessageActions message={message} isStreaming={isStreaming} />}
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

export default React.memo(AssistantMessage);
