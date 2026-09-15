/**
 * turnUtils — 轮次分组视图（三层模型：完整 query ｜ 过程区 ｜ 完整回复）的纯函数层
 *
 * 与状态层（store/selectors/turnProjection、store/selectors/turnSections、
 * store/turnViewStore）配合，承接装配层可抽离的全部纯逻辑，供组件与测试复用：
 * - resolveTurnOutcome：轮次结果（成功/出错/被中断）→ 状态点颜色
 * - formatTurnDuration：起止时间差格式化（2m34s / <1s）
 * - resolveSectionStatus：任务分节状态推导（蓝=运行/绿=完成/红=失败/琥珀=中断，
 *   与过程区聚合条/分节条状态点同一语义）
 * - planTurnDeepLink：深链定位（消息 uuid → 轮次 index）
 */

import type { ToolCallState } from '@/types';
import {
    findTurnIndexByMessageId,
    type Turn,
} from '@/store/selectors/turnProjection';
import type { TurnTaskSection } from '@/store/selectors/turnSections';
import { isCancelledResult, resolveToolCallState } from '../toolCallState';

// ==================== 轮次结果 ====================

export type TurnOutcome = 'success' | 'error' | 'interrupted';

/** 判定为「出错」的 system subtype（与 api/dispatch.ts 写入值一致） */
const ERROR_SUBTYPES = new Set(['error', 'provider_error']);
/** 判定为「被中断」的 system subtype */
const INTERRUPT_SUBTYPE = 'interrupt';

/**
 * 轮次结果推导：轮内 system 消息 subtype 含 error/provider_error → 'error'；
 * 否则含 interrupt → 'interrupted'；否则 'success'。error 优先于 interrupt。
 */
export function resolveTurnOutcome(turn: Turn): TurnOutcome {
    let interrupted = false;
    for (const message of turn.messages) {
        if (message.type !== 'system') continue;
        if (message.subtype && ERROR_SUBTYPES.has(message.subtype)) return 'error';
        if (message.subtype === INTERRUPT_SUBTYPE) interrupted = true;
    }
    return interrupted ? 'interrupted' : 'success';
}

// ==================== 耗时格式化 ====================

/**
 * 起止时间差（epoch millis）→ 短耗时串：
 * <1s（不足 1 秒）/ Ns（不足 1 分钟）/ NmNNs（不足 1 小时）/ NhNNm。
 */
export function formatTurnDuration(startedAt: number, endedAt: number): string {
    const ms = Math.max(0, endedAt - startedAt);
    if (ms < 1000) return '<1s';
    const totalSec = Math.floor(ms / 1000);
    if (totalSec < 60) return `${totalSec}s`;
    const pad = (n: number) => String(n).padStart(2, '0');
    const minutes = Math.floor(totalSec / 60);
    if (minutes < 60) return `${minutes}m${pad(totalSec % 60)}s`;
    return `${Math.floor(minutes / 60)}h${pad(minutes % 60)}m`;
}

// ==================== 任务分节状态 ====================

/** 分节状态：蓝=运行 / 绿=完成 / 红=失败 / 琥珀=中断（与 TurnCard 状态点同一语义） */
export type TaskSectionStatus = 'running' | 'completed' | 'error' | 'interrupted';

export interface ResolveSectionStatusOptions {
    /** 所属轮是否为最后一轮（active） */
    isActiveTurn: boolean;
    /** run 进行中（streaming / waiting_permission） */
    isRunActive: boolean;
    /** 是否轮内最后一个分节（活跃轮运行中时视为当前任务） */
    isLastSection: boolean;
    /** 轮次终态只作用于最后一个任务，不改变此前已完成任务的状态。 */
    turnOutcome?: TurnOutcome;
}

/**
 * 任务分节状态推导：扫描分节内 tool_use 块 —
 * - 任一工具实时运行中（仅活跃轮且 run 进行中才算；历史轮遗留的无结果工具
 *   不误判 running —— 其运行早已终结）→ 'running'；
 * - 否则任一工具失败（result.isError，不含已取消）→ 'error'；
 * - 否则任一工具已取消 → 'interrupted'；
 * - 活跃轮运行中且为最后一个分节（当前任务，工具可能尚未挂入消息流）→ 'running'；
 * - 轮已停止时，最后任务同时继承轮次失败/中断；失败优先于中断；
 * - 否则 'completed'。
 */
export function resolveSectionStatus(
    section: TurnTaskSection,
    activeToolCalls: Map<string, ToolCallState> | undefined,
    opts: ResolveSectionStatusOptions,
): TaskSectionStatus {
    let hasError = false;
    let hasCancelled = false;
    let hasLive = false;
    for (const message of section.messages) {
        if (message.type !== 'assistant') continue;
        for (const block of message.content) {
            if (block.type !== 'tool_use') continue;
            const tc = resolveToolCallState(block, activeToolCalls);
            if (isCancelledResult(tc.result)) {
                hasCancelled = true;
                continue;
            }
            if (tc.status === 'error' || tc.result?.isError === true) {
                hasError = true;
                continue;
            }
            if ((tc.status === 'running' || tc.status === 'pending')
                && opts.isActiveTurn && opts.isRunActive) {
                hasLive = true;
            }
        }
    }
    if (hasLive) return 'running';
    const terminalOutcome = opts.isLastSection && !section.isPrep
        && !(opts.isActiveTurn && opts.isRunActive) ? opts.turnOutcome : undefined;
    if (hasError || terminalOutcome === 'error') return 'error';
    if (hasCancelled || terminalOutcome === 'interrupted') return 'interrupted';
    if (opts.isActiveTurn && opts.isRunActive && opts.isLastSection) return 'running';
    return 'completed';
}

// ==================== 深链定位 ====================

export interface TurnDeepLinkPlan {
    /** 目标消息所属轮次 index（同时是 Virtuoso 的 item index） */
    turnIndex: number;
    /** 目标消息 uuid（轮内 data-message-uuid 锚点） */
    messageId: string;
}

/**
 * 深链定位：消息 uuid → 所属轮次。未命中返回 null（调用方按「消费掉本次跳转」处理，
 * 与平铺路径 index<0 即 consumePendingMessage 的语义一致）。
 */
export function planTurnDeepLink(turns: Turn[], messageId: string): TurnDeepLinkPlan | null {
    const turnIndex = findTurnIndexByMessageId(turns, messageId);
    return turnIndex >= 0 ? { turnIndex, messageId } : null;
}
