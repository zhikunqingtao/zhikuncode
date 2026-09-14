/**
 * assistantBlockRenderer — assistant 消息内容块的共享块级渲染器
 *
 * 从 AssistantMessage 的终态块路由原样抽取（加法式，语义零变化），供两条
 * 渲染路径复用同一套块级分发，避免逻辑发散：
 * - AssistantMessage（legacy detailed 平铺路径 + 轮内流式消息的终态渲染）；
 * - turn/TurnContent（轮次分组路径 flattenTurnBlocks 的 blocks 段原位渲染：
 *   text→TextBlock、thinking→ThinkingBlock、image→ImageBlock、
 *   server_tool_use→斜体占位、孤儿 tool_result→合成 ToolCallBlock）。
 *
 * tool_use ↔ 结果/实时状态配对统一走 toolCallState.resolveToolCallState
 * （实时优先，空 input 回退 block.input；无 result 视为 running）。
 */

import React from 'react';
import type { ContentBlock, ToolCallState } from '@/types';
import TextBlock from './TextBlock';
import ThinkingBlock from './ThinkingBlock';
import ToolCallBlock from './ToolCallBlock';
import ImageBlock from './ImageBlock';
import { resolveToolCallState } from './toolCallState';

export interface AssistantBlockRendererProps {
    block: ContentBlock;
    activeToolCalls?: Map<string, ToolCallState>;
}

export const AssistantBlockRenderer: React.FC<AssistantBlockRendererProps> = ({ block, activeToolCalls }) => {
    switch (block.type) {
        case 'text':
            return <TextBlock text={block.text} />;
        case 'thinking':
            return <ThinkingBlock content={block.thinking} />;
        case 'redacted_thinking':
            return <ThinkingBlock content="" redacted />;
        case 'tool_use': {
            // 配对逻辑在 toolCallState.resolveToolCallState（语义不变）：
            // activeToolCalls 命中以实时状态为准（空 input 回退 block.input），
            // 未命中由 block 合成（有 result 判终态，无 result 视为 running）
            const tc = resolveToolCallState(block, activeToolCalls);
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
