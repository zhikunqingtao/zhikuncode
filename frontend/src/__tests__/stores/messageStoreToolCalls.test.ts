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

    describe('错误路径 — error 条目迁移进消息内容（防跨 run 残留）', () => {
        it('failAll 标记 error 后 finalizeStream 迁移为带 isError result 的 tool_use block 并清空 map', () => {
            const s = useMessageStore.getState();
            s.appendStreamDelta('working');
            s.startToolCall('e-1', 'Bash', { command: 'sleep 100' });
            s.updateToolCallProgress('e-1', 'step 1');

            // 错误路径调用顺序（同 handleError）：先标记 error，再 finalize
            s.failAllRunningToolCalls('上游 Provider 错误');
            s.finalizeStream(USAGE);

            const state = useMessageStore.getState();
            // 迁移进消息内容：带合成 isError result 的 tool_use block
            const blocks = toolUseBlocks(state.messages[0]);
            expect(blocks).toHaveLength(1);
            expect(blocks[0]).toMatchObject({
                toolUseId: 'e-1',
                toolName: 'Bash',
                input: { command: 'sleep 100' },
                result: { content: '上游 Provider 错误', isError: true },
            });
            // 迁移后 activeToolCalls 无残留
            expect(state.activeToolCalls.size).toBe(0);
        });

        it('无流式消息时 error 条目直接清理，不跨 run 残留', () => {
            const s = useMessageStore.getState();
            // 无 appendStreamDelta/appendThinkingDelta → 无流式消息承载
            s.startToolCall('e-2', 'Read', { path: 'a.ts' });
            s.failAllRunningToolCalls('run 直接失败');
            s.finalizeStream(USAGE);

            const state = useMessageStore.getState();
            expect(state.activeToolCalls.size).toBe(0);
            expect(state.messages.filter(m => m.type === 'assistant')).toHaveLength(0);
        });

        it('两轮 run：第一轮错误迁移清理后，第二轮 map 与新消息均不含旧工具条目', () => {
            // 第一轮：流式 + running 工具 → 错误路径
            let s = useMessageStore.getState();
            s.appendStreamDelta('round 1');
            s.startToolCall('r1-tool', 'Grep', { pattern: 'foo' });
            s.failAllRunningToolCalls('第一轮失败');
            s.finalizeStream(USAGE);
            expect(useMessageStore.getState().activeToolCalls.has('r1-tool')).toBe(false);

            // 第二轮：新流式消息 + 新工具调用
            s = useMessageStore.getState();
            s.appendStreamDelta('round 2');
            s.startToolCall('r2-tool', 'Bash', { command: 'echo hi' });

            // 流式渲染（StreamingContent 遍历 activeToolCalls）仅含第二轮条目，旧卡片无残留
            let state = useMessageStore.getState();
            expect(Array.from(state.activeToolCalls.keys())).toEqual(['r2-tool']);

            // 第二轮 finalize 也不会把旧条目迁入新消息
            s.finalizeStream(USAGE);
            state = useMessageStore.getState();
            const assistantMsgs = state.messages.filter(m => m.type === 'assistant');
            expect(assistantMsgs).toHaveLength(2);
            expect(toolUseBlocks(assistantMsgs[1]).map(b => b.toolUseId)).toEqual(['r2-tool']);
            expect(toolUseBlocks(assistantMsgs[1])[0]?.result).toBeUndefined();
        });

        it('回归修复：block 已提交进消息后，error 兜底删除前补写合成 result，不永久转圈', () => {
            const s = useMessageStore.getState();
            // 第一次局部提交：running 条目的 tool_use block（无 result）写入消息，条目保留 map（既有设计）
            s.appendStreamDelta('partial');
            s.startToolCall('r-tool', 'Bash', { command: 'sleep 100' });
            s.finalizeStream(USAGE);
            let state = useMessageStore.getState();
            expect(state.streamingMessageId).toBeNull();
            expect(toolUseBlocks(state.messages[0])).toHaveLength(1);
            expect(toolUseBlocks(state.messages[0])[0].result).toBeUndefined();
            expect(state.activeToolCalls.get('r-tool')?.status).toBe('running');

            // error 事件到达：消息早已 finalize，streamingMessageId 为 null，走兜底分支
            s.failAllRunningToolCalls('上游 Provider 错误');
            s.finalizeStream(USAGE);

            // block 获得合成 isError result（渲染终态错误而非回退 running 转圈），map 无残留
            state = useMessageStore.getState();
            const patched = toolUseBlocks(state.messages[0]).find(b => b.toolUseId === 'r-tool');
            expect(patched?.result).toMatchObject({ content: '上游 Provider 错误', isError: true });
            expect(state.activeToolCalls.has('r-tool')).toBe(false);
        });

        it('block 已有 result 时兜底分支不覆盖原 result', () => {
            useMessageStore.setState({
                messages: [{
                    type: 'assistant', uuid: 'a-existing', timestamp: 1, stopReason: 'tool_use', usage: USAGE,
                    content: [
                        {
                            type: 'tool_use', toolUseId: 'r-ok', toolName: 'Bash', input: {},
                            result: { content: '真实结果', isError: false },
                        },
                    ],
                }] as Message[],
            });
            const s = useMessageStore.getState();
            s.startToolCall('r-ok', 'Bash', {});
            s.failAllRunningToolCalls('新的失败');
            s.finalizeStream(USAGE);

            const state = useMessageStore.getState();
            const block = toolUseBlocks(state.messages[0]).find(b => b.toolUseId === 'r-ok');
            expect(block?.result).toMatchObject({ content: '真实结果', isError: false });
            expect(state.activeToolCalls.has('r-ok')).toBe(false);
        });

        it('消息中无对应 block 时兜底分支仍直接删除条目（原行为不变）', () => {
            useMessageStore.setState({
                messages: [{
                    type: 'assistant', uuid: 'a-other', timestamp: 1, stopReason: 'end_turn', usage: USAGE,
                    content: [{ type: 'text', text: 'unrelated' }],
                }] as Message[],
            });
            const s = useMessageStore.getState();
            s.startToolCall('r-orphan', 'Read', { path: 'a.ts' });
            s.failAllRunningToolCalls('run 失败');
            s.finalizeStream(USAGE);

            const state = useMessageStore.getState();
            expect(state.activeToolCalls.has('r-orphan')).toBe(false);
            // 无关消息内容不被篡改
            const msg = state.messages[0];
            expect(msg.type === 'assistant' && msg.content).toHaveLength(1);
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
