/**
 * AssistantMessageActions — assistant 消息级操作行（共享组件）
 *
 * 从 AssistantMessage 抽取的消息级 chrome，供两条渲染路径复用，避免逻辑发散：
 * - AssistantMessage（detailed 平铺 / balanced 卡内整消息路径）：卡片底部操作行；
 * - TurnContent（轮次分组路径）：按 messageId 分组的 assistant blocks 段 footer。
 *
 * 行内容：
 * - TTS 朗读按钮（TTS 服务可用 + 非流式 + 有正文文本时，TtsPlayButton）；
 * - MessageActions（复制按钮 + 时分秒时间戳；复制规则与流式隐藏逻辑
 *   见 MessageActions / utils/messageContent）。
 *
 * 视觉：TTS 居左、复制/时间戳居右的单行 footer，顶部 hairline 分隔
 * （与 AssistantMessage 原操作行一致；外层可经 className 覆盖间距/边框）。
 */

import React from 'react';
import type { ContentBlock, Message } from '@/types';
import TtsPlayButton from './TtsPlayButton';
import MessageActions from './MessageActions';
import { useTtsAvailability } from '@/hooks/useTtsAvailability';
import { cn } from '@/components/ui/cn';

type AssistantMessage = Extract<Message, { type: 'assistant' }>;

export interface AssistantMessageActionsProps {
    message: AssistantMessage;
    /** 流式进行中：隐藏 TTS 与复制按钮（时间戳照常显示，与 AssistantMessage 一致） */
    isStreaming?: boolean;
    className?: string;
}

const AssistantMessageActions: React.FC<AssistantMessageActionsProps> = ({
    message,
    isStreaming = false,
    className,
}) => {
    const ttsAvailable = useTtsAvailability();
    const plainText = message.content
        .filter((b): b is Extract<ContentBlock, { type: 'text' }> => b.type === 'text')
        .map(b => b.text)
        .join('\n')
        .trim();

    return (
        <div
            className={cn(
                'mt-2 flex items-center gap-1 border-t border-hairline pt-1.5',
                className,
            )}
            data-testid="assistant-message-actions"
        >
            {ttsAvailable && !isStreaming && plainText && (
                <TtsPlayButton messageId={message.uuid} text={plainText} />
            )}
            {/* 内层 MessageActions 自带 mt/border-t/pt，嵌入本行时抹平，避免双重分隔线 */}
            <MessageActions
                message={message}
                isStreaming={isStreaming}
                className="mt-0 flex-1 border-t-0 pt-0"
            />
        </div>
    );
};

export default React.memo(AssistantMessageActions);
