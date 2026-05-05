import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useMessageStore } from '../../store/messageStore';
import { useConfigStore } from '../../store/configStore';
import type { Message } from '@/types';

/**
 * TC-IMMER-001~004: immer 不可变性专项测试
 */

describe('immer immutability', () => {
    beforeEach(() => {
        useMessageStore.setState({
            messages: [],
            streamingMessageId: null,
            streamingContent: '',
            thinkingContent: '',
            activeToolCalls: new Map(),
            tokenBudgetState: null,
        });
    });

    it('TC-IMMER-001: 深层嵌套更新不影响原引用', () => {
        const initialMessages = useMessageStore.getState().messages;

        // 通过 addMessage 添加消息
        const msg: Message = {
            uuid: 'immer-test-1',
            type: 'user',
            content: [{ type: 'text', text: 'new message' }],
            timestamp: Date.now(),
        } as Message;
        useMessageStore.getState().addMessage(msg);

        const newMessages = useMessageStore.getState().messages;
        expect(newMessages).not.toBe(initialMessages);
        expect(initialMessages).toHaveLength(0);
        expect(newMessages).toHaveLength(1);
    });

    it('TC-IMMER-002: Map 类型深层更新不可变性', () => {
        // 使用 startToolCall 添加 tool call
        useMessageStore.getState().startToolCall('tool-1', 'Read', { path: '/test' });
        const beforeUpdate = useMessageStore.getState().activeToolCalls;

        // 使用 updateToolCallProgress 更新进度
        useMessageStore.getState().updateToolCallProgress('tool-1', 'Reading file...');
        const afterUpdate = useMessageStore.getState().activeToolCalls;

        // immer with enableMapSet: Map 引用应不同
        expect(afterUpdate).not.toBe(beforeUpdate);
        expect(afterUpdate.get('tool-1')!.progress).toBe('Reading file...');
    });

    it('TC-IMMER-003: 单个 set 调用中的批量更新原子性', () => {
        const subscribeFn = vi.fn();
        const unsub = useMessageStore.subscribe(subscribeFn);

        useMessageStore.setState((draft) => {
            draft.streamingMessageId = 'msg-batch';
            draft.streamingContent = 'batch content';
            draft.thinkingContent = 'thinking...';
        });

        // 原子更新：单次 set 调用应只触发一次订阅通知
        expect(subscribeFn).toHaveBeenCalledTimes(1);

        // 验证所有字段已更新
        const state = useMessageStore.getState();
        expect(state.streamingMessageId).toBe('msg-batch');
        expect(state.streamingContent).toBe('batch content');
        expect(state.thinkingContent).toBe('thinking...');

        unsub();
    });

    it('TC-IMMER-004: configStore 持久化状态正确序列化与反序列化', () => {
        // 更新 autoCompact 配置
        useConfigStore.setState({
            autoCompact: { enabled: true, threshold: 0.8 },
        });

        // 验证 localStorage 持久化
        const stored = localStorage.getItem('ai-coder-config');
        expect(stored).toBeTruthy();
        if (stored) {
            const parsed = JSON.parse(stored);
            expect(parsed.state.autoCompact.enabled).toBe(true);
            expect(parsed.state.autoCompact.threshold).toBe(0.8);
        }
    });
});
