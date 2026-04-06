/**
 * MessageStore — 消息状态管理
 * SPEC: §8.3 Store #2
 * 持久化: 否 (从后端 session_restored 加载)
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type { Message, ToolResult, ToolCallState, Usage } from '@/types';

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
        appendStreamDelta: (delta) => set(d => { d.streamingContent += delta; }),
        appendThinkingDelta: (delta) => set(d => { d.thinkingContent += delta; }),
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
