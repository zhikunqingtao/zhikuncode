/**
 * MessageStore — 消息状态管理
 * SPEC: §8.3 Store #2
 * 持久化: 否 (从后端 session_restored 加载)
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type { Message, ToolResult, ToolCallState, Usage } from '@/types';
import { streamingStore, flushStreamingBuffer } from '@/hooks/useStreamingText';

export interface MessageStoreState {
    // 状态
    messages: Message[];
    streamingMessageId: string | null;
    streamingContent: string;
    thinkingContent: string;
    activeToolCalls: Map<string, ToolCallState>;

    // Actions
    addMessage: (msg: Message) => void;
    appendStreamDelta: (delta: string) => void;
    appendThinkingDelta: (delta: string) => void;
    startToolCall: (toolUseId: string, toolName: string, input: unknown) => void;
    updateToolCallProgress: (toolUseId: string, progress: string) => void;
    completeToolCall: (toolUseId: string, result: ToolResult) => void;
    finalizeStream: (usage: Usage) => void;
    clearMessages: () => void;
    rewindToMessage: (messageId: string) => void;
}

export const useMessageStore = create<MessageStoreState>()(
    subscribeWithSelector(immer((set) => ({
        messages: [],
        streamingMessageId: null,
        streamingContent: '',
        thinkingContent: '',
        activeToolCalls: new Map(),

        addMessage: (msg) => set(d => { d.messages.push(msg); }),
        appendStreamDelta: (delta) => set(d => {
            // 首次收到 stream_delta 时，创建占位 assistant 消息
            if (!d.streamingMessageId) {
                const msgId = crypto.randomUUID();
                d.streamingMessageId = msgId;
                d.messages.push({
                    uuid: msgId,
                    type: 'assistant',
                    content: [{ type: 'text', text: '' }],
                    timestamp: Date.now(),
                } as Message);
            }
            d.streamingContent += delta;
        }),
        appendThinkingDelta: (delta) => set(d => {
            // 首次收到 thinking_delta 时，也创建占位 assistant 消息
            if (!d.streamingMessageId) {
                const msgId = crypto.randomUUID();
                d.streamingMessageId = msgId;
                d.messages.push({
                    uuid: msgId,
                    type: 'assistant',
                    content: [],
                    timestamp: Date.now(),
                    stopReason: '',
                    usage: { inputTokens: 0, outputTokens: 0, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
                } as unknown as Message);
            }
            d.thinkingContent += delta;
            // 同步更新 streaming message 中的 thinking block
            const msg = d.messages.find(m => m.uuid === d.streamingMessageId);
            if (msg && msg.type === 'assistant' && Array.isArray((msg as any).content)) {
                const content = (msg as any).content;
                const thinkingBlock = content.find((b: any) => b.type === 'thinking' && !b.completed);
                if (thinkingBlock) {
                    thinkingBlock.text = d.thinkingContent;
                } else {
                    content.unshift({ type: 'thinking', text: d.thinkingContent, completed: false });
                }
            }
        }),
        startToolCall: (id, name, input) => set(d => {
            d.activeToolCalls.set(id, {
                toolName: name, input, status: 'running', startTime: Date.now(),
            });
        }),
        updateToolCallProgress: (id, progress) => set(d => {
            const tc = d.activeToolCalls.get(id);
            if (tc) tc.progress = progress;
        }),
        completeToolCall: (id, result) => set(d => {
            const tc = d.activeToolCalls.get(id);
            if (tc) {
                tc.status = result.isError ? 'error' : 'completed';
                tc.result = result;
                tc.duration = Date.now() - tc.startTime;
            }
        }),
        finalizeStream: (_usage) => set(d => {
            // 先刷新 streamingStore 中的剩余缓冲
            flushStreamingBuffer();
            const externalContent = streamingStore.clear();

            // 将累积的流式内容保存到 messages 中的 assistant 消息
            const combinedContent = d.streamingContent + externalContent;
            if (d.streamingMessageId) {
                const msg = d.messages.find(m => m.uuid === d.streamingMessageId);
                if (msg && 'content' in msg && msg.type === 'assistant') {
                    const content: any[] = [];
                    // 保留 thinking block (标记为 completed)
                    if (d.thinkingContent) {
                        content.push({ type: 'thinking' as const, text: d.thinkingContent, completed: true });
                    }
                    // 文本内容
                    if (combinedContent) {
                        content.push({ type: 'text' as const, text: combinedContent });
                    }
                    (msg as { content: unknown }).content = content;
                }
            }
            d.streamingMessageId = null;
            d.streamingContent = '';
            d.thinkingContent = '';
        }),
        clearMessages: () => set(d => { d.messages = []; d.activeToolCalls.clear(); }),
        rewindToMessage: (messageId) => set(d => {
            const idx = d.messages.findIndex(m => m.uuid === messageId);
            if (idx >= 0) d.messages.splice(idx + 1);
        }),
    })))
);
