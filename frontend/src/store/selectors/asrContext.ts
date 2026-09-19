/**
 * AsrContext — ASR 语音识别上下文构建（纯函数，无状态）
 *
 * 语音转文字时，把最近若干轮「用户 query + 助手最终回复」拼成上下文文本，
 * 随音频一起提交给 qwen3-asr-flash（后端将其作为 system 消息注入请求首位），
 * 利用模型的词表匹配机制（上下文中出现过的词识别更准）提高专有名词识别准确率。
 *
 * 规则：
 * - 复用轮次投影（turnProjection.buildTurns）与三层切分（turnSections.splitTurnLayers）；
 * - 只收集「instruction 非空 且 answer 非空」的轮次，最多 3 轮
 *   （运行中/无 answer 的轮跳过，继续往前取）；
 * - 用户输入只取每轮的初始 query（instruction），不含 steering 干预消息；
 * - instruction / answer 各自拼接全部 type='text' 块文本并 trim（忽略 image 等其他块）；
 * - 拼装格式（按时间旧→新排列）：每轮 `用户：{query}\n助手：{answer}`，轮之间空行分隔；
 * - 总长 ≤ 1500 字符，优先保留最近轮：按新→旧累加，加入某轮会使总长超限时
 *   将该轮文本截断到剩余预算（保留前段）后停止；剩余预算 <20 字符时直接停止；
 * - 无合格轮返回空字符串。
 *
 * 注意：turnProjection 的 status 按位置标注（末轮恒为 'active'），不代表运行态，
 * 故不按 status 过滤；真正运行中的轮没有最终回复，自然被「answer 非空」条件跳过。
 */

import type { Message } from '@/types';
import { buildTurns } from './turnProjection';
import { splitTurnLayers } from './turnSections';

/** 上下文总长度上限（字符） */
export const ASR_CONTEXT_MAX_LENGTH = 1500;
/** 加入一轮所需的最低剩余预算（字符），低于此值直接停止 */
const MIN_REMAINING_BUDGET = 20;
/** 最多收集的轮次数 */
const MAX_TURNS = 3;
/** 轮间分隔符（空行） */
const TURN_SEPARATOR = '\n\n';

/** 提取消息全部 type='text' 块文本拼接并 trim；非 user/assistant 消息或无 text 块返回空串 */
function textOf(message: Message | null): string {
    if (!message) return '';
    if (message.type !== 'user' && message.type !== 'assistant') return '';
    let text = '';
    for (const block of message.content) {
        if (block.type === 'text') text += block.text;
    }
    return text.trim();
}

/**
 * 构建 ASR 识别上下文。空 messages 或无合格轮时返回空字符串。
 */
export function buildAsrContext(messages: Message[]): string {
    if (messages.length === 0) return '';

    // 新→旧收集合格轮次文本
    const turnTexts: string[] = [];
    const turns = buildTurns(messages);
    for (let index = turns.length - 1; index >= 0 && turnTexts.length < MAX_TURNS; index -= 1) {
        const { instruction, answer } = splitTurnLayers(turns[index]);
        const query = textOf(instruction);
        const reply = textOf(answer);
        // 无 query / 无最终回复（含运行中）的轮跳过，继续往前取
        if (!query || !reply) continue;
        turnTexts.push(`用户：${query}\n助手：${reply}`);
    }
    if (turnTexts.length === 0) return '';

    // 长度控制：新→旧累加（含轮间分隔符），优先保留最近轮
    const selected: string[] = [];
    let used = 0;
    for (const text of turnTexts) {
        const separatorCost = selected.length > 0 ? TURN_SEPARATOR.length : 0;
        const remaining = ASR_CONTEXT_MAX_LENGTH - used - separatorCost;
        if (remaining < MIN_REMAINING_BUDGET) break;
        if (text.length <= remaining) {
            selected.push(text);
            used += separatorCost + text.length;
        } else {
            // 截断到剩余预算（保留前段）后停止
            selected.push(text.slice(0, remaining));
            break;
        }
    }

    // 输出按时间旧→新排列，轮之间空行分隔
    return selected.reverse().join(TURN_SEPARATOR);
}
