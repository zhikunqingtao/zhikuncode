/**
 * sequence-diagram-builder — 从 MessageStore 数据构建 Mermaid sequenceDiagram
 *
 * 工具函数：提取工具调用记录 → 生成 Mermaid 语法
 */

import type { Message, ContentBlock } from '@/types';

export interface ToolCallRecord {
    toolUseId: string;
    toolName: string;
    input: Record<string, unknown>;
    result?: string;
    isError?: boolean;
    startTime?: number;
    endTime?: number;
}

/**
 * 从 messageStore.messages 中提取工具调用记录
 * 遍历 assistant 消息的 content blocks，匹配 tool_use 与 tool_result
 */
export function extractToolCalls(messages: Message[]): ToolCallRecord[] {
    const records = new Map<string, ToolCallRecord>();

    for (const msg of messages) {
        if (msg.type !== 'assistant' && msg.type !== 'user') continue;
        if (!('content' in msg) || !Array.isArray(msg.content)) continue;

        for (const block of msg.content as ContentBlock[]) {
            if (block.type === 'tool_use') {
                records.set(block.toolUseId, {
                    toolUseId: block.toolUseId,
                    toolName: block.toolName,
                    input: block.input,
                    startTime: msg.timestamp,
                });
            } else if (block.type === 'tool_result') {
                const existing = records.get(block.toolUseId);
                if (existing) {
                    existing.result = block.content;
                    existing.isError = block.isError;
                    existing.endTime = msg.timestamp;
                } else {
                    records.set(block.toolUseId, {
                        toolUseId: block.toolUseId,
                        toolName: 'unknown',
                        input: {},
                        result: block.content,
                        isError: block.isError,
                        endTime: msg.timestamp,
                    });
                }
            }
        }
    }

    return Array.from(records.values());
}

/**
 * 参数摘要：将工具输入转为简短可读字符串
 */
export function summarizeInput(
    input: Record<string, unknown>,
    maxLen = 50
): string {
    const keys = Object.keys(input);
    if (keys.length === 0) return '';

    const parts: string[] = [];
    for (const key of keys.slice(0, 2)) {
        const val = input[key];
        let valStr: string;
        if (typeof val === 'string') {
            valStr = val.length > 30 ? val.slice(0, 27) + '...' : val;
        } else if (val === null || val === undefined) {
            valStr = String(val);
        } else {
            valStr = JSON.stringify(val);
            if (valStr.length > 30) valStr = valStr.slice(0, 27) + '...';
        }
        parts.push(`${key}: "${valStr}"`);
    }

    let result = parts.join(', ');
    if (result.length > maxLen) {
        result = result.slice(0, maxLen - 3) + '...';
    }
    return result;
}

/**
 * 响应摘要：将工具结果转为简短可读字符串
 */
function summarizeResult(record: ToolCallRecord): string {
    if (record.isError) {
        const errText = record.result || 'Error';
        return `错误: ${errText.slice(0, 30)}`;
    }
    if (!record.result) return '成功';

    const text = record.result;
    // 尝试推断有用信息
    const lines = text.split('\n').length;
    if (lines > 3) return `成功 (${lines}行)`;
    if (text.length > 50) return `成功 (${text.length}字符)`;
    return '成功';
}

/**
 * 计算耗时字符串
 */
function formatDuration(record: ToolCallRecord): string {
    if (!record.startTime || !record.endTime) return '';
    const ms = record.endTime - record.startTime;
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
}

/**
 * 转义 Mermaid 序列图中的特殊字符
 */
function escapeMermaid(text: string): string {
    return text
        .replace(/[#;]/g, ' ')
        .replace(/"/g, "'")
        .replace(/\n/g, ' ')
        .replace(/[<>{}]/g, '');
}

/**
 * 生成工具的短别名（用作 participant ID）
 */
function toolAlias(toolName: string): string {
    // 移除 Tool/Service 后缀，取首字母大写缩写
    const clean = toolName.replace(/Tool$|Service$/i, '');
    if (clean.length <= 6) return clean;
    // 取大写字母组成缩写
    const caps = clean.replace(/[a-z]/g, '');
    if (caps.length >= 2) return caps;
    return clean.slice(0, 6);
}

/**
 * 将工具调用记录转换为 Mermaid sequenceDiagram 语法
 */
export function buildSequenceDiagram(
    toolCalls: ToolCallRecord[],
    options?: {
        timeRange?: { start: number; end: number };
        toolFilter?: string[];
        maxParamLength?: number;
    }
): string {
    const maxParamLen = options?.maxParamLength ?? 50;

    // 应用过滤
    let filtered = toolCalls;
    if (options?.timeRange) {
        const { start, end } = options.timeRange;
        filtered = filtered.filter(tc => {
            const t = tc.startTime ?? tc.endTime ?? 0;
            return t >= start && t <= end;
        });
    }
    if (options?.toolFilter && options.toolFilter.length > 0) {
        const allowed = new Set(options.toolFilter);
        filtered = filtered.filter(tc => allowed.has(tc.toolName));
    }

    if (filtered.length === 0) return '';

    // 收集唯一工具名
    const toolNames = [...new Set(filtered.map(tc => tc.toolName))];

    // 生成唯一 participant ID
    const aliasMap = new Map<string, string>();
    const usedAliases = new Set<string>();
    for (const name of toolNames) {
        let alias = toolAlias(name);
        let suffix = 1;
        while (usedAliases.has(alias)) {
            alias = toolAlias(name) + suffix++;
        }
        usedAliases.add(alias);
        aliasMap.set(name, alias);
    }

    const lines: string[] = ['sequenceDiagram'];
    lines.push('    participant U as User');
    lines.push('    participant A as Agent');
    for (const name of toolNames) {
        lines.push(`    participant ${aliasMap.get(name)} as ${escapeMermaid(name)}`);
    }

    // 起始消息
    lines.push('    U->>A: 用户请求');

    // 按顺序生成调用
    for (const tc of filtered) {
        const alias = aliasMap.get(tc.toolName)!;
        const paramSummary = summarizeInput(tc.input, maxParamLen);
        const callLabel = paramSummary
            ? `${escapeMermaid(tc.toolName)}(${escapeMermaid(paramSummary)})`
            : escapeMermaid(tc.toolName);

        lines.push(`    A->>${alias}: ${callLabel}`);

        // 响应
        const duration = formatDuration(tc);
        const resultSummary = summarizeResult(tc);
        const respLabel = duration
            ? `${escapeMermaid(resultSummary)} (${duration})`
            : escapeMermaid(resultSummary);

        const arrow = tc.isError ? `${alias}--xA` : `${alias}-->>A`;
        lines.push(`    ${arrow}: ${respLabel}`);
    }

    // 结束消息
    lines.push('    A-->>U: 回复完成');

    return lines.join('\n');
}

/**
 * 获取工具调用记录中所有唯一工具名
 */
export function getUniqueToolNames(toolCalls: ToolCallRecord[]): string[] {
    return [...new Set(toolCalls.map(tc => tc.toolName))].sort();
}
