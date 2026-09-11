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

    it('atomically replaces the transient run with the committed generic message tail', () => {
        const objectKey = 'zhikuncode-artifacts/session/artifact/live.html';
        const url = `https://zhikunshare.oss-cn-beijing.aliyuncs.com/${objectKey}`;
        useMessageStore.setState({
            messages: [
                { uuid: 'history-anchor', type: 'assistant', content: [{ type: 'text', text: 'before' }], timestamp: 1 },
                { uuid: 'provisional-user', type: 'user', content: [{ type: 'text', text: 'upload it' }], timestamp: 2 },
            ] as Message[],
        });
        useMessageStore.getState().appendStreamDelta('temporary final text');
        useMessageStore.getState().startToolCall('bash-live', 'Bash', { command: 'pwd' });
        useMessageStore.getState().completeToolCall('bash-live', {
            content: '/app/workspace',
            isError: false,
        });
        useMessageStore.getState().startToolCall('publish-live', 'PublishArtifact', {
            file_path: '/app/workspace/live.html',
        });
        useMessageStore.getState().completeToolCall('publish-live', {
            content: '{"status":"published"}',
            isError: false,
            metadata: {
                structuredResult: {
                    schema: 'external-resource/v1', kind: 'download', provider: 'oss',
                    url, label: 'live.html', size: 12, sha256: 'b'.repeat(64), objectKey,
                    mimeType: 'text/html', permanentlyPublic: true, downloadExpected: true,
                },
            },
        });

        const committedTail: Message[] = [
            {
                uuid: 'committed-user', type: 'user',
                content: [{ type: 'text', text: 'upload it' }], timestamp: 3,
            },
            {
                uuid: 'committed-tools', type: 'assistant', timestamp: 4,
                content: [
                    { type: 'tool_use', toolUseId: 'bash-live', toolName: 'Bash', input: { command: 'pwd' } },
                    { type: 'tool_use', toolUseId: 'publish-live', toolName: 'PublishArtifact', input: { file_path: '/app/workspace/live.html' } },
                ],
            },
            {
                uuid: 'committed-results', type: 'user', timestamp: 5,
                content: [
                    { type: 'tool_result', toolUseId: 'bash-live', content: '/app/workspace', isError: false },
                    {
                        type: 'tool_result', toolUseId: 'publish-live',
                        content: '{"status":"published"}', isError: false,
                        metadata: {
                            structuredResult: {
                                schema: 'external-resource/v1', kind: 'download', provider: 'oss',
                                url, label: 'live.html', size: 12, sha256: 'b'.repeat(64), objectKey,
                                mimeType: 'text/html', permanentlyPublic: true, downloadExpected: true,
                            },
                        },
                    },
                ],
            },
            {
                uuid: 'committed-final', type: 'assistant',
                content: [{ type: 'text', text: '上传成功，请使用下载卡片。' }], timestamp: 6,
            },
        ] as Message[];

        expect(useMessageStore.getState().reconcileCommittedRun('history-anchor', committedTail)).toBe(true);

        const state = useMessageStore.getState();
        // committed-results 是纯 tool_result 载体：结果已附加到 committed-tools 的 tool_use 上，载体消息本身被剔除
        expect(state.messages.map(message => message.uuid)).toEqual([
            'history-anchor', 'committed-user', 'committed-tools', 'committed-final',
        ]);
        expect(state.activeToolCalls.size).toBe(0);
        expect(state.streamingMessageId).toBeNull();
        const assistant = state.messages[2];
        expect(assistant.type).toBe('assistant');
        if (assistant.type !== 'assistant') throw new Error('expected assistant');
        const bash = assistant.content.find(block => block.type === 'tool_use' && block.toolUseId === 'bash-live');
        const publish = assistant.content.find(block => block.type === 'tool_use' && block.toolUseId === 'publish-live');
        expect(bash?.type === 'tool_use' && bash.result?.content).toBe('/app/workspace');
        expect(publish?.type === 'tool_use' && publish.result?.metadata?.structuredResult)
            .toMatchObject({ url, objectKey });

        // A duplicated completion frame is idempotent and cannot duplicate history.
        expect(useMessageStore.getState().reconcileCommittedRun('history-anchor', committedTail)).toBe(true);
        expect(useMessageStore.getState().messages.map(message => message.uuid)).toEqual([
            'history-anchor', 'committed-user', 'committed-tools', 'committed-final',
        ]);
    });

    it('keeps the live projection unchanged when the committed anchor is missing', () => {
        const liveMessages: Message[] = [{
            uuid: 'current-history', type: 'assistant',
            content: [{ type: 'text', text: 'keep me' }], timestamp: 1,
        }] as Message[];
        useMessageStore.setState({ messages: liveMessages });
        useMessageStore.getState().startToolCall('running-tool', 'Bash', { command: 'pwd' });

        const reconciled = useMessageStore.getState().reconcileCommittedRun('missing-anchor', [{
            uuid: 'committed-new', type: 'assistant',
            content: [{ type: 'text', text: 'new' }], timestamp: 2,
        }] as Message[]);

        expect(reconciled).toBe(false);
        expect(useMessageStore.getState().messages).toEqual(liveMessages);
        expect(useMessageStore.getState().activeToolCalls.has('running-tool')).toBe(true);
    });

    it('replaces a provisional new-session projection when the committed anchor is null', () => {
        useMessageStore.setState({
            messages: [{
                uuid: 'provisional', type: 'user',
                content: [{ type: 'text', text: 'draft' }], timestamp: 1,
            }] as Message[],
        });
        const committed: Message[] = [{
            uuid: 'durable', type: 'user',
            content: [{ type: 'text', text: 'saved' }], timestamp: 2,
        }] as Message[];

        expect(useMessageStore.getState().reconcileCommittedRun(null, committed)).toBe(true);
        expect(useMessageStore.getState().messages).toEqual(committed);
    });

    it('restores new structured results onto their tool call without creating active calls', () => {
        const objectKey = 'zhikuncode-artifacts/session/artifact/file.html';
        const url = `https://zhikunshare.oss-cn-beijing.aliyuncs.com/${objectKey}`;
        const messages: Message[] = [
            {
                uuid: 'assistant-1',
                type: 'assistant',
                content: [{
                    type: 'tool_use',
                    toolUseId: 'publish-1',
                    toolName: 'PublishArtifact',
                    input: { file_path: 'file.html' },
                }],
                timestamp: 1,
            },
            {
                uuid: 'user-1',
                type: 'user',
                content: [{
                    type: 'tool_result',
                    toolUseId: 'publish-1',
                    content: '{"status":"published"}',
                    isError: false,
                    metadata: {
                        structuredResult: {
                            schema: 'external-resource/v1',
                            kind: 'download',
                            provider: 'oss',
                            url,
                            label: 'file.html',
                            size: 12,
                            sha256: 'a'.repeat(64),
                            objectKey,
                            mimeType: 'text/html',
                            permanentlyPublic: true,
                            downloadExpected: true,
                        },
                    },
                }],
                timestamp: 2,
            },
        ] as Message[];

        useMessageStore.getState().restoreSessionSnapshot(messages, []);

        const state = useMessageStore.getState();
        const assistant = state.messages[0];
        expect(assistant.type).toBe('assistant');
        if (assistant.type !== 'assistant') throw new Error('expected assistant');
        const toolUse = assistant.content[0];
        expect(toolUse.type).toBe('tool_use');
        if (toolUse.type !== 'tool_use') throw new Error('expected tool_use');
        expect(toolUse.result?.metadata?.structuredResult).toMatchObject({ url, objectKey });
        expect(state.activeToolCalls.size).toBe(0);
        // user-1 是纯 tool_result 载体，已被剔除，不产生空白 "You" 气泡
        expect(state.messages.map(message => message.uuid)).toEqual(['assistant-1']);
    });

});
