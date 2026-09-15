import { beforeEach, describe, expect, it } from 'vitest';
import { dispatch, resetBoundSession } from '@/api/dispatch';
import { useMessageStore } from '../messageStore';
import { streamingStore, flushStreamingBuffer } from '@/hooks/useStreamingText';
import { buildTurnTaskSections, countToolUses } from '../selectors/turnSections';
import type { Message } from '@/types';

const segment = (uuid: string, toolId?: string): Extract<Message, { type: 'assistant' }> => ({
    type: 'assistant', uuid, timestamp: 1, stopReason: toolId ? 'tool_use' : 'end_turn',
    usage: { inputTokens: 1, outputTokens: 1, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
    content: [{ type: 'text', text: uuid }, ...(toolId ? [{ type: 'tool_use' as const, toolUseId: toolId, toolName: 'Read', input: {} }] : [])],
});
const toolStart = (id: string) => dispatch({ type: 'tool_use_start', toolUseId: id, toolName: 'Read', input: {} });
const toolResult = (id: string) => dispatch({ type: 'tool_result', toolUseId: id, content: 'done', isError: false });
const complete = (message: Extract<Message, { type: 'assistant' }>) => dispatch({ type: 'assistant_segment_complete', message });

beforeEach(() => {
    resetBoundSession();
    useMessageStore.getState().clearMessages();
});

describe('assistant segment event sequences', () => {
    it.each([true, false])('preserves tool results arriving before/after segment completion (%s)', resultFirst => {
        dispatch({ type: 'stream_delta', messageId: 'legacy-delta', delta: 'working' });
        toolStart('read');
        expect(countToolUses(useMessageStore.getState().messages)).toBe(1);
        if (resultFirst) toolResult('read');
        complete(segment('first', 'read'));
        if (!resultFirst) toolResult('read');
        const first = useMessageStore.getState().messages[0];
        expect(first.type === 'assistant' && first.content.find(b => b.type === 'tool_use')).toMatchObject({ result: { content: 'done' } });
        dispatch({ type: 'task_boundary', message_id: 'boundary', task_id: 'B', title: 'B', seq: 1, ts: 10 });
        toolStart('next');
        expect(countToolUses(buildTurnTaskSections(useMessageStore.getState().messages).sections[0].messages)).toBe(1);
        complete(segment('second', 'next'));
        dispatch({ type: 'stream_delta', messageId: 'legacy-delta', delta: 'answer' });
        complete(segment('answer'));
        useMessageStore.getState().finalizeStream(segment('answer').usage);
        expect(useMessageStore.getState().messages.map(m => m.uuid)).toEqual(['first', 'boundary', 'second', 'answer']);
        expect(countToolUses([useMessageStore.getState().messages[3]])).toBe(0);
    });
    it('ignores repeated completion for sealing purposes and keeps the next streaming buffer', () => {
        complete(segment('first'));
        dispatch({ type: 'stream_delta', messageId: 'legacy-delta', delta: 'next text' });
        const nextId = useMessageStore.getState().streamingMessageId;
        complete(segment('first'));
        expect(useMessageStore.getState().streamingMessageId).toBe(nextId);
        flushStreamingBuffer();
        expect(streamingStore.getSnapshot()).toBe('next text');
    });
    it('uses UUID rather than seq for boundary deduplication across runs', () => {
        const event = { type: 'task_boundary' as const, message_id: 'b1', task_id: 'A', title: 'A', seq: 1 };
        dispatch(event); dispatch(event); dispatch({ ...event, message_id: 'b2' });
        expect(useMessageStore.getState().messages.map(m => m.uuid)).toEqual(['b1', 'b2']);
    });
    it('does not resurrect a completed tool when its start is replayed after reconciliation', () => {
        toolStart('read'); toolResult('read'); complete(segment('first', 'read'));
        useMessageStore.getState().finalizeStream(segment('first').usage);
        toolStart('read');
        expect(useMessageStore.getState().activeToolCalls.has('read')).toBe(false);
        expect(useMessageStore.getState().messages).toHaveLength(1);
    });
    it('steering sealing retains tools and final reconciliation replaces provisional messages', () => {
        dispatch({ type: 'stream_delta', messageId: 'legacy-delta', delta: 'working' });
        toolStart('read');
        useMessageStore.getState().finalizeAssistantSegment();
        expect(countToolUses(useMessageStore.getState().messages)).toBe(1);
        const authoritative = [segment('committed', 'read'), segment('answer')];
        expect(useMessageStore.getState().reconcileCommittedRun(null, authoritative)).toBe(true);
        expect(useMessageStore.getState().messages.map(m => m.uuid)).toEqual(['committed', 'answer']);
    });
});
