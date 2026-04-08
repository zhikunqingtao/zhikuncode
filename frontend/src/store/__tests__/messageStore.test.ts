import { describe, it, expect, beforeEach } from 'vitest';
import { useMessageStore } from '../messageStore';
import type { Message } from '@/types';

describe('MessageStore', () => {
    beforeEach(() => {
        // Reset store state between tests
        useMessageStore.setState({
            messages: [],
            streamingMessageId: null,
            streamingContent: '',
            thinkingContent: '',
            activeToolCalls: new Map(),
        });
    });

    it('should start with empty messages', () => {
        const { messages } = useMessageStore.getState();
        expect(messages).toHaveLength(0);
    });

    it('addMessage appends to messages list', () => {
        const msg: Message = {
            uuid: 'msg-1',
            type: 'user',
            content: [{ type: 'text', text: 'Hello' }],
            timestamp: Date.now(),
        } as Message;

        useMessageStore.getState().addMessage(msg);
        const { messages } = useMessageStore.getState();
        expect(messages).toHaveLength(1);
        expect(messages[0].uuid).toBe('msg-1');
    });

    it('clearMessages resets messages and tool calls', () => {
        const msg: Message = {
            uuid: 'msg-1',
            type: 'user',
            content: [{ type: 'text', text: 'Hello' }],
            timestamp: Date.now(),
        } as Message;

        useMessageStore.getState().addMessage(msg);
        useMessageStore.getState().startToolCall('tc-1', 'BashTool', { command: 'ls' });
        
        useMessageStore.getState().clearMessages();
        const state = useMessageStore.getState();
        expect(state.messages).toHaveLength(0);
        expect(state.activeToolCalls.size).toBe(0);
    });

    it('appendStreamDelta creates streaming message and accumulates content', () => {
        useMessageStore.getState().appendStreamDelta('Hello ');
        useMessageStore.getState().appendStreamDelta('world!');
        
        const state = useMessageStore.getState();
        expect(state.streamingMessageId).toBeTruthy();
        expect(state.streamingContent).toBe('Hello world!');
        expect(state.messages).toHaveLength(1);
        expect(state.messages[0].type).toBe('assistant');
    });

    it('startToolCall/completeToolCall tracks tool execution', () => {
        useMessageStore.getState().startToolCall('tc-1', 'FileReadTool', { path: '/test.txt' });
        
        let state = useMessageStore.getState();
        expect(state.activeToolCalls.size).toBe(1);
        const tc = state.activeToolCalls.get('tc-1');
        expect(tc?.toolName).toBe('FileReadTool');
        expect(tc?.status).toBe('running');

        useMessageStore.getState().completeToolCall('tc-1', {
            content: 'file content',
            isError: false,
        });
        
        state = useMessageStore.getState();
        const completed = state.activeToolCalls.get('tc-1');
        expect(completed?.status).toBe('completed');
        expect(completed?.duration).toBeGreaterThanOrEqual(0);
    });

    it('rewindToMessage removes messages after specified ID', () => {
        const msgs: Message[] = [
            { uuid: 'msg-1', type: 'user', content: [], timestamp: 1 },
            { uuid: 'msg-2', type: 'assistant', content: [], timestamp: 2 },
            { uuid: 'msg-3', type: 'user', content: [], timestamp: 3 },
        ] as Message[];
        
        msgs.forEach(m => useMessageStore.getState().addMessage(m));
        expect(useMessageStore.getState().messages).toHaveLength(3);

        useMessageStore.getState().rewindToMessage('msg-1');
        expect(useMessageStore.getState().messages).toHaveLength(1);
        expect(useMessageStore.getState().messages[0].uuid).toBe('msg-1');
    });

    it('finalizeStream saves content to message and resets streaming state', () => {
        useMessageStore.getState().appendStreamDelta('Test response');
        
        useMessageStore.getState().finalizeStream({
            inputTokens: 100,
            outputTokens: 50,
            cacheReadInputTokens: 0,
            cacheCreationInputTokens: 0,
        });
        
        const state = useMessageStore.getState();
        expect(state.streamingMessageId).toBeNull();
        expect(state.streamingContent).toBe('');
        expect(state.messages).toHaveLength(1);
    });
});
