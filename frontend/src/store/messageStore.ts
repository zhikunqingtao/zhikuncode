/**
 * MessageStore — 消息状态管理
 * SPEC: §8.3 Store #2
 * 持久化: 否 (从后端 session_restored 加载)
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type { Message, ToolResult, ToolCallState, Usage, TokenWarningPayload } from '@/types';
import { streamingStore, flushStreamingBuffer } from '@/hooks/useStreamingText';
import { generateUUID } from '@/utils/uuid';

export interface TokenBudgetState {
    pct: number;
    currentTokens: number;
    budgetTokens: number;
    visible: boolean;
}

export interface RecoveredToolCall {
    toolUseId: string;
    toolName: string;
    input: unknown;
    startedAt?: number;
}

function attachCompletedToolResults(messages: Message[]): Message[] {
    const results = new Map<string, ToolResult>();
    for (const message of messages) {
        if (message.type !== 'user') continue;
        for (const block of message.content) {
            if (block.type !== 'tool_result') continue;
            results.set(block.toolUseId, {
                content: block.content,
                isError: block.isError,
                metadata: block.metadata,
            });
        }
    }
    if (results.size === 0) return messages;
    // 已被 assistant tool_use 实际消费的 toolUseId；孤儿 tool_result（无对应 tool_use）不算，原样保留
    const consumedToolUseIds = new Set<string>();
    for (const message of messages) {
        if (message.type !== 'assistant') continue;
        for (const block of message.content) {
            if (block.type === 'tool_use' && results.has(block.toolUseId)) {
                consumedToolUseIds.add(block.toolUseId);
            }
        }
    }
    if (consumedToolUseIds.size === 0) return messages;
    const projected: Message[] = [];
    for (const message of messages) {
        if (message.type === 'assistant') {
            let changed = false;
            const content = message.content.map(block => {
                if (block.type !== 'tool_use') return block;
                const result = results.get(block.toolUseId);
                if (!result) return block;
                changed = true;
                return { ...block, result };
            });
            projected.push(changed ? { ...message, content } : message);
            continue;
        }
        if (message.type === 'user') {
            // 块级过滤：剔除已被消费的 tool_result 载体块；混合 text 的消息保留剩余块
            const remaining = message.content.filter(
                block => !(block.type === 'tool_result' && consumedToolUseIds.has(block.toolUseId)),
            );
            // 纯 tool_result 载体消息被完全消费后不进入返回列表，避免渲染空白 "You" 气泡
            if (remaining.length === 0 && message.content.length > 0) continue;
            projected.push(remaining.length === message.content.length ? message : { ...message, content: remaining });
            continue;
        }
        projected.push(message);
    }
    return projected;
}

export interface MessageStoreState {
    // 状态
    messages: Message[];
    streamingMessageId: string | null;
    streamingContent: string;
    thinkingContent: string;
    activeToolCalls: Map<string, ToolCallState>;
    tokenBudgetState: TokenBudgetState | null;
    tokenWarning: TokenWarningPayload | null;

    // Actions
    addMessage: (msg: Message) => void;
    appendStreamDelta: (delta: string) => void;
    appendThinkingDelta: (delta: string) => void;
    startToolCall: (toolUseId: string, toolName: string, input: unknown) => void;
    updateToolCallInput: (toolUseId: string, input: unknown) => void;
    updateToolCallProgress: (toolUseId: string, progress: string) => void;
    completeToolCall: (toolUseId: string, result: ToolResult) => void;
    failAllRunningToolCalls: (errorMessage: string) => void;
    replaceActiveToolCalls: (calls: RecoveredToolCall[]) => void;
    restoreSessionSnapshot: (messages: Message[], calls: RecoveredToolCall[]) => void;
    reconcileCommittedRun: (replaceAfterMessageId: string | null, messages: Message[]) => boolean;
    finalizeAssistantSegment: () => void;
    finalizeStream: (usage: Usage) => void;
    clearMessages: () => void;
    rewindToMessage: (messageId: string) => void;
    setTokenBudgetState: (state: TokenBudgetState | null) => void;
    clearTokenBudgetState: () => void;
    setTokenWarning: (warning: TokenWarningPayload | null) => void;
    clearTokenWarning: () => void;
}

export const useMessageStore = create<MessageStoreState>()(
    subscribeWithSelector(immer((set) => ({
        messages: [],
        streamingMessageId: null,
        streamingContent: '',
        thinkingContent: '',
        activeToolCalls: new Map(),
        tokenBudgetState: null,
        tokenWarning: null,

        addMessage: (msg) => set(d => {
            const existing = d.messages.findIndex(item => item.uuid === msg.uuid);
            if (existing >= 0) d.messages[existing] = msg;
            else d.messages.push(msg);
        }),
        appendStreamDelta: (delta) => set(d => {
            // 首次收到 stream_delta 时，创建占位 assistant 消息
            if (!d.streamingMessageId) {
                const msgId = generateUUID();
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
                const msgId = generateUUID();
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
                    thinkingBlock.thinking = d.thinkingContent;
                } else {
                    content.unshift({ type: 'thinking', thinking: d.thinkingContent, completed: false });
                }
            }
        }),
        startToolCall: (id, name, input) => set(d => {
            d.activeToolCalls.set(id, {
                toolName: name, input, status: 'running', startTime: Date.now(),
            });
        }),
        updateToolCallInput: (id, input) => set(d => {
            const tc = d.activeToolCalls.get(id);
            if (tc) {
                tc.input = input;
            } else {
                console.warn(`[MessageStore] tool_use_input: 未找到 toolUseId=${id} 的活跃工具调用，input 被丢弃`);
            }
        }),
        updateToolCallProgress: (id, progress) => set(d => {
            const tc = d.activeToolCalls.get(id);
            if (tc) {
                tc.progress = progress;
                if (!tc.progressHistory) tc.progressHistory = [];
                tc.progressHistory.push(progress);
            }
        }),
        completeToolCall: (id, result) => set(d => {
            const tc = d.activeToolCalls.get(id);
            if (tc) {
                tc.status = result.isError ? 'error' : 'completed';
                tc.result = result;
                tc.duration = Date.now() - tc.startTime;
            } else {
                console.warn(`[MessageStore] tool_result: 未找到 toolUseId=${id} 的活跃工具调用，result 被丢弃`);
            }
        }),
        failAllRunningToolCalls: (errorMessage) => set(d => {
            // 错误/run_failed 路径上 tool_result 永远不会到来：
            // 将仍在 running 的工具调用标记为 error，避免工具卡片永久转圈。
            // 已有终态（completed/error/permission_needed 等）的条目不受影响。
            for (const tc of d.activeToolCalls.values()) {
                if (tc.status !== 'running') continue;
                tc.status = 'error';
                tc.error = errorMessage;
                tc.duration = Date.now() - tc.startTime;
            }
        }),
        replaceActiveToolCalls: (calls) => set(d => {
            d.activeToolCalls.clear();
            calls.forEach(call => d.activeToolCalls.set(call.toolUseId, {
                toolName: call.toolName || 'Tool',
                input: call.input ?? {},
                status: 'running',
                startTime: call.startedAt ?? Date.now(),
            }));
        }),
        restoreSessionSnapshot: (messages, calls) => {
            flushStreamingBuffer();
            streamingStore.clear();
            const projectedMessages = attachCompletedToolResults(messages);
            set(d => {
                d.messages = projectedMessages;
                d.streamingMessageId = null;
                d.streamingContent = '';
                d.thinkingContent = '';
                d.activeToolCalls.clear();
                calls.forEach(call => d.activeToolCalls.set(call.toolUseId, {
                    toolName: call.toolName || 'Tool',
                    input: call.input ?? {},
                    status: 'running',
                    startTime: call.startedAt ?? Date.now(),
                }));
                d.tokenBudgetState = null;
                d.tokenWarning = null;
            });
        },
        reconcileCommittedRun: (replaceAfterMessageId, messages) => {
            if (messages.length === 0) return false;
            const incomingIds = new Set<string>();
            for (const message of messages) {
                if (!message.uuid || incomingIds.has(message.uuid)) return false;
                incomingIds.add(message.uuid);
            }
            const projectedMessages = attachCompletedToolResults(messages);
            // 精准清理：仅清除 committed messages 中已带 result 的工具条目，
            // 仍在 running（无 result）的条目保留，使后续 tool_result 仍能通过 completeToolCall 关联
            const resolvedToolUseIds = new Set<string>();
            for (const message of projectedMessages) {
                if (message.type !== 'assistant' && message.type !== 'user') continue;
                for (const block of message.content) {
                    if (block.type === 'tool_use' && block.result) resolvedToolUseIds.add(block.toolUseId);
                    else if (block.type === 'tool_result') resolvedToolUseIds.add(block.toolUseId);
                }
            }
            let reconciled = false;
            set(d => {
                const keepCount = replaceAfterMessageId === null
                    ? 0
                    : d.messages.findIndex(message => message.uuid === replaceAfterMessageId) + 1;
                if (replaceAfterMessageId !== null && keepCount === 0) return;
                for (let i = 0; i < keepCount; i++) {
                    if (incomingIds.has(d.messages[i].uuid)) return;
                }
                d.messages.splice(keepCount, d.messages.length - keepCount, ...projectedMessages);
                d.streamingMessageId = null;
                d.streamingContent = '';
                d.thinkingContent = '';
                resolvedToolUseIds.forEach(id => d.activeToolCalls.delete(id));
                d.tokenBudgetState = null;
                d.tokenWarning = null;
                reconciled = true;
            });
            if (reconciled) {
                flushStreamingBuffer();
                streamingStore.clear();
            }
            return reconciled;
        },
        finalizeAssistantSegment: () => set(d => {
            flushStreamingBuffer();
            const externalContent = streamingStore.clear();
            const combinedContent = d.streamingContent + externalContent;
            if (d.streamingMessageId) {
                const msg = d.messages.find(m => m.uuid === d.streamingMessageId);
                if (msg && msg.type === 'assistant') {
                    const content: any[] = [];
                    if (d.thinkingContent) {
                        content.push({ type: 'thinking' as const, thinking: d.thinkingContent, completed: true });
                    }
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
                        content.push({ type: 'thinking' as const, thinking: d.thinkingContent, completed: true });
                    }
                    // 文本内容
                    if (combinedContent) {
                        content.push({ type: 'text' as const, text: combinedContent });
                    }
                    // 将本条流式消息期间的工具调用迁移为 tool_use block，
                    // 保证流式→终态切换后工具卡片数据（id/name/完整 input/result/status）不丢失
                    for (const [toolUseId, tc] of Array.from(d.activeToolCalls.entries())) {
                        content.push({
                            type: 'tool_use' as const,
                            toolUseId,
                            toolName: tc.toolName,
                            input: tc.input ?? {},
                            ...(tc.result ? { result: tc.result } : {}),
                        });
                        // 已有 result 的条目迁移后即可清理；running 条目保留，
                        // 使后续到达的 tool_result 仍能通过 completeToolCall 关联
                        if (tc.result) d.activeToolCalls.delete(toolUseId);
                    }
                    (msg as { content: unknown }).content = content;
                }
            }
            d.streamingMessageId = null;
            d.streamingContent = '';
            d.thinkingContent = '';
        }),
        clearMessages: () => {
            flushStreamingBuffer();
            streamingStore.clear();
            set(d => {
                d.messages = [];
                d.streamingMessageId = null;
                d.streamingContent = '';
                d.thinkingContent = '';
                d.activeToolCalls.clear();
                d.tokenBudgetState = null;
                d.tokenWarning = null;
            });
        },
        rewindToMessage: (messageId) => set(d => {
            const idx = d.messages.findIndex(m => m.uuid === messageId);
            if (idx >= 0) d.messages.splice(idx + 1);
        }),
        setTokenBudgetState: (state) => set(d => { d.tokenBudgetState = state; }),
        clearTokenBudgetState: () => set(d => { d.tokenBudgetState = null; }),
        setTokenWarning: (warning) => set((draft) => { draft.tokenWarning = warning; }),
        clearTokenWarning: () => set((draft) => { draft.tokenWarning = null; }),
    })))
);
