/**
 * turnUtils 纯函数测试
 * 覆盖：轮次结果推导、耗时格式化、轮内文本拼接、指令首行、轮次序号、
 * 展开态装配（resolveTurnExpanded 集成）、深链定位。
 */

import { describe, it, expect } from 'vitest';
import type { ContentBlock, Message, ToolCallState } from '@/types';
import { buildTurns } from '@/store/selectors/turnProjection';
import {
    computeTurnOrdinals,
    extractTurnText,
    formatTurnDuration,
    instructionFirstLine,
    planTurnDeepLink,
    resolveTurnExpandedStates,
    resolveTurnOutcome,
    turnConclusionPreview,
    turnToolStats,
    TURN_CONCLUSION_PREVIEW_MAX,
} from '../turnUtils';

// ==================== 消息工厂 ====================

function userText(uuid: string, timestamp: number, text = `text-${uuid}`): Message {
    return { type: 'user', uuid, timestamp, content: [{ type: 'text', text }] } as Message;
}

function userImage(uuid: string, timestamp: number): Message {
    return {
        type: 'user', uuid, timestamp,
        content: [{ type: 'image', mediaType: 'image/png', base64Data: 'AAAA' }],
    } as Message;
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

// ==================== extractTurnText ====================

describe('extractTurnText', () => {
    it('默认排除 user 指令文本，拼接 assistant/system 的 text 文本（消息间空行分隔）', () => {
        const [turn] = buildTurns([
            userText('u1', 1, '问题一'),
            assistantMsg('a1', 2),
            systemMsg('s1', 3),
        ]);
        expect(extractTurnText(turn)).toBe('reply-a1\n\nsys-s1');
    });

    it('includeUser: true 时包含 user 指令文本', () => {
        const [turn] = buildTurns([
            userText('u1', 1, '问题一'),
            assistantMsg('a1', 2),
            systemMsg('s1', 3),
        ]);
        expect(extractTurnText(turn, { includeUser: true })).toBe('问题一\n\nreply-a1\n\nsys-s1');
    });

    it('轮内仅 user 文本（无 assistant/system 产出）→ null', () => {
        const [turn] = buildTurns([userText('u1', 1, '只有问题')]);
        expect(extractTurnText(turn)).toBeNull();
    });

    it('轮内无可复制文本 → null', () => {
        const [turn] = buildTurns([userImage('u1', 1)]);
        // 纯图片 user 是合法指令但无文本
        expect(extractTurnText(turn)).toBeNull();
    });
});

// ==================== instructionFirstLine ====================

describe('instructionFirstLine', () => {
    it('取指令文本首行', () => {
        expect(instructionFirstLine(userText('u1', 1, '第一行\n第二行'))).toBe('第一行');
    });

    it('纯图片指令 → 占位文案', () => {
        expect(instructionFirstLine(userImage('u1', 1))).toBe('[图片]');
    });
});

// ==================== computeTurnOrdinals ====================

describe('computeTurnOrdinals', () => {
    it('只数有指令的轮；preamble 为 null', () => {
        const turns = buildTurns([
            systemMsg('s0', 1), // preamble
            userText('u1', 2),
            assistantMsg('a1', 3),
            userText('u2', 4),
        ]);
        expect(computeTurnOrdinals(turns)).toEqual([null, 1, 2]);
    });

    it('无 preamble 时从 1 连续编号', () => {
        const turns = buildTurns([userText('u1', 1), userText('u2', 2)]);
        expect(computeTurnOrdinals(turns)).toEqual([1, 2]);
    });
});

// ==================== resolveTurnExpandedStates（装配层集成） ====================

describe('resolveTurnExpandedStates', () => {
    const turns = buildTurns([
        userText('u1', 1),
        assistantMsg('a1', 2),
        userText('u2', 3),
    ]);

    it('balanced：仅最后一轮展开；override 双向生效', () => {
        expect(resolveTurnExpandedStates(turns, 'balanced', false)).toEqual([false, true]);
        expect(resolveTurnExpandedStates(turns, 'balanced', false, { 0: true, 1: false }))
            .toEqual([true, false]);
    });

    it('compact：全折叠；最后一轮运行中强制展开', () => {
        expect(resolveTurnExpandedStates(turns, 'compact', false)).toEqual([false, false]);
        expect(resolveTurnExpandedStates(turns, 'compact', true)).toEqual([false, true]);
    });

    it('detailed：全展开', () => {
        expect(resolveTurnExpandedStates(turns, 'detailed', false)).toEqual([true, true]);
    });
});

// ==================== turnToolStats ====================

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

describe('turnToolStats', () => {
    it('无工具轮 → 全零', () => {
        const [turn] = buildTurns([userText('u1', 1), assistantMsg('a1', 2)]);
        expect(turnToolStats(turn)).toEqual({
            total: 0,
            topNames: [],
            filesChanged: 0,
            errorCount: 0,
            cancelledCount: 0,
            totalDurationMs: 0,
        });
    });

    it('统计总数与 top 工具名（计数降序，平手按首次出现序，取前 3）', () => {
        const [turn] = buildTurns([
            userText('u1', 1),
            assistantWith('a1', 2, [
                toolUse('t1', 'Bash'),
                toolUse('t2', 'Read'),
                toolUse('t3', 'Read'),
                toolUse('t4', 'Edit'),
                toolUse('t5', 'Bash'),
                toolUse('t6', 'Read'),
                toolUse('t7', 'Grep'),
            ]),
        ]);
        const stats = turnToolStats(turn);
        expect(stats.total).toBe(7);
        expect(stats.topNames).toEqual([['Read', 3], ['Bash', 2], ['Edit', 1]]);
    });

    it('filesChanged：Edit/Write/MultiEdit/NotebookEdit 的 file_path 去重，其他工具不计', () => {
        const [turn] = buildTurns([
            userText('u1', 1),
            assistantWith('a1', 2, [
                toolUse('t1', 'Edit', { file_path: '/a.ts' }),
                toolUse('t2', 'Edit', { file_path: '/a.ts' }),
                toolUse('t3', 'Write', { file_path: '/b.ts' }),
                toolUse('t4', 'MultiEdit', { file_path: '/c.ts' }),
                toolUse('t5', 'NotebookEdit', { file_path: '/d.ipynb' }),
                toolUse('t6', 'Read', { file_path: '/e.ts' }),
                toolUse('t7', 'Edit', { file_path: 42 }),
                toolUse('t8', 'Edit', {}),
            ]),
        ]);
        expect(turnToolStats(turn).filesChanged).toBe(4);
    });

    it('errorCount / cancelledCount：isError 计失败，cancelled 不计入失败', () => {
        const [turn] = buildTurns([
            userText('u1', 1),
            assistantWith('a1', 2, [
                toolUse('t1', 'Bash', {}, { content: 'boom', isError: true }),
                toolUse('t2', 'Bash', {}, { content: 'ok', isError: false }),
                toolUse('t3', 'Read', {}, { content: 'aborted', isError: true, metadata: { executionStatus: 'cancelled' } }),
            ]),
        ]);
        const stats = turnToolStats(turn);
        expect(stats.errorCount).toBe(1);
        expect(stats.cancelledCount).toBe(1);
    });

    it('totalDurationMs：仅 activeToolCalls 实时条目的 duration 参与累加', () => {
        const [turn] = buildTurns([
            userText('u1', 1),
            assistantWith('a1', 2, [
                toolUse('t1', 'Read', {}, { content: 'x', isError: false }),
                toolUse('t2', 'Edit', {}, { content: 'y', isError: false }),
            ]),
        ]);
        // 无 activeToolCalls：block 不持久化耗时 → 0
        expect(turnToolStats(turn).totalDurationMs).toBe(0);
        const live = new Map<string, ToolCallState>([
            ['t1', { toolName: 'Read', input: {}, status: 'completed', startTime: 0, duration: 1200 }],
            ['t2', { toolName: 'Edit', input: {}, status: 'completed', startTime: 0, duration: 800 }],
        ]);
        expect(turnToolStats(turn, live).totalDurationMs).toBe(2000);
    });

    it('activeToolCalls 的 error 状态（无 result）计入失败', () => {
        const [turn] = buildTurns([
            userText('u1', 1),
            assistantWith('a1', 2, [toolUse('t1', 'Bash')]),
        ]);
        const live = new Map<string, ToolCallState>([
            ['t1', { toolName: 'Bash', input: {}, status: 'error', startTime: 0, duration: 100 }],
        ]);
        expect(turnToolStats(turn, live).errorCount).toBe(1);
    });
});

// ==================== turnConclusionPreview ====================

describe('turnConclusionPreview', () => {
    it('取轮内最后一个 assistant text 块的首个非空行', () => {
        const [turn] = buildTurns([
            userText('u1', 1, '问题'),
            assistantWith('a1', 2, [{ type: 'text', text: '第一段' }]),
            assistantWith('a2', 3, [{ type: 'thinking', thinking: '想' }, { type: 'text', text: '\n最终结论在此\n第二行忽略' }]),
        ]);
        expect(turnConclusionPreview(turn)).toBe('最终结论在此');
    });

    it('超过 ~80 字符截断并追加省略号', () => {
        const longLine = '长'.repeat(TURN_CONCLUSION_PREVIEW_MAX + 20);
        const [turn] = buildTurns([
            userText('u1', 1),
            assistantWith('a1', 2, [{ type: 'text', text: longLine }]),
        ]);
        const preview = turnConclusionPreview(turn);
        expect(preview).toHaveLength(TURN_CONCLUSION_PREVIEW_MAX + 1);
        expect(preview?.endsWith('…')).toBe(true);
    });

    it('轮内无 assistant 文本 / 全空白文本 → null', () => {
        const [noText] = buildTurns([userText('u1', 1), assistantWith('a1', 2, [toolUse('t1', 'Read')])]);
        expect(turnConclusionPreview(noText)).toBeNull();
        const [blank] = buildTurns([userText('u1', 1), assistantWith('a1', 2, [{ type: 'text', text: '  \n\n' }])]);
        expect(turnConclusionPreview(blank)).toBeNull();
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
