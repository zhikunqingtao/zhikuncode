/**
 * MessageStore — 消息状态管理
 * SPEC: §8.3 Store #2
 * 持久化: 否 (从后端 session_restored 加载)
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type { ContentBlock, Message, ToolResult, ToolCallState, Usage, TokenWarningPayload } from '@/types';
import { streamingStore, flushStreamingBuffer } from '@/hooks/useStreamingText';
import { generateUUID } from '@/utils/uuid';
import { stripInternalMarkers } from '@/utils/internalMarkers';

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

function toolInputRecord(input: unknown): Record<string, unknown> {
    return input !== null && typeof input === 'object' && !Array.isArray(input)
        ? input as Record<string, unknown> : {};
}

function findToolBlock(messages: Message[], id: string): Extract<ContentBlock, { type: 'tool_use' }> | undefined {
    // 当前工具通常位于末段，从尾部查找，避免每次参数更新扫描全部历史。
    for (let index = messages.length - 1; index >= 0; index--) {
        const message = messages[index];
        if (message.type !== 'assistant') continue;
        for (const block of message.content) {
            if (block.type === 'tool_use' && block.toolUseId === id) return block;
        }
    }
}

/** 权威段带回的工具块保留已经先到达的结果。 */
function mergeSegmentTools(
    message: Extract<Message, { type: 'assistant' }>,
    messages: Message[],
    calls: Map<string, ToolCallState>,
): Extract<Message, { type: 'assistant' }> {
    return { ...message, content: message.content.map(block => block.type === 'tool_use'
        ? { ...block, result: block.result ?? calls.get(block.toolUseId)?.result ?? findToolBlock(messages, block.toolUseId)?.result }
        : block) };
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
    /**
     * steering（运行中追加指令）消息 uuid 登记，按 sessionId 键控天然隔离。
     * 供轮次投影（store/selectors/turnProjection）在 reconcile 换 uuid 后仍能识别
     * steering 消息 —— 后端提交 steering UserMessage 时沿用客户端 requestId 作为
     * 消息 uuid，因此快照/committedMessages 替换后登记依旧命中。
     * clearMessages / restoreSessionSnapshot 均不清除本字段。
     */
    steeringMessageIds: Record<string, string[]>;

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
    finalizeAssistantSegment: (message?: Extract<Message, { type: 'assistant' }>) => void;
    finalizeStream: (usage: Usage) => void;
    clearMessages: () => void;
    rewindToMessage: (messageId: string) => void;
    setTokenBudgetState: (state: TokenBudgetState | null) => void;
    clearTokenBudgetState: () => void;
    setTokenWarning: (warning: TokenWarningPayload | null) => void;
    clearTokenWarning: () => void;
    /** 登记 steering 消息 uuid（去重；每个 session 上限 200 条，超出丢最旧） */
    markSteeringMessage: (sessionId: string, uuid: string) => void;
}

/** steeringMessageIds 每个 session 的上限（超出丢最旧） */
export const MAX_STEERING_IDS_PER_SESSION = 200;

export const useMessageStore = create<MessageStoreState>()(
    subscribeWithSelector(immer((set, get) => ({
        messages: [],
        streamingMessageId: null,
        streamingContent: '',
        thinkingContent: '',
        activeToolCalls: new Map(),
        tokenBudgetState: null,
        tokenWarning: null,
        steeringMessageIds: {},

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
        startToolCall: (id, name, input) => {
            const existing = findToolBlock(get().messages, id);
            // 快照/最终对账后的旧 start 重放不能把已完成工具重新标为运行中。
            if (existing?.result) return;
            if (!existing && !get().streamingMessageId) get().appendStreamDelta('');
            set(d => {
                if (!d.activeToolCalls.has(id)) d.activeToolCalls.set(id, {
                    toolName: name, input, status: 'running', startTime: Date.now(),
                });
                const message = d.messages.find(m => m.uuid === d.streamingMessageId);
                if (!existing && message?.type === 'assistant') message.content.push({
                    type: 'tool_use', toolUseId: id, toolName: name, input: toolInputRecord(input),
                });
            });
        },
        updateToolCallInput: (id, input) => set(d => {
            const tc = d.activeToolCalls.get(id);
            if (tc) tc.input = input;
            const block = findToolBlock(d.messages, id);
            if (block) block.input = toolInputRecord(input);
            if (!tc && !block) console.warn(`[MessageStore] tool_use_input: 未找到 toolUseId=${id}`);
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
            }
            // 结果可晚于段完成/快照到达，仍按 toolUseId 回填所属消息。
            const block = findToolBlock(d.messages, id);
            if (block) block.result = result;
            if (!tc && !block) console.warn(`[MessageStore] tool_result: 未找到 toolUseId=${id}`);
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
        finalizeAssistantSegment: (authoritative) => {
            // 同 UUID 重放只更新原段，绝不能封掉随后正在流式生成的新段。
            if (authoritative && get().messages.some(message => message.uuid === authoritative.uuid)) {
                set(d => {
                    const index = d.messages.findIndex(message => message.uuid === authoritative.uuid);
                    d.messages[index] = mergeSegmentTools(authoritative, d.messages, d.activeToolCalls);
                });
                return;
            }
            flushStreamingBuffer();
            const externalContent = streamingStore.clear();
            set(d => {
                const index = d.messages.findIndex(m => m.uuid === d.streamingMessageId);
                if (authoritative) {
                    const merged = mergeSegmentTools(authoritative, d.messages, d.activeToolCalls);
                    if (index >= 0) d.messages[index] = merged;
                    else d.messages.push(merged);
                } else if (index >= 0) {
                    const message = d.messages[index];
                    if (message.type === 'assistant') {
                        const content: ContentBlock[] = [];
                        if (d.thinkingContent) content.push({ type: 'thinking', thinking: d.thinkingContent });
                        const rawText = d.streamingContent + externalContent;
                        const text = stripInternalMarkers(rawText);
                        const otherBlocks = message.content.filter(block => block.type !== 'text' && block.type !== 'thinking');
                        if (text) content.push({ type: 'text', text });
                        else if (rawText && content.length === 0 && otherBlocks.length === 0) {
                            content.push({ type: 'text', text: rawText });
                        }
                        // steering 封段也必须保留工具，不能只保存 thinking/text。
                        content.push(...otherBlocks);
                        message.content = content;
                    }
                }
                d.streamingMessageId = null;
                d.streamingContent = '';
                d.thinkingContent = '';
            });
        },
        finalizeStream: (_usage) => {
            get().finalizeAssistantSegment();
            set(d => {
                // 每个结果只回填它所属的段，不能把先前所有工具搬到最终回复。
                for (const [id, call] of d.activeToolCalls) {
                    const result = call.result ?? (call.error ? { content: call.error, isError: true } : undefined);
                    if (!result) continue;
                    for (const message of d.messages) {
                        if (message.type !== 'assistant') continue;
                        for (const block of message.content) {
                            if (block.type === 'tool_use' && block.toolUseId === id && !block.result) block.result = result;
                        }
                    }
                    d.activeToolCalls.delete(id);
                }
            });
        },
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
        markSteeringMessage: (sessionId, uuid) => set(d => {
            if (!sessionId || !uuid) return;
            const list = d.steeringMessageIds[sessionId] ?? (d.steeringMessageIds[sessionId] = []);
            if (list.includes(uuid)) return;
            list.push(uuid);
            // 上限保护：超出丢最旧（FIFO）
            if (list.length > MAX_STEERING_IDS_PER_SESSION) {
                list.splice(0, list.length - MAX_STEERING_IDS_PER_SESSION);
            }
        }),
    })))
);
