/**
 * AssistantMessage 终态渲染测试
 * 覆盖 FinalizedContent 的 tool_use fallback 状态推导:
 * 无 result 的工具不得误标 completed。
 */

import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import AssistantMessage from './AssistantMessage';
import type { Message, ToolCallState, ToolResult } from '@/types';

vi.mock('@/hooks/useTtsAvailability', () => ({
    useTtsAvailability: () => false,
}));

function assistantMessage(result?: ToolResult): Extract<Message, { type: 'assistant' }> {
    return {
        type: 'assistant',
        uuid: 'a-1',
        timestamp: Date.now(),
        stopReason: 'end_turn',
        usage: { inputTokens: 0, outputTokens: 0, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
        content: [{
            type: 'tool_use',
            toolUseId: 't-1',
            toolName: 'Bash',
            input: { command: 'ls' },
            ...(result ? { result } : {}),
        }],
    };
}

describe('AssistantMessage finalized tool_use fallback status', () => {
    it('无 result 的工具渲染为 Running，不误标 Completed', () => {
        render(<AssistantMessage message={assistantMessage()} />);
        expect(screen.getByText('Running')).toBeInTheDocument();
        expect(screen.queryByText('Completed')).not.toBeInTheDocument();
    });

    it('带成功 result 的工具渲染为 Completed', () => {
        render(<AssistantMessage message={assistantMessage({ content: 'ok', isError: false })} />);
        expect(screen.getByText('Completed')).toBeInTheDocument();
    });

    it('带错误 result 的工具渲染为 Error', () => {
        render(<AssistantMessage message={assistantMessage({ content: 'boom', isError: true })} />);
        expect(screen.getAllByText('Error').length).toBeGreaterThan(0);
        expect(screen.queryByText('Completed')).not.toBeInTheDocument();
    });

    it('activeToolCalls 中存在实时状态时优先使用实时状态', () => {
        const active = new Map<string, ToolCallState>([
            ['t-1', {
                toolName: 'Bash',
                input: { command: 'ls' },
                status: 'completed',
                result: { content: 'ok', isError: false },
                startTime: 0,
                duration: 5,
            }],
        ]);
        render(<AssistantMessage message={assistantMessage()} activeToolCalls={active} />);
        expect(screen.getByText('Completed')).toBeInTheDocument();
        expect(screen.queryByText('Running')).not.toBeInTheDocument();
    });
});
