/**
 * toolCallState — ToolCallState 解析纯函数层（无 React 依赖）
 *
 * 从 AssistantMessage 的 tool_use 渲染分支加法式抽取，供多处复用同一套
 * 「tool_use block ↔ 实时状态 / 结果」配对逻辑，避免行为发散：
 * - AssistantMessage / assistantBlockRenderer（legacy 平铺路径 + 轮内块级渲染）
 * - turn/ToolRunBlock（轮级过程清单的工具聚合段）
 * - turn/TaskSection（分节工具摘要行）、turn/TurnProcessArea（聚合条状态点与
 *   实时工具判定）、turn/turnUtils.resolveSectionStatus（分节状态推导）
 *
 * 配对语义（与抽取前 AssistantMessage 完全一致）：
 * 1. activeToolCalls 命中 → 以实时状态为准；但实时 input 为空对象时
 *    回退使用 block.input（P1 兜底：tool_use_input 事件未到达的场景）；
 * 2. 未命中 → 由 block 合成：有 result 按 isError 判 completed/error，
 *    无 result 视为 running（不得误标 completed）；startTime 置 0（无计时）。
 */

import type { ContentBlock, ToolCallState, ToolResult } from '@/types';

/** tool_use 内容块（ContentBlock 封闭 union 的判别子类型） */
export type ToolUseBlock = Extract<ContentBlock, { type: 'tool_use' }>;

/**
 * 解析单个 tool_use block 的渲染用 ToolCallState。
 * 不修改入参；返回新对象（activeToolCalls 命中时为展开副本）。
 */
export function resolveToolCallState(
    block: ToolUseBlock,
    activeToolCalls?: Map<string, ToolCallState>,
): ToolCallState {
    // Try to find state from activeToolCalls, fallback to basic info
    const state = activeToolCalls?.get(block.toolUseId);
    // P1 兑底：activeToolCalls 命中但 input 为空对象时，回退使用 block.input
    const activeInputEmpty = !!state
        && state.input != null
        && typeof state.input === 'object'
        && !Array.isArray(state.input)
        && Object.keys(state.input as Record<string, unknown>).length === 0;
    return state
        ? { ...state, input: activeInputEmpty ? block.input : state.input }
        : {
            toolName: block.toolName,
            input: block.input,
            // 无 result 的工具仍在执行中，不得误标 completed
            status: block.result ? (block.result.isError ? 'error' : 'completed') : 'running',
            result: block.result,
            startTime: 0,
        };
}

/**
 * 「已取消」结果判定：后端 tool_result payload 的 executionStatus='cancelled'
 * 经 metadata 透传时识别。ToolCallState.status 无 cancelled 分支，
 * 取消语义只能从 result.metadata 防御性读取（无则不算取消）。
 */
export function isCancelledResult(result?: ToolResult): boolean {
    return result?.metadata?.executionStatus === 'cancelled';
}
