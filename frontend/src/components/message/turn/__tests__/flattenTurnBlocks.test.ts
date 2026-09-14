/**
 * flattenTurnBlocks 纯函数测试
 * 覆盖：合并矩阵全部分支（连续 tool_use 合并、tool_result 载体透明跳过、
 * 跨消息合并、text/thinking/steering/system/visualization 阻断、流式豁免）、
 * 空消息兜底、输入不可变性。
 */

import { describe, expect, it } from 'vitest';
import type { ContentBlock, Message } from '@/types';
import { buildTurns, type Turn } from '@/store/selectors/turnProjection';
import { flattenTurnBlocks, type FlattenedTurnItem } from '../flattenTurnBlocks';

// ==================== 消息工厂 ====================

function userText(uuid: string, timestamp: number, text = `text-${uuid}`): Message {
    return { type: 'user', uuid, timestamp, content: [{ type: 'text', text }] } as Message;
}

function steeringUser(uuid: string, timestamp: number, text = `steer-${uuid}`): Message {
    return {
        type: 'user', uuid, timestamp,
        content: [{ type: 'text', text }],
        meta: { steering: true },
    } as Message;
}

function userToolResultCarrier(uuid: string, timestamp: number, toolUseId = `tu-${uuid}`): Message {
    return {
        type: 'user', uuid, timestamp,
        content: [{ type: 'tool_result', toolUseId, content: 'result', isError: false }],
    } as Message;
}

