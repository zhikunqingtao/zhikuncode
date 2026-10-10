import type { MessageStoreState } from '@/store/messageStore';
import type { ToolCallState } from '@/types';
import { parseEditDiffResult } from '@/utils/structuredToolResult';
import { parseGrepOutput } from '@/components/message/renderers/SearchResultRenderer';
import { record, resultPhase } from '../runtime/state';
import type { FileState, GalaxyFile } from './scene';
export type ToolKind = 'read' | 'edit' | 'search' | 'tool';
export interface ToolView {
    id: string;
    name: string;
    kind: ToolKind;
    paths: string[];
    result: ReturnType<typeof resultPhase>;
    actualDiff: boolean;
}
export interface ProjectedFile extends GalaxyFile {
    state: FileState;
}
const READ = new Set(['Read', 'FileRead', 'FileReadTool', 'ReadFile', 'NotebookRead']);
const EDIT = new Set(['Edit', 'MultiEdit', 'FileEdit', 'FileEditTool', 'Write', 'FileWrite', 'FileWriteTool', 'NotebookEdit']);
const GREP = new Set(['Grep', 'GrepTool']);
const GLOB = new Set(['Glob', 'GlobTool']);
/** Lexical only: no filesystem access, command parsing, or inferred directory contents. */
export function normalizeFilePath(value: unknown): string | null {
    if (typeof value !== 'string' || !value.trim() || value.length > 4096 || /[\n\r\0]/.test(value))
        return null;
    const path = value.trim().replace(/\\/g, '/');
    if (path.endsWith('/') || /^(https?|data):/i.test(path))
        return null;
    const parts: string[] = [];
    for (const part of path.split('/')) {
        if (!part || part === '.')
            continue;
        if (part === '..' && parts.length && parts.at(-1) !== '..')
            parts.pop();
        else
            parts.push(part);
    }
    return parts.length ? (path.startsWith('/') ? '/' : '') + parts.join('/') : null;
}
export function projectTool(id: string, call: Pick<ToolCallState, 'toolName' | 'input' | 'result'>): ToolView {
    const name = call.toolName, input = record(call.input), result = resultPhase(call.result);
    const kind: ToolKind = READ.has(name) ? 'read' : EDIT.has(name) ? 'edit' : GREP.has(name) || GLOB.has(name) ? 'search' : 'tool';
    const actual = result === 'succeeded' ? parseEditDiffResult(call.result?.metadata) : null;
    const candidates: unknown[] = [];
    if (kind === 'read' || kind === 'edit')
        candidates.push(actual?.filePath ?? input?.file_path ?? input?.path ?? input?.notebook_path);
    if (result === 'succeeded' && GREP.has(name))
        candidates.push(...parseGrepOutput(call.result?.content ?? '').keys());
    if (result === 'succeeded' && GLOB.has(name)) {
        // Only path-shaped rows of the documented newline-list result; no command/MCP guessing.
        candidates.push(...(call.result?.content ?? '').split('\n').filter(line => /^(?:\.?\.?\/|\/|[^\s]+\/|[^\s/]+\.[\w-]+$)/.test(line.trim())));
    }
    const paths = [...new Set(candidates.map(normalizeFilePath).filter((p): p is string => p !== null))].slice(0, 128);
    return { id, name, kind, paths, result, actualDiff: Boolean(actual?.diff) };
}
/** Weak object cache avoids reparsing unchanged result bodies on streaming updates. */
export function createToolProjection() {
    let cache = new WeakMap<object, ToolView>();
    return {
        read(state: Pick<MessageStoreState, 'messages' | 'activeToolCalls'>): Map<string, ToolView> {
            const tools = new Map<string, ToolView>();
            const add = (id: string, call: Pick<ToolCallState, 'toolName' | 'input' | 'result'>) => {
                let view = cache.get(call);
                if (!view) {
                    view = projectTool(id, call);
                    cache.set(call, view);
                }
                tools.set(id, view);
            };
            for (const message of state.messages)
                if (message.type === 'assistant') {
                    for (const block of message.content)
                        if (block.type === 'tool_use')
                            add(block.toolUseId, block);
                }
            for (const [id, call] of state.activeToolCalls)
                add(id, call);
            return tools;
        },
        reset() { cache = new WeakMap(); },
    };
}
export function projectFiles(session: string, tools: Iterable<ToolView>, activeId?: string): ProjectedFile[] {
    const values = [...tools];
    const files = new Map<string, ProjectedFile>();
    for (const tool of values)
        for (const path of tool.paths) {
            const old = files.get(path);
            const state: FileState = tool.result === 'succeeded' ? tool.kind === 'edit' ? 'changed' : 'read' : 'discovered';
            files.set(path, { id: `${session}|${path}`, path, label: path.split('/').pop()!, state: old?.state === 'changed' ? 'changed' : state === 'discovered' && old ? old.state : state });
        }
    const active = values.find(tool => tool.id === activeId);
    // A bounded display window, not a cap on observed business state.
    const chosen = [...files.values()].slice(-16);
    for (const path of active?.paths ?? []) {
        const file = files.get(path);
        if (file && !chosen.includes(file)) {
            chosen.shift();
            chosen.push(file);
        }
    }
    return chosen;
}

/** Resolve from business identity, never the last mounted virtual-list row. */
export function currentReplyMessageId(state: Pick<MessageStoreState, 'messages' | 'streamingMessageId'>): string | null {
    if (state.streamingMessageId) return state.streamingMessageId;
    for (let i = state.messages.length - 1; i >= 0; i--) {
        const message = state.messages[i];
        if (message.type === 'user') return null;
        if (message.type === 'assistant' && message.content.some(block => block.type === 'text' && block.text.length > 0)) return message.uuid;
    }
    return null;
}
