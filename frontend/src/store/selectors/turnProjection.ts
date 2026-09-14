/**
 * TurnProjection — 消息流「轮次（Turn）」投影（纯函数，无状态）
 *
 * 将扁平的 messages 数组按「用户指令」边界分组为轮次：
 * - 轮次边界：user 消息且 content 含 ≥1 个 text 或 image 块（即非纯 tool_result 载体），
 *   且未被判定为 steering → 开启新轮，该消息为 Turn.instruction。
 * - steering user 消息（uuid ∈ steeringMessageIds，或 meta.steering === true）
 *   → 归入当前轮，不新开轮。
 * - 首条指令消息之前的首部消息（如有，例如恢复会话时的系统消息）
 *   → preamble 轮（instruction: null）。
 * - 其余所有消息（assistant/system/attachment/grouped_tool_use/
 *   collapsed_read_search/visualization）按序归入当前轮。
 *
 * 稳定性设计：Turn.key = `turn-${index}`，刻意不使用消息 uuid ——
 * reconcileCommittedRun 会用后端权威消息整体替换本地投影（uuid 全变），
 * 只有 turnIndex 是跨替换稳定的坐标，供 turnViewStore 的 expandOverrides 键控。
 *
 * 复杂度：O(n) 单遍；不修改输入、不做深拷贝（组内 messages 数组持有原消息引用）。
 */

import type { Message } from '@/types';

export type TurnStatus = 'active' | 'completed';

export interface Turn {
    /** 轮次序号（0 起），跨 reconcile 稳定的坐标 */
    index: number;
    /** 稳定 key：`turn-${index}`（不要用消息 uuid —— reconcile 会替换 uuid） */
    key: string;
    /** 开启本轮的用户指令消息；preamble 轮为 null */
    instruction: Message | null;
    /** 组内全部消息（含 instruction 本身，按原始顺序，引用共享） */
    messages: Message[];
    /** 最后一轮为 'active'，其余为 'completed' */
    status: TurnStatus;
    /** 组内首条消息的 timestamp（注意：与 Message.timestamp 一致为 epoch millis 数字） */
    startedAt: number;
    /** 组内末条消息的 timestamp */
    endedAt: number;
}

export interface BuildTurnsOptions {
    /** steering 消息 uuid 集合（通常来自 messageStore.steeringMessageIds[sessionId]） */
    steeringMessageIds?: ReadonlySet<string>;
}

/**
 * 判定一条消息是否为「开启新一轮的用户指令」。
 *
 * 命中任一 steering 标记即判为 steering（返回 false）：
 * 1. msg.uuid ∈ steeringIds（跨 reconcile 稳定 —— 后端提交 steering UserMessage 时
 *    沿用客户端 requestId 作为 uuid，见 api/dispatch.ts run_input_applied 注释）；
 * 2. msg.meta?.steering === true（双通道冗余标记 —— 后端将 meta 持久化并在
 *    历史 REST / session_restored 快照 / committedMessages 中原样回传，
 *    因此刷新后（steeringIds 为空）投影结果与实时路径一致）。
 *
 * 非 steering 的 user 消息还需 content 含 ≥1 个 text 或 image 块才是指令；
 * 纯 tool_result 载体（工具结果回传）不开新轮。
 */
export function isInstructionalUserMessage(
    msg: Message,
    steeringIds?: ReadonlySet<string>,
): boolean {
    if (msg.type !== 'user') return false;
    if (steeringIds?.has(msg.uuid)) return false;
    if (msg.meta?.steering === true) return false;
    return msg.content.some(block => block.type === 'text' || block.type === 'image');
}

/**
 * 构建轮次投影。空数组输入返回 []。
 *
 * 单遍分组后再统一标注 index/key/status/起止时间；
 * preamble 轮（instruction: null）仅当首条指令消息之前存在首部消息时产生。
 */
export function buildTurns(messages: Message[], opts?: BuildTurnsOptions): Turn[] {
    if (messages.length === 0) return [];
    const steeringIds = opts?.steeringMessageIds;
    const groups: Array<{ instruction: Message | null; messages: Message[] }> = [];
    for (const message of messages) {
        if (isInstructionalUserMessage(message, steeringIds)) {
            groups.push({ instruction: message, messages: [message] });
            continue;
        }
        const current = groups[groups.length - 1];
        if (current) {
            current.messages.push(message);
        } else {
            // preamble：首条指令消息之前的首部消息（如恢复会话时的系统/可视化消息）
            groups.push({ instruction: null, messages: [message] });
        }
    }
    const lastIndex = groups.length - 1;
    return groups.map((group, index) => ({
        index,
        key: `turn-${index}`,
        instruction: group.instruction,
        messages: group.messages,
        status: index === lastIndex ? 'active' : 'completed',
        startedAt: group.messages[0].timestamp,
        endedAt: group.messages[group.messages.length - 1].timestamp,
    }));
}

/**
 * 按消息 uuid 反查所属轮次 index；未命中返回 -1。
 * （Message 仅有 uuid 字段，参数名保留 messageIdOrUuid 以兼容调用方语义。）
 */
export function findTurnIndexByMessageId(
    turns: Turn[],
    messageIdOrUuid: string,
): number {
    for (const turn of turns) {
        for (const message of turn.messages) {
            if (message.uuid === messageIdOrUuid) return turn.index;
        }
    }
    return -1;
}
