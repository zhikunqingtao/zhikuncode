import { describe, it, expect } from 'vitest';
import {
    buildTurns,
    isInstructionalUserMessage,
    findTurnIndexByMessageId,
} from '../turnProjection';
import type { Message } from '@/types';

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

function userToolResultCarrier(uuid: string, timestamp: number): Message {
    return {
        type: 'user', uuid, timestamp,
        content: [{ type: 'tool_result', toolUseId: `tu-${uuid}`, content: 'result', isError: false }],
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

function visualizationMsg(uuid: string, timestamp: number): Message {
    return { type: 'visualization', uuid, timestamp, viewType: 'chart', props: {} } as Message;
}

function groupedToolUseMsg(uuid: string, timestamp: number): Message {
    return {
        type: 'grouped_tool_use', uuid, timestamp,
        toolCalls: [{ toolUseId: 'tu-1', toolName: 'Bash', status: 'completed' }],
    } as Message;
}

describe('isInstructionalUserMessage', () => {
    it('非 user 消息返回 false', () => {
        expect(isInstructionalUserMessage(assistantMsg('a-1', 1))).toBe(false);
        expect(isInstructionalUserMessage(systemMsg('s-1', 1))).toBe(false);
    });

    it('含 text 块的 user 消息是指令', () => {
        expect(isInstructionalUserMessage(userText('u-1', 1))).toBe(true);
    });

    it('含 image 块的 user 消息是指令', () => {
        expect(isInstructionalUserMessage(userImage('u-1', 1))).toBe(true);
    });

    it('纯 tool_result 载体不是指令', () => {
        expect(isInstructionalUserMessage(userToolResultCarrier('u-1', 1))).toBe(false);
    });

    it('空 content 的 user 消息不是指令', () => {
        const msg = { type: 'user', uuid: 'u-1', timestamp: 1, content: [] } as Message;
        expect(isInstructionalUserMessage(msg)).toBe(false);
    });

    it('uuid 命中 steeringIds → steering（非指令）', () => {
        expect(isInstructionalUserMessage(userText('u-1', 1), new Set(['u-1']))).toBe(false);
    });

    it('meta.steering === true → steering（非指令）', () => {
        const msg = {
            type: 'user', uuid: 'u-1', timestamp: 1,
            content: [{ type: 'text', text: 'x' }],
            meta: { steering: true },
        } as Message;
        expect(isInstructionalUserMessage(msg)).toBe(false);
    });

    it('meta.steering 非 true 不影响判定', () => {
        const msg = {
            type: 'user', uuid: 'u-1', timestamp: 1,
            content: [{ type: 'text', text: 'x' }],
            meta: { steering: 'yes' },
        } as unknown as Message;
        expect(isInstructionalUserMessage(msg)).toBe(true);
    });
});

describe('buildTurns', () => {
    it('空数组输入返回 []', () => {
        expect(buildTurns([])).toEqual([]);
    });

    it('单轮：instruction + 后续消息归组，status=active，起止时间取首末消息', () => {
        const messages = [userText('u-1', 10), assistantMsg('a-1', 20), assistantMsg('a-2', 30)];
        const turns = buildTurns(messages);
        expect(turns).toHaveLength(1);
        const [turn] = turns;
        expect(turn.index).toBe(0);
        expect(turn.key).toBe('turn-0');
        expect(turn.instruction?.uuid).toBe('u-1');
        expect(turn.messages.map(m => m.uuid)).toEqual(['u-1', 'a-1', 'a-2']);
        expect(turn.status).toBe('active');
        expect(turn.startedAt).toBe(10);
        expect(turn.endedAt).toBe(30);
    });

    it('多轮边界：每条指令消息开启新轮；仅最后一轮 active', () => {
        const messages = [
            userText('u-1', 1), assistantMsg('a-1', 2),
            userText('u-2', 3), assistantMsg('a-2', 4),
            userText('u-3', 5),
        ];
        const turns = buildTurns(messages);
        expect(turns).toHaveLength(3);
        expect(turns.map(t => t.index)).toEqual([0, 1, 2]);
        expect(turns.map(t => t.key)).toEqual(['turn-0', 'turn-1', 'turn-2']);
        expect(turns.map(t => t.instruction?.uuid)).toEqual(['u-1', 'u-2', 'u-3']);
        expect(turns.map(t => t.status)).toEqual(['completed', 'completed', 'active']);
        expect(turns[1].messages.map(m => m.uuid)).toEqual(['u-2', 'a-2']);
    });

    it('纯 tool_result user 载体不开新轮，归入当前轮', () => {
        const messages = [
            userText('u-1', 1), assistantMsg('a-1', 2),
            userToolResultCarrier('u-tr', 3), assistantMsg('a-2', 4),
        ];
        const turns = buildTurns(messages);
        expect(turns).toHaveLength(1);
        expect(turns[0].messages.map(m => m.uuid)).toEqual(['u-1', 'a-1', 'u-tr', 'a-2']);
    });

    it('混合 text + tool_result 的 user 消息仍开新轮', () => {
        const mixed = {
            type: 'user', uuid: 'u-mix', timestamp: 3,
            content: [
                { type: 'tool_result', toolUseId: 'tu-1', content: 'r', isError: false },
                { type: 'text', text: '追加说明' },
            ],
        } as Message;
        const turns = buildTurns([userText('u-1', 1), assistantMsg('a-1', 2), mixed]);
        expect(turns).toHaveLength(2);
        expect(turns[1].instruction?.uuid).toBe('u-mix');
    });

    it('image 指令开新轮', () => {
        const turns = buildTurns([userText('u-1', 1), assistantMsg('a-1', 2), userImage('u-2', 3)]);
        expect(turns).toHaveLength(2);
        expect(turns[1].instruction?.uuid).toBe('u-2');
    });

    it('steering（steeringMessageIds 命中）归入当前轮，不新开轮', () => {
        const messages = [
            userText('u-1', 1), assistantMsg('a-1', 2),
            userText('steer-1', 3, 'change direction'), assistantMsg('a-2', 4),
        ];
        const turns = buildTurns(messages, { steeringMessageIds: new Set(['steer-1']) });
        expect(turns).toHaveLength(1);
        expect(turns[0].messages.map(m => m.uuid)).toEqual(['u-1', 'a-1', 'steer-1', 'a-2']);
    });

    it('steering（meta.steering=true 命中）归入当前轮，不新开轮', () => {
        const steering = {
            type: 'user', uuid: 'steer-1', timestamp: 3,
            content: [{ type: 'text', text: 'change direction' }],
            meta: { steering: true },
        } as Message;
        const messages = [userText('u-1', 1), assistantMsg('a-1', 2), steering, assistantMsg('a-2', 4)];
        const turns = buildTurns(messages);
        expect(turns).toHaveLength(1);
        expect(turns[0].messages.map(m => m.uuid)).toEqual(['u-1', 'a-1', 'steer-1', 'a-2']);
    });

    it('刷新路径：服务端水合消息（meta.steering=true，无 steeringMessageIds）与实时路径投影一致', () => {
        // 实时路径：steering 消息由 run_input_applied 落库，
        // 携带本地 meta 且 requestId 登记进 steeringMessageIds
        const liveSteering = {
            type: 'user', uuid: 'req-steering-1', timestamp: 3,
            content: [{ type: 'text', text: 'change direction' }],
            meta: { steering: true },
        } as Message;
        const liveMessages = [
            userText('u-1', 1), assistantMsg('a-1', 2), liveSteering, assistantMsg('a-2', 4),
        ];
        const liveTurns = buildTurns(liveMessages, {
            steeringMessageIds: new Set(['req-steering-1']),
        });

        // 刷新路径：steeringMessageIds 是纯内存登记（已丢失），历史消息来自
        // 后端 REST/session_restored —— 服务端沿用 requestId 作为 uuid 并回传
        // 持久化的 meta（messages.meta_json），无本地冗余标记之外的差异
        const serverHydratedSteering = {
            type: 'user', uuid: 'req-steering-1', timestamp: 3,
            content: [{ type: 'text', text: 'change direction' }],
            meta: { steering: true },
        } as Message;
        const serverMessages = [
            userText('u-1', 1), assistantMsg('a-1', 2), serverHydratedSteering, assistantMsg('a-2', 4),
        ];
        const refreshedTurns = buildTurns(serverMessages); // 无 steeringMessageIds

        // 两条路径必须产生完全一致的轮次结构（1 轮，steering 归并而非新开轮）
        expect(refreshedTurns.map(t => t.index)).toEqual(liveTurns.map(t => t.index));
        expect(refreshedTurns.map(t => t.key)).toEqual(liveTurns.map(t => t.key));
        expect(refreshedTurns.map(t => t.instruction?.uuid ?? null))
            .toEqual(liveTurns.map(t => t.instruction?.uuid ?? null));
        expect(refreshedTurns.map(t => t.status)).toEqual(liveTurns.map(t => t.status));
        expect(refreshedTurns.map(t => t.messages.map(m => m.uuid)))
            .toEqual(liveTurns.map(t => t.messages.map(m => m.uuid)));
        expect(refreshedTurns).toHaveLength(1);
        expect(refreshedTurns[0].messages.map(m => m.uuid))
            .toEqual(['u-1', 'a-1', 'req-steering-1', 'a-2']);
    });

    it('刷新路径回归保护：服务端水合消息缺 meta 时行为与旧版一致（steering 被当作新轮）', () => {
        // 向后兼容：无 meta 的历史消息（旧数据）行为不变 —— 该 user 消息含 text
        // 块且无 steering 标记，仍按普通指令开启新轮
        const legacySteering = {
            type: 'user', uuid: 'req-steering-1', timestamp: 3,
            content: [{ type: 'text', text: 'change direction' }],
        } as Message;
        const turns = buildTurns([
            userText('u-1', 1), assistantMsg('a-1', 2), legacySteering, assistantMsg('a-2', 4),
        ]);
        expect(turns).toHaveLength(2);
        expect(turns[1].instruction?.uuid).toBe('req-steering-1');
    });

    it('preamble：首条指令之前的首部消息归入 instruction=null 的轮次', () => {
        const messages = [
            systemMsg('s-1', 1), visualizationMsg('v-1', 2),
            userText('u-1', 3), assistantMsg('a-1', 4),
        ];
        const turns = buildTurns(messages);
        expect(turns).toHaveLength(2);
        expect(turns[0].instruction).toBeNull();
        expect(turns[0].key).toBe('turn-0');
        expect(turns[0].messages.map(m => m.uuid)).toEqual(['s-1', 'v-1']);
        expect(turns[0].status).toBe('completed');
        expect(turns[1].instruction?.uuid).toBe('u-1');
    });

    it('无任何指令时全部消息构成单个 preamble 轮（status=active）', () => {
        const turns = buildTurns([systemMsg('s-1', 1), assistantMsg('a-1', 2)]);
        expect(turns).toHaveLength(1);
        expect(turns[0].instruction).toBeNull();
        expect(turns[0].status).toBe('active');
    });

    it('steering 消息出现在首条指令之前时归入 preamble 轮', () => {
        const steering = {
            type: 'user', uuid: 'steer-0', timestamp: 1,
            content: [{ type: 'text', text: 'orphan steering' }],
            meta: { steering: true },
        } as Message;
        const turns = buildTurns([steering, userText('u-1', 2)]);
        expect(turns).toHaveLength(2);
        expect(turns[0].instruction).toBeNull();
        expect(turns[0].messages.map(m => m.uuid)).toEqual(['steer-0']);
    });

    it('compact_boundary/interrupt/error/visualization/grouped_tool_use 等消息归属当前轮', () => {
        // 实际 Message union 无独立 error/progress 分支：错误/进度以 system subtype 呈现
        const messages = [
            userText('u-1', 1),
            assistantMsg('a-1', 2),
            systemMsg('s-compact', 3, 'compact_boundary'),
            systemMsg('s-interrupt', 4, 'interrupt'),
            systemMsg('s-error', 5, 'error'),
            visualizationMsg('v-1', 6),
            groupedToolUseMsg('g-1', 7),
            assistantMsg('a-2', 8),
        ];
        const turns = buildTurns(messages);
        expect(turns).toHaveLength(1);
        expect(turns[0].messages.map(m => m.uuid))
            .toEqual(['u-1', 'a-1', 's-compact', 's-interrupt', 's-error', 'v-1', 'g-1', 'a-2']);
    });

    it('reconcile 式替换①：指令序列不变、其余消息 uuid 替换后，各轮 index/instruction 不变', () => {
        const steeringIds = new Set(['steer-1']);
        const original = [
            userText('u-1', 1), assistantMsg('a-1', 2),
            userText('steer-1', 3), assistantMsg('a-2', 4),
            userText('u-2', 5), assistantMsg('a-3', 6),
        ];
        const before = buildTurns(original, { steeringMessageIds: steeringIds });
        expect(before.map(t => t.instruction?.uuid)).toEqual(['u-1', 'u-2']);

        // 模拟 reconcileCommittedRun：指令/steering 消息 uuid 保留
        // （后端沿用 requestId 提交 steering 消息），其余消息被后端权威版本替换（uuid 全变）
        const replaced = [
            userText('u-1', 1), assistantMsg('a-1-new', 2),
            userText('steer-1', 3), assistantMsg('a-2-new', 4),
            userText('u-2', 5), assistantMsg('a-3-new', 6),
        ];
        const after = buildTurns(replaced, { steeringMessageIds: steeringIds });
        expect(after.map(t => t.index)).toEqual(before.map(t => t.index));
        expect(after.map(t => t.key)).toEqual(before.map(t => t.key));
        expect(after.map(t => t.instruction?.uuid)).toEqual(before.map(t => t.instruction?.uuid));
        expect(after.map(t => t.messages.length)).toEqual(before.map(t => t.messages.length));
    });

    it('reconcile 式替换②：全部消息被新对象替换（指令 uuid 也变更、steering uuid 保留）后轮次结构稳定', () => {
        // 与现有 reconcile 行为一致：普通 user 消息的 uuid 也会被后端权威版本替换
        // （见 dispatch.test.ts 'message_complete atomically reconciles'），
        // 轮次边界是位置性的，不依赖指令 uuid；steering 识别依赖 uuid 保留（已核实后端行为）。
        const steeringIds = new Set(['steer-1']);
        const original = [
            userText('u-1', 1), assistantMsg('a-1', 2),
            userText('steer-1', 3), assistantMsg('a-2', 4),
            userText('u-2', 5), assistantMsg('a-3', 6),
        ];
        const before = buildTurns(original, { steeringMessageIds: steeringIds });

        const committed = [
            userText('saved-u-1', 1), assistantMsg('saved-a-1', 2),
            userText('steer-1', 3), assistantMsg('saved-a-2', 4),
            userText('saved-u-2', 5), assistantMsg('saved-a-3', 6),
        ];
        const after = buildTurns(committed, { steeringMessageIds: steeringIds });
        expect(after).toHaveLength(before.length);
        expect(after.map(t => t.key)).toEqual(before.map(t => t.key));
        expect(after.map(t => t.messages.length)).toEqual(before.map(t => t.messages.length));
        // steering 消息仍归并（未新开轮）
        expect(after[0].messages.map(m => m.uuid))
            .toEqual(['saved-u-1', 'saved-a-1', 'steer-1', 'saved-a-2']);
    });

    it('消息只追加时前轮 index/key/instruction 不变（instruction 引用共享）', () => {
        const base = [userText('u-1', 1), assistantMsg('a-1', 2)];
        const before = buildTurns(base);
        expect(before[0].status).toBe('active');

        const after = buildTurns([...base, userText('u-2', 3), assistantMsg('a-2', 4)]);
        expect(after).toHaveLength(2);
        expect(after[0].index).toBe(before[0].index);
        expect(after[0].key).toBe(before[0].key);
        expect(after[0].instruction).toBe(before[0].instruction);
        expect(after[0].status).toBe('completed');
    });

    it('不修改输入：冻结输入后不抛错且组内消息引用共享', () => {
        const messages = Object.freeze([
            Object.freeze(userText('u-1', 1)),
            Object.freeze(assistantMsg('a-1', 2)),
        ]) as unknown as Message[];
        const turns = buildTurns(messages);
        expect(turns[0].messages[0]).toBe(messages[0]);
        expect(turns[0].messages[1]).toBe(messages[1]);
    });
});

describe('findTurnIndexByMessageId', () => {
    const turns = buildTurns([
        systemMsg('s-1', 1),
        userText('u-1', 2), assistantMsg('a-1', 3),
        userText('u-2', 4), assistantMsg('a-2', 5),
    ]);

    it('按 uuid 找到所属轮次（含 preamble 与非指令消息）', () => {
        expect(findTurnIndexByMessageId(turns, 's-1')).toBe(0);
        expect(findTurnIndexByMessageId(turns, 'u-1')).toBe(1);
        expect(findTurnIndexByMessageId(turns, 'a-1')).toBe(1);
        expect(findTurnIndexByMessageId(turns, 'a-2')).toBe(2);
    });

    it('未命中返回 -1', () => {
        expect(findTurnIndexByMessageId(turns, 'missing')).toBe(-1);
        expect(findTurnIndexByMessageId([], 'u-1')).toBe(-1);
    });
});
