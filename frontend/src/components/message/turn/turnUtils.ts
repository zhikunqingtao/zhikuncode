/**
 * turnUtils — 轮次分组视图（compact/balanced 路径）的纯函数层
 *
 * 与状态层（store/selectors/turnProjection、store/turnViewStore）配合，
 * 承接 MessageList 装配层可抽离的全部纯逻辑，供组件与测试复用：
 * - resolveTurnOutcome：轮次结果（成功/出错/被中断）→ 状态点颜色
 * - formatTurnDuration：起止时间差格式化（2m34s / <1s）
 * - extractTurnText：拼接轮内可复制文本（「复制本轮」，默认排除 user 指令）
 * - instructionFirstLine：指令首行预览（卡片头粗体截断）
 * - computeTurnOrdinals：「第 N 轮」序号（只数有指令的轮，preamble 为 null）
 * - resolveTurnExpandedStates：逐轮展开态求值（resolveTurnExpanded 的装配层集成）
 * - planTurnDeepLink：深链定位（消息 uuid → 轮次 index）
 */

import type { Message, ToolCallState } from '@/types';
import {
    findTurnIndexByMessageId,
    type Turn,
} from '@/store/selectors/turnProjection';
import {
    resolveTurnExpanded,
    type TurnDensity,
} from '@/store/turnViewStore';
import { extractMessageText } from '@/utils/messageContent';
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

// ==================== 文本提取 ====================

export interface ExtractTurnTextOptions {
    /**
     * 是否包含 user 消息文本（指令/steering）。默认 false ——
     * 「复制本轮」只复制助手产出（assistant/system 文本），不混入用户指令。
     */
    includeUser?: boolean;
}

/**
 * 拼接轮内消息的 text 块文本（Markdown 源码），消息间空行分隔。
 * 默认排除 user 消息（复制本轮 = 助手产出）；includeUser: true 时含指令文本。
 * 无可复制文本时返回 null（调用方据此隐藏「复制本轮」按钮）。
 */
export function extractTurnText(turn: Turn, opts?: ExtractTurnTextOptions): string | null {
    const includeUser = opts?.includeUser ?? false;
    const parts: string[] = [];
    for (const message of turn.messages) {
        if (!includeUser && message.type === 'user') continue;
        const text = extractMessageText(message);
        if (text) parts.push(text);
    }
    return parts.length > 0 ? parts.join('\n\n') : null;
}

/** 指令消息首行预览（卡片头粗体截断）；纯图片指令给占位文案 */
export function instructionFirstLine(instruction: Message): string {
    const text = extractMessageText(instruction);
    if (text) return text.split('\n')[0];
    return '[图片]';
}

// ==================== 轮次工具统计 ====================

/** 计入「文件变更」的工具名（与后端 CC 风格命名一致） */
const FILE_CHANGE_TOOL_NAMES = new Set(['Edit', 'Write', 'MultiEdit', 'NotebookEdit']);
/** meta 行展示的 top 工具名个数 */
const TOP_TOOL_NAMES_LIMIT = 3;

export interface TurnToolStats {
    /** 轮内 tool_use 块总数 */
    total: number;
    /** 工具名计数前 3（计数降序，平手按首次出现序） */
    topNames: Array<[string, number]>;
    /** 唯一文件变更数（Edit/Write/MultiEdit/NotebookEdit 的 input.file_path 去重） */
    filesChanged: number;
    /** 失败工具数（result.isError，不含已取消） */
    errorCount: number;
    /** 取消工具数（result.metadata.executionStatus === 'cancelled'） */
    cancelledCount: number;
    /**
     * 工具耗时累加（仅 activeToolCalls 实时条目携带 duration 时非 0；
     * 历史轮 tool_use block 不持久化耗时，为 0）
     */
    totalDurationMs: number;
}

/**
 * 轮次工具统计（TurnCard meta 行数据源）。O(轮内块数)，不改输入。
 * activeToolCalls 可选：传入时按共享配对逻辑解析实时状态与耗时。
 */
export function turnToolStats(
    turn: Turn,
    activeToolCalls?: Map<string, ToolCallState>,
): TurnToolStats {
    let total = 0;
    let errorCount = 0;
    let cancelledCount = 0;
    let totalDurationMs = 0;
    const nameCounts = new Map<string, number>();
    const changedFiles = new Set<string>();

    for (const message of turn.messages) {
        if (message.type !== 'assistant') continue;
        for (const block of message.content) {
            if (block.type !== 'tool_use') continue;
            total += 1;
            nameCounts.set(block.toolName, (nameCounts.get(block.toolName) ?? 0) + 1);

            if (FILE_CHANGE_TOOL_NAMES.has(block.toolName)) {
                const filePath = block.input?.file_path;
                if (typeof filePath === 'string' && filePath.length > 0) {
                    changedFiles.add(filePath);
                }
            }

            const resolved = resolveToolCallState(block, activeToolCalls);
            if (isCancelledResult(resolved.result)) {
                cancelledCount += 1;
            } else if (resolved.status === 'error' || resolved.result?.isError === true) {
                errorCount += 1;
            }
            if (typeof resolved.duration === 'number') {
                totalDurationMs += resolved.duration;
            }
        }
    }

    const topNames = Array.from(nameCounts.entries())
        .sort((a, b) => b[1] - a[1])
        .slice(0, TOP_TOOL_NAMES_LIMIT);

    return {
        total,
        topNames,
        filesChanged: changedFiles.size,
        errorCount,
        cancelledCount,
        totalDurationMs,
    };
}

// ==================== 结论预览 ====================

/** 结论预览最大字符数（超出截断并追加省略号） */
export const TURN_CONCLUSION_PREVIEW_MAX = 80;

/**
 * 「结论预览」：轮内最后一个 assistant text 块的首个非空行，
 * 截断至 ~80 字符；无 assistant 文本时返回 null。
 */
export function turnConclusionPreview(turn: Turn): string | null {
    let lastText: string | null = null;
    for (const message of turn.messages) {
        if (message.type !== 'assistant') continue;
        for (const block of message.content) {
            if (block.type === 'text') lastText = block.text;
        }
    }
    if (lastText === null) return null;
    const firstLine = lastText
        .split('\n')
        .map(line => line.trim())
        .find(line => line.length > 0);
    if (!firstLine) return null;
    return firstLine.length > TURN_CONCLUSION_PREVIEW_MAX
        ? `${firstLine.slice(0, TURN_CONCLUSION_PREVIEW_MAX)}…`
        : firstLine;
}

// ==================== 轮次序号 ====================

/**
 * 「第 N 轮」序号：只数有指令的轮（1 起）；preamble 轮为 null（不渲染卡片头）。
 * 返回数组下标 = turn.index。
 */
export function computeTurnOrdinals(turns: Turn[]): Array<number | null> {
    let count = 0;
    return turns.map(turn => (turn.instruction ? (count += 1) : null));
}

// ==================== 展开态装配 ====================

/**
 * 逐轮展开态求值（MessageList 装配层）：逐轮委托 resolveTurnExpanded。
 * 返回数组下标 = turn.index。
 */
export function resolveTurnExpandedStates(
    turns: Turn[],
    density: TurnDensity,
    isRunActive: boolean,
    overridesForSession?: Record<number, boolean>,
): boolean[] {
    return turns.map(turn =>
        resolveTurnExpanded(density, turn.index, turns.length, isRunActive, overridesForSession));
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
