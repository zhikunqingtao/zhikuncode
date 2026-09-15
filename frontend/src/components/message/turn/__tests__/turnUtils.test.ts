/**
 * turnUtils 纯函数测试
 * 覆盖：轮次结果推导、耗时格式化、
 * 任务分节状态推导（resolveSectionStatus）、深链定位。
 */

import { describe, it, expect } from 'vitest';
import type { ContentBlock, Message, ToolCallState } from '@/types';
import { buildTurns } from '@/store/selectors/turnProjection';
import type { TurnTaskSection } from '@/store/selectors/turnSections';
import {
    formatTurnDuration,
    planTurnDeepLink,
    resolveSectionStatus,
    resolveTurnOutcome,
} from '../turnUtils';

// ==================== 消息工厂 ====================

function userText(uuid: string, timestamp: number, text = `text-${uuid}`): Message {
    return { type: 'user', uuid, timestamp, content: [{ type: 'text', text }] } as Message;
}


function assistantMsg(uuid: string, timestamp: number): Message {
    return {
        type: 'assistant', uuid, timestamp,
        content: [{ type: 'text', text: `reply-${uuid}` }],
        stopReason: 'end_turn',
        usage: { inputTokens: 1, outputTokens: 1, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
    } as Message;
}

function systemMsg(uuid: string, timestamp: number, subtype?: string): Message {
    return { type: 'system', uuid, timestamp, content: `sys-${uuid}`, subtype } as Message;
}

function toolUse(id: string, name: string, input: Record<string, unknown> = {}, result?: { content: string; isError: boolean; metadata?: Record<string, unknown> }): ContentBlock {
    return { type: 'tool_use', toolUseId: id, toolName: name, input, ...(result ? { result } : {}) };
}

function assistantWith(uuid: string, timestamp: number, content: ContentBlock[]): Message {
    return {
        type: 'assistant', uuid, timestamp, content,
        stopReason: 'end_turn',
        usage: { inputTokens: 1, outputTokens: 1, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
    } as Message;
}

// ==================== resolveTurnOutcome ====================

describe('resolveTurnOutcome', () => {
    it('无异常 system 消息 → success', () => {
        const [turn] = buildTurns([userText('u1', 1), assistantMsg('a1', 2)]);
        expect(resolveTurnOutcome(turn)).toBe('success');
    });

    it('subtype=error → error；provider_error 同样判 error', () => {
        const [t1] = buildTurns([userText('u1', 1), systemMsg('s1', 2, 'error')]);
        expect(resolveTurnOutcome(t1)).toBe('error');
        const [t2] = buildTurns([userText('u1', 1), systemMsg('s1', 2, 'provider_error')]);
        expect(resolveTurnOutcome(t2)).toBe('error');
    });

    it('subtype=interrupt → interrupted', () => {
        const [turn] = buildTurns([userText('u1', 1), systemMsg('s1', 2, 'interrupt')]);
        expect(resolveTurnOutcome(turn)).toBe('interrupted');
    });

    it('error 优先于 interrupt', () => {
        const [turn] = buildTurns([
            userText('u1', 1),
            systemMsg('s1', 2, 'interrupt'),
            systemMsg('s2', 3, 'error'),
        ]);
        expect(resolveTurnOutcome(turn)).toBe('error');
    });

    it('其他 subtype（compact_boundary 等）不影响结果', () => {
        const [turn] = buildTurns([userText('u1', 1), systemMsg('s1', 2, 'compact_boundary')]);
        expect(resolveTurnOutcome(turn)).toBe('success');
    });
});

// ==================== formatTurnDuration ====================

describe('formatTurnDuration', () => {
    it('不足 1 秒 → <1s（含 0 与负值兜底）', () => {
        expect(formatTurnDuration(1000, 1999)).toBe('<1s');
        expect(formatTurnDuration(1000, 1000)).toBe('<1s');
        expect(formatTurnDuration(1000, 500)).toBe('<1s');
    });

    it('不足 1 分钟 → Ns', () => {
        expect(formatTurnDuration(0, 45_000)).toBe('45s');
        expect(formatTurnDuration(0, 59_999)).toBe('59s');
    });

    it('不足 1 小时 → NmNNs', () => {
        expect(formatTurnDuration(0, 154_000)).toBe('2m34s');
        expect(formatTurnDuration(0, 60_000)).toBe('1m00s');
    });

    it('1 小时及以上 → NhNNm', () => {
        expect(formatTurnDuration(0, 3_723_000)).toBe('1h02m');
    });
});

// ==================== resolveSectionStatus ====================

function sectionOf(messages: Message[]): TurnTaskSection {
    return {
        index: 0,
        taskId: null,
        title: '任务',
        source: 'boundary',
        isPrep: false,
        messages,
        startedAt: messages[0]?.timestamp ?? 0,
        endedAt: messages[messages.length - 1]?.timestamp ?? 0,
    };
}

const IDLE_OPTS = { isActiveTurn: false, isRunActive: false, isLastSection: false };

describe('resolveSectionStatus', () => {
    it('分节工具全部成功 → completed', () => {
        const section = sectionOf([
            assistantWith('a1', 1, [toolUse('t1', 'Read', {}, { content: 'ok', isError: false })]),
        ]);
        expect(resolveSectionStatus(section, undefined, IDLE_OPTS)).toBe('completed');
    });

    it('任一工具 result.isError → error', () => {
        const section = sectionOf([
            assistantWith('a1', 1, [
                toolUse('t1', 'Read', {}, { content: 'ok', isError: false }),
                toolUse('t2', 'Bash', {}, { content: 'boom', isError: true }),
            ]),
        ]);
        expect(resolveSectionStatus(section, undefined, IDLE_OPTS)).toBe('error');
    });

    it('已取消工具 → interrupted（不计入失败）；error 优先于取消', () => {
        const cancelled = sectionOf([
            assistantWith('a1', 1, [
                toolUse('t1', 'Bash', {}, {
                    content: 'aborted',
                    isError: true,
                    metadata: { executionStatus: 'cancelled' },
                }),
            ]),
        ]);
        expect(resolveSectionStatus(cancelled, undefined, IDLE_OPTS)).toBe('interrupted');

        const mixed = sectionOf([
            assistantWith('a1', 1, [
                toolUse('t1', 'Bash', {}, {
                    content: 'aborted',
                    isError: true,
                    metadata: { executionStatus: 'cancelled' },
                }),
                toolUse('t2', 'Bash', {}, { content: 'boom', isError: true }),
            ]),
        ]);
        expect(resolveSectionStatus(mixed, undefined, IDLE_OPTS)).toBe('error');
    });

    it('活跃轮运行中 + 实时 running 工具 → running', () => {
        const section = sectionOf([
            assistantWith('a1', 1, [toolUse('t1', 'Bash')]),
        ]);
        const live = new Map<string, ToolCallState>([
            ['t1', { toolName: 'Bash', input: {}, status: 'running', startTime: 1 }],
        ]);
        expect(resolveSectionStatus(section, live, {
            isActiveTurn: true,
            isRunActive: true,
            isLastSection: true,
        })).toBe('running');
    });

    it('历史轮遗留的无结果工具不误判 running（活跃轮且 run 进行中才算）', () => {
        const section = sectionOf([
            assistantWith('a1', 1, [toolUse('t1', 'Bash')]),
        ]);
        // 无 activeToolCalls：block 无 result → resolved running，但轮已完结
        expect(resolveSectionStatus(section, undefined, IDLE_OPTS)).toBe('completed');
    });

    it('活跃轮运行中且为最后分节（工具可能尚未挂入）→ running；非最后分节 → completed', () => {
        const section = sectionOf([assistantMsg('a1', 1)]);
        expect(resolveSectionStatus(section, undefined, {
            isActiveTurn: true,
            isRunActive: true,
            isLastSection: true,
        })).toBe('running');
        expect(resolveSectionStatus(section, undefined, {
            isActiveTurn: true,
            isRunActive: true,
            isLastSection: false,
        })).toBe('completed');
        // run 已结束的活跃轮不强制 running
        expect(resolveSectionStatus(section, undefined, {
            isActiveTurn: true,
            isRunActive: false,
            isLastSection: true,
        })).toBe('completed');
    });
});

// ==================== planTurnDeepLink ====================

describe('planTurnDeepLink', () => {
    it('命中 → 返回所属轮次 index 与消息 uuid', () => {
        const turns = buildTurns([
            userText('u1', 1),
            assistantMsg('a1', 2),
            userText('u2', 3),
            assistantMsg('a2', 4),
        ]);
        expect(planTurnDeepLink(turns, 'a1')).toEqual({ turnIndex: 0, messageId: 'a1' });
        expect(planTurnDeepLink(turns, 'u2')).toEqual({ turnIndex: 1, messageId: 'u2' });
    });

    it('未命中 → null（调用方消费掉本次跳转）', () => {
        const turns = buildTurns([userText('u1', 1)]);
        expect(planTurnDeepLink(turns, 'missing')).toBeNull();
        expect(planTurnDeepLink([], 'u1')).toBeNull();
    });
});
