import type { ContentBlock, Message, ToolCallState, ToolResult } from '@/types';

export interface TurnFileOperation {
    id: string;
    kind: '编辑' | '写入';
    label: '差异记录' | '修改片段' | '写入内容';
    content?: string;
}
export interface TurnFileChange { path: string; operations: TurnFileOperation[]; }
type ToolUse = Extract<ContentBlock, { type: 'tool_use' }>;

/** Only native Edit/Write contracts are verified. Never infer mutations from paths or shell text. */
export function projectTurnFileChanges(messages: Message[], live?: Map<string, ToolCallState>): TurnFileChange[] {
    const uses = new Map<string, ToolUse>();
    const results = new Map<string, ToolResult>();
    for (const message of messages) {
        if (message.type !== 'assistant' && message.type !== 'user') continue;
        for (const block of message.content) {
            if (block.type === 'tool_use' && message.type === 'assistant') {
                uses.set(block.toolUseId, block);
                if (block.result) results.set(block.toolUseId, block.result);
            } else if (block.type === 'tool_result') results.set(block.toolUseId, block);
        }
    }
    const files = new Map<string, TurnFileChange>();
    for (const [id, use] of uses) {
        if (use.toolName !== 'Edit' && use.toolName !== 'Write') continue;
        const active = live?.get(id);
        if (active && active.status !== 'completed') continue;
        const result = active?.result ?? results.get(id);
        if (!result || result.isError !== false) continue;
        const meta = result.metadata;
        if (meta?.executionStatus && meta.executionStatus !== 'succeeded') continue;
        if (meta?.effectState && meta.effectState !== 'applied' && meta.effectState !== 'APPLIED') continue;
        // Historical public messages may retain the native success receipt but omit metadata.
        // Match the complete native receipt, never arbitrary mentions of a file in output.
        const receipt = use.toolName === 'Edit'
            ? /^(?:Edited|Created): ([^\r\n]+)$/.exec(result.content)
            : /^(?:create|update): ([^\r\n]+)$/.exec(result.content);
        const path = meta?.filePath ?? receipt?.[1];
        if (typeof path !== 'string' || !path.trim()) continue;
        const input = active?.input ?? use.input;
        const params = input && typeof input === 'object' && !Array.isArray(input)
            ? input as Record<string, unknown> : {};
        let operation: TurnFileOperation;
        if (use.toolName === 'Edit') {
            const diff = typeof meta?.diff === 'string' && meta.diff.length > 0 ? meta.diff : undefined;
            const oldText = params.old_string, newText = params.new_string;
            const snippet = typeof oldText === 'string' && typeof newText === 'string'
                ? [...(oldText ? oldText.split('\n').map(line => `- ${line}`) : []),
                    ...(newText ? newText.split('\n').map(line => `+ ${line}`) : [])].join('\n') : undefined;
            operation = { id, kind: '编辑', label: diff ? '差异记录' : '修改片段', content: diff ?? snippet };
        } else {
            operation = { id, kind: '写入', label: '写入内容', content: typeof params.content === 'string' ? params.content : undefined };
        }
        const file = files.get(path) ?? { path, operations: [] };
        file.operations.push(operation);
        files.set(path, file);
    }
    return [...files.values()];
}