function assistantWith(uuid: string, timestamp: number, content: ContentBlock[]): Message {
    return {
        type: 'assistant', uuid, timestamp, content,
        stopReason: 'end_turn',
        usage: { inputTokens: 1, outputTokens: 1, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
    } as Message;
}

function textBlock(text: string): ContentBlock {
    return { type: 'text', text };
}

function thinkingBlock(text: string): ContentBlock {
    return { type: 'thinking', thinking: text };
}

function toolUse(id: string, name = 'Read'): ContentBlock {
    return { type: 'tool_use', toolUseId: id, toolName: name, input: { file_path: `/f/${id}` } };
}

function systemMsg(uuid: string, timestamp: number): Message {
    return { type: 'system', uuid, timestamp, content: `sys-${uuid}` } as Message;
}

function visualizationMsg(uuid: string, timestamp: number): Message {
    return { type: 'visualization', uuid, timestamp, viewType: 'mermaid', props: {} } as Message;
}

function singleTurn(messages: Message[]): Turn {
    const turns = buildTurns(messages);
    expect(turns).toHaveLength(1);
    return turns[0];
}

function kinds(items: FlattenedTurnItem[]): string[] {
    return items.map(item => item.kind);
}

// ==================== 基础形态 ====================

describe('flattenTurnBlocks 基础形态', () => {
    it('user 指令 → message 项', () => {
        const turn = singleTurn([userText('u1', 1, '问题')]);
        const items = flattenTurnBlocks(turn);
        expect(kinds(items)).toEqual(['message']);
        expect(items[0]).toMatchObject({ kind: 'message', message: { uuid: 'u1' } });
    });

    it('assistant 纯文本 → blocks 项（保持原位渲染）', () => {
        const turn = singleTurn([userText('u1', 1), assistantWith('a1', 2, [textBlock('回答')])]);
        const items = flattenTurnBlocks(turn);
        expect(kinds(items)).toEqual(['message', 'blocks']);
        expect(items[1]).toMatchObject({ kind: 'blocks', message: { uuid: 'a1' } });
        expect((items[1] as { blocks: ContentBlock[] }).blocks).toHaveLength(1);
    });

    it('assistant [text, tool, text] → blocks / tool_run / blocks 三段', () => {
        const turn = singleTurn([
            userText('u1', 1),
            assistantWith('a1', 2, [textBlock('先看一下'), toolUse('t1'), textBlock('看完了')]),
        ]);
        const items = flattenTurnBlocks(turn);
        expect(kinds(items)).toEqual(['message', 'blocks', 'tool_run', 'blocks']);
        const run = items[2] as Extract<FlattenedTurnItem, { kind: 'tool_run' }>;
        expect(run.blocks.map(b => b.toolUseId)).toEqual(['t1']);
        expect(run.messageIds).toEqual(['a1']);
        // text 段保持原顺序与内容
        expect((items[1] as { blocks: ContentBlock[] }).blocks[0]).toMatchObject({ text: '先看一下' });
        expect((items[3] as { blocks: ContentBlock[] }).blocks[0]).toMatchObject({ text: '看完了' });
    });

    it('assistant 消息内连续 tool_use 合并为一段', () => {
        const turn = singleTurn([
            userText('u1', 1),
            assistantWith('a1', 2, [toolUse('t1'), toolUse('t2', 'Edit'), toolUse('t3', 'Bash')]),
        ]);
        const items = flattenTurnBlocks(turn);
        expect(kinds(items)).toEqual(['message', 'tool_run']);
        const run = items[1] as Extract<FlattenedTurnItem, { kind: 'tool_run' }>;
        expect(run.blocks.map(b => b.toolUseId)).toEqual(['t1', 't2', 't3']);
    });
});

// ==================== 合并矩阵 ====================

describe('flattenTurnBlocks 合并矩阵', () => {
    it('纯 tool_result 载体 user 消息透明跳过：Read→result→Edit→result 聚成一段', () => {
        const turn = singleTurn([
            userText('u1', 1),
            assistantWith('a1', 2, [toolUse('t1')]),
            userToolResultCarrier('r1', 3, 't1'),
            assistantWith('a2', 4, [toolUse('t2', 'Edit')]),
            userToolResultCarrier('r2', 5, 't2'),
        ]);
        const items = flattenTurnBlocks(turn);
        expect(kinds(items)).toEqual(['message', 'tool_run']);
        const run = items[1] as Extract<FlattenedTurnItem, { kind: 'tool_run' }>;
        expect(run.blocks.map(b => b.toolUseId)).toEqual(['t1', 't2']);
        // messageIds 记录贡献消息（跨消息合并）
        expect(run.messageIds).toEqual(['a1', 'a2']);
        // 载体消息不产出任何渲染项
        expect(JSON.stringify(items)).not.toContain('"r1"');
    });

    it('text/thinking 块阻断合并', () => {
        const turn = singleTurn([
            userText('u1', 1),
            assistantWith('a1', 2, [toolUse('t1')]),
            userToolResultCarrier('r1', 3, 't1'),
            assistantWith('a2', 4, [thinkingBlock('想一下'), toolUse('t2')]),
        ]);
        const items = flattenTurnBlocks(turn);
        expect(kinds(items)).toEqual(['message', 'tool_run', 'blocks', 'tool_run']);
        const run1 = items[1] as Extract<FlattenedTurnItem, { kind: 'tool_run' }>;
        const run2 = items[3] as Extract<FlattenedTurnItem, { kind: 'tool_run' }>;
        expect(run1.blocks.map(b => b.toolUseId)).toEqual(['t1']);
        expect(run2.blocks.map(b => b.toolUseId)).toEqual(['t2']);
    });

    it('同一消息内 tool_use 被 thinking 隔开 → 两段 tool_run', () => {
        const turn = singleTurn([
            userText('u1', 1),
            assistantWith('a1', 2, [toolUse('t1'), thinkingBlock('mid'), toolUse('t2')]),
        ]);
        const items = flattenTurnBlocks(turn);
        expect(kinds(items)).toEqual(['message', 'tool_run', 'blocks', 'tool_run']);
    });

    it('steering user 消息（meta.steering，含 text）阻断合并并原位渲染', () => {
        const turn = singleTurn([
            userText('u1', 1),
            assistantWith('a1', 2, [toolUse('t1')]),
            steeringUser('s1', 3, '顺便改下别处'),
            assistantWith('a2', 4, [toolUse('t2')]),
        ]);
        const items = flattenTurnBlocks(turn);
        expect(kinds(items)).toEqual(['message', 'tool_run', 'message', 'tool_run']);
        expect(items[2]).toMatchObject({ kind: 'message', message: { uuid: 's1' } });
    });

    it('system 消息阻断合并', () => {
        const turn = singleTurn([
            userText('u1', 1),
            assistantWith('a1', 2, [toolUse('t1')]),
            systemMsg('sys1', 3),
            assistantWith('a2', 4, [toolUse('t2')]),
        ]);
        const items = flattenTurnBlocks(turn);
        expect(kinds(items)).toEqual(['message', 'tool_run', 'message', 'tool_run']);
        expect(items[2]).toMatchObject({ kind: 'message', message: { uuid: 'sys1' } });
    });

    it('visualization 消息阻断合并', () => {
        const turn = singleTurn([
            userText('u1', 1),
            assistantWith('a1', 2, [toolUse('t1')]),
            visualizationMsg('v1', 3),
            assistantWith('a2', 4, [toolUse('t2')]),
        ]);
        const items = flattenTurnBlocks(turn);
        expect(kinds(items)).toEqual(['message', 'tool_run', 'message', 'tool_run']);
        expect(items[2]).toMatchObject({ kind: 'message', message: { uuid: 'v1' } });
    });

    it('assistant 内 image/server_tool_use/孤儿 tool_result 块随 text 段原位保留', () => {
        const turn = singleTurn([
            userText('u1', 1),
            assistantWith('a1', 2, [
                toolUse('t1'),
                { type: 'image', mediaType: 'image/png', base64Data: 'AAAA' },
                { type: 'server_tool_use', toolUseId: 'st1', toolName: 'web_search' },
                { type: 'tool_result', toolUseId: 'orphan', content: 'x', isError: false },
            ]),
        ]);
        const items = flattenTurnBlocks(turn);
        expect(kinds(items)).toEqual(['message', 'tool_run', 'blocks']);
        const seg = (items[2] as { blocks: ContentBlock[] }).blocks;
        expect(seg.map(b => b.type)).toEqual(['image', 'server_tool_use', 'tool_result']);
    });
});

// ==================== 流式豁免 ====================

describe('flattenTurnBlocks 流式豁免', () => {
    it('streamingMessageId 命中的消息整体作为 streaming 项，不参与聚合', () => {
        const turn = singleTurn([
            userText('u1', 1),
            assistantWith('a1', 2, [toolUse('t1')]),
            assistantWith('a2', 3, [toolUse('t2'), textBlock('生成中')]),
        ]);
        const items = flattenTurnBlocks(turn, { streamingMessageId: 'a2' });
        expect(kinds(items)).toEqual(['message', 'tool_run', 'streaming']);
        expect(items[2]).toMatchObject({ kind: 'streaming', message: { uuid: 'a2' } });
        // 流式消息内的 tool_use 不被并入前段
        const run = items[1] as Extract<FlattenedTurnItem, { kind: 'tool_run' }>;
        expect(run.blocks.map(b => b.toolUseId)).toEqual(['t1']);
    });

    it('流式消息阻断合并：其后的 tool_use 另起一段', () => {
        const turn = singleTurn([
            userText('u1', 1),
            assistantWith('a1', 2, [toolUse('t1')]),
            assistantWith('a2', 3, [textBlock('流式中')]),
            assistantWith('a3', 4, [toolUse('t2')]),
        ]);
        const items = flattenTurnBlocks(turn, { streamingMessageId: 'a2' });
        expect(kinds(items)).toEqual(['message', 'tool_run', 'streaming', 'tool_run']);
    });

    it('streamingMessageId 未命中任何消息 → 全部按终态摊平', () => {
        const turn = singleTurn([
            userText('u1', 1),
            assistantWith('a1', 2, [toolUse('t1'), textBlock('完成')]),
        ]);
        const items = flattenTurnBlocks(turn, { streamingMessageId: 'missing' });
        expect(kinds(items)).toEqual(['message', 'tool_run', 'blocks']);
    });
});

// ==================== 边界与不变性 ====================

describe('flattenTurnBlocks 边界', () => {
    it('非流式空 assistant 消息 → message 项原样渲染（与摊平前一致）', () => {
        const turn = singleTurn([
            userText('u1', 1),
            assistantWith('a1', 2, []),
        ]);
        const items = flattenTurnBlocks(turn);
        expect(kinds(items)).toEqual(['message', 'message']);
        expect(items[1]).toMatchObject({ kind: 'message', message: { uuid: 'a1' } });
    });

    it('preamble 轮（无指令）同样摊平', () => {
        const [turn] = buildTurns([
            systemMsg('s0', 1),
            assistantWith('a1', 2, [toolUse('t1'), textBlock('hi')]),
        ]);
        expect(turn.instruction).toBeNull();
        const items = flattenTurnBlocks(turn);
        expect(kinds(items)).toEqual(['message', 'tool_run', 'blocks']);
    });

    it('不修改输入：消息与块数组引用不变、内容不变', () => {
        const a1 = assistantWith('a1', 2, [toolUse('t1'), textBlock('x')]) as Extract<Message, { type: 'assistant' }>;
        const messages = [userText('u1', 1), a1, userToolResultCarrier('r1', 3, 't1')];
        const turn = singleTurn(messages);
        const snapshot = JSON.stringify(turn.messages);
        const contentRef = a1.content;
        flattenTurnBlocks(turn);
        expect(JSON.stringify(turn.messages)).toBe(snapshot);
        expect(a1.content).toBe(contentRef);
    });

    it('长序列性能形态：N 个工具块聚成一段（O(n) 单遍）', () => {
        const blocks: ContentBlock[] = [];
        for (let i = 0; i < 500; i += 1) blocks.push(toolUse(`t${i}`));
        const turn = singleTurn([userText('u1', 1), assistantWith('a1', 2, blocks)]);
        const items = flattenTurnBlocks(turn);
        expect(kinds(items)).toEqual(['message', 'tool_run']);
        expect((items[1] as { blocks: ContentBlock[] }).blocks).toHaveLength(500);
    });
});
