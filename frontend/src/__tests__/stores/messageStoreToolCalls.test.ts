/**
 * messageStore 工具调用生命周期测试
 * 覆盖流式→终态切换 (finalizeStream)、权威消息对账 (reconcileCommittedRun)
 * 以及未知 toolUseId 的告警行为。
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useMessageStore } from '@/store/messageStore';
import type { ContentBlock, Message, Usage } from '@/types';

const USAGE: Usage = {
    inputTokens: 0,
    outputTokens: 0,
    cacheReadInputTokens: 0,
    cacheCreationInputTokens: 0,
};

function toolUseBlocks(message: Message | undefined) {
    if (!message || message.type !== 'assistant') return [];
    return message.content.filter(
        (b): b is Extract<ContentBlock, { type: 'tool_use' }> => b.type === 'tool_use',
    );
}

describe('messageStore tool call lifecycle', () => {
    beforeEach(() => {
        useMessageStore.getState().clearMessages();
    });

    describe('finalizeStream — 工具条目迁移 (1a)', () => {
        it('将已完成的工具调用迁移为带完整 input/result 的 tool_use block', () => {
            const s = useMessageStore.getState();
            s.appendStreamDelta('Working on it');
            s.startToolCall('tool-1', 'Bash', {});
            s.updateToolCallInput('tool-1', { command: 'ls -la' });
            s.completeToolCall('tool-1', { content: 'file.txt', isError: false });
            s.finalizeStream(USAGE);

            const state = useMessageStore.getState();
            expect(state.streamingMessageId).toBeNull();
            const blocks = toolUseBlocks(state.messages[0]);
            expect(blocks).toHaveLength(1);
            expect(blocks[0]).toMatchObject({
                toolUseId: 'tool-1',
                toolName: 'Bash',
                input: { command: 'ls -la' },
                result: { content: 'file.txt', isError: false },
            });
            // 已带 result 的条目迁移后从 activeToolCalls 清理
            expect(state.activeToolCalls.has('tool-1')).toBe(false);
        });

        it('running 条目迁移为无 result 的 block 并保留在 activeToolCalls，迟到的 tool_result 仍可关联', () => {
            const s = useMessageStore.getState();
            s.appendStreamDelta('running tool');
            s.startToolCall('tool-2', 'Read', { path: 'a.ts' });
            s.finalizeStream(USAGE);

            let state = useMessageStore.getState();
            const blocks = toolUseBlocks(state.messages[0]);
            expect(blocks).toHaveLength(1);
            expect(blocks[0]).toMatchObject({ toolUseId: 'tool-2', toolName: 'Read', input: { path: 'a.ts' } });
            expect(blocks[0].result).toBeUndefined();
            expect(state.activeToolCalls.get('tool-2')?.status).toBe('running');

            state.completeToolCall('tool-2', { content: 'done', isError: false });
            state = useMessageStore.getState();
            expect(state.activeToolCalls.get('tool-2')?.status).toBe('completed');
            expect(state.activeToolCalls.get('tool-2')?.result?.content).toBe('done');
        });

        it('文本与工具混合时同时保留 text block 与 tool_use block', () => {
            const s = useMessageStore.getState();
            s.appendStreamDelta('Hello');
            s.startToolCall('tool-3', 'Grep', { pattern: 'foo' });
            s.completeToolCall('tool-3', { content: 'match', isError: false });
            s.finalizeStream(USAGE);

            const msg = useMessageStore.getState().messages[0] as Extract<Message, { type: 'assistant' }>;
            expect(msg.type).toBe('assistant');
            const content = msg.content;
            expect(content.some(b => b.type === 'text' && b.text === 'Hello')).toBe(true);
            expect(toolUseBlocks(msg)).toHaveLength(1);
        });
    });

    describe('reconcileCommittedRun — 精准清理 (1b)', () => {
        it('只清除 committed messages 中已带 result 的工具条目，running 条目保留', () => {
            const s = useMessageStore.getState();
            s.startToolCall('t-done', 'Bash', { command: 'pwd' });
            s.startToolCall('t-running', 'Bash', { command: 'sleep 100' });
            s.completeToolCall('t-done', { content: '/tmp', isError: false });

            const committed: Message[] = [
                {
                    type: 'assistant', uuid: 'a-1', timestamp: 1, stopReason: 'tool_use', usage: USAGE,
                    content: [
                        { type: 'tool_use', toolUseId: 't-done', toolName: 'Bash', input: { command: 'pwd' } },
                        { type: 'tool_use', toolUseId: 't-running', toolName: 'Bash', input: { command: 'sleep 100' } },
                    ],
                },
                {
                    type: 'user', uuid: 'u-1', timestamp: 2,
                    content: [
                        { type: 'tool_result', toolUseId: 't-done', content: '/tmp', isError: false },
                    ],
                },
            ];

            expect(useMessageStore.getState().reconcileCommittedRun(null, committed)).toBe(true);
            const state = useMessageStore.getState();
            expect(state.activeToolCalls.has('t-done')).toBe(false);
            expect(state.activeToolCalls.has('t-running')).toBe(true);
            // u-1 纯 tool_result 载体被剔除；t-done 的 result 已附加到 a-1 的 tool_use 上
            expect(state.messages.map(message => message.uuid)).toEqual(['a-1']);
            const doneToolUse = toolUseBlocks(state.messages[0]).find(b => b.toolUseId === 't-done');
            expect(doneToolUse?.result?.content).toBe('/tmp');

            // 后续到达的 tool_result 仍能通过 completeToolCall 关联
            state.completeToolCall('t-running', { content: 'ok', isError: false });
            expect(useMessageStore.getState().activeToolCalls.get('t-running')?.status).toBe('completed');
        });
    });

    describe('failAllRunningToolCalls — 错误路径终止 running 工具', () => {
        it('running 条目标记为 error 并携带错误消息，input/result 保留', () => {
            const s = useMessageStore.getState();
            s.startToolCall('f-running', 'Bash', { command: 'sleep 100' });
            s.updateToolCallProgress('f-running', 'step 1');

            s.failAllRunningToolCalls('上游 Provider 错误');

            const state = useMessageStore.getState();
            const failed = state.activeToolCalls.get('f-running');
            expect(failed?.status).toBe('error');
            expect(failed?.error).toBe('上游 Provider 错误');
            expect(failed?.input).toEqual({ command: 'sleep 100' });
            expect(failed?.progress).toBe('step 1');
            expect(typeof failed?.duration).toBe('number');
        });

        it('已有终态（completed）的条目不受影响', () => {
            const s = useMessageStore.getState();
            s.startToolCall('f-done', 'Read', { path: 'a.ts' });
            s.completeToolCall('f-done', { content: 'ok', isError: false });

            s.failAllRunningToolCalls('上游 Provider 错误');

            const done = useMessageStore.getState().activeToolCalls.get('f-done');
            expect(done?.status).toBe('completed');
            expect(done?.error).toBeUndefined();
        });

        it('finalizeStream 后调用：running 条目仍被标记，避免永久转圈', () => {
            const s = useMessageStore.getState();
            s.appendStreamDelta('using tool');
            s.startToolCall('f-2', 'Grep', { pattern: 'foo' });
            s.finalizeStream(USAGE);

            // finalizeStream 后 running 条目保留在 activeToolCalls（等待迟到 tool_result）
            expect(useMessageStore.getState().activeToolCalls.get('f-2')?.status).toBe('running');

            useMessageStore.getState().failAllRunningToolCalls('run 终止');
            const failed = useMessageStore.getState().activeToolCalls.get('f-2');
            expect(failed?.status).toBe('error');
            expect(failed?.error).toBe('run 终止');
        });
    });

    describe('未知 toolUseId 告警 (1c)', () => {
        afterEach(() => {
            vi.restoreAllMocks();
        });

        it('updateToolCallInput 找不到条目时输出 console.warn 而非静默丢弃', () => {
            const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
            useMessageStore.getState().updateToolCallInput('missing-input-id', { a: 1 });
            expect(warnSpy).toHaveBeenCalledTimes(1);
            const warned = String(warnSpy.mock.calls[0][0]);
            expect(warned).toContain('tool_use_input');
            expect(warned).toContain('missing-input-id');
        });

        it('completeToolCall 找不到条目时输出 console.warn 而非静默丢弃', () => {
            const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
            useMessageStore.getState().completeToolCall('missing-result-id', { content: 'x', isError: false });
            expect(warnSpy).toHaveBeenCalledTimes(1);
            const warned = String(warnSpy.mock.calls[0][0]);
            expect(warned).toContain('tool_result');
            expect(warned).toContain('missing-result-id');
        });
    });
});
