import { describe, expect, it } from 'vitest';
import type { Message, ToolCallState } from '@/types';
import { projectTurnFileChanges } from './turnFileChanges';
const message = (id: string, toolName = 'Edit', path = '/src/a.ts', isError = false): Message => ({
    type: 'assistant', uuid: id, timestamp: 1, stopReason: '', usage: { inputTokens: 0, outputTokens: 0, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
    content: [{ type: 'tool_use', toolUseId: id, toolName, input: { file_path: path, old_string: 'old', new_string: 'new', content: 'written' }, result: { isError, content: 'done', metadata: { filePath: path, diff: '-actual\n+replacement' } } }],
});
describe('turn file changes', () => {
    it('uses actual diff, deduplicates ids and keeps per-file execution order', () => {
        const files = projectTurnFileChanges([message('a'), message('b', 'Write'), message('a')]);
        expect(files).toHaveLength(1);
        expect(files[0].operations.map(o => o.id)).toEqual(['a', 'b']);
        expect(files[0].operations[0].content).toBe('-actual\n+replacement');
        expect(files[0].operations[1]).toMatchObject({ kind: '写入', label: '写入内容', content: 'written' });
    });
    it('excludes errors, read/search/shell, unsupported delete and partial batch tools', () => {
        expect(projectTurnFileChanges([message('e', 'Edit', '/a', true), ...['Read','Grep','Bash','Delete','MultiEdit','mcp_write'].map(n => message(n,n))])).toEqual([]);
    });
    it('does not infer success from input, or mix other turns via live tools', () => {
        const m = message('a'); if (m.type !== 'assistant' || m.content[0].type !== 'tool_use') throw Error();
        delete m.content[0].result;
        const live = new Map<string, ToolCallState>([['other', { toolName: 'Write', input: {}, status: 'completed', startTime: 1, result: { isError: false, content: '', metadata: { filePath: '/other' } } }]]);
        expect(projectTurnFileChanges([m], live)).toEqual([]);
        for (const status of ['pending', 'running', 'error', 'permission_needed'] as const) {
            live.set('a', { toolName: 'Edit', input: {}, status, startTime: 1 });
            expect(projectTurnFileChanges([message('a')], live)).toEqual([]);
        }
    });
    it('supports separate historical result blocks and missing content without reading disk', () => {
        const m = message('a'); if (m.type !== 'assistant' || m.content[0].type !== 'tool_use') throw Error();
        delete m.content[0].result; m.content[0].input = {};
        const result: Message = { type: 'user', uuid: 'r', timestamp: 2, content: [{ type: 'tool_result', toolUseId: 'a', isError: false, content: 'ok', metadata: { filePath: '/a' } }] };
        expect(projectTurnFileChanges([m,result])[0].operations[0].content).toBeUndefined();
        expect(projectTurnFileChanges([result])).toEqual([]);
    });
    it('recognizes metadata-free native historical receipts, but not generic success text', () => {
        const m = message('history');
        if (m.type !== 'assistant' || m.content[0].type !== 'tool_use') throw Error();
        m.content[0].result = { content: 'Edited: /src/a.ts', isError: false, metadata: {} };
        expect(projectTurnFileChanges([m])[0]).toMatchObject({ path: '/src/a.ts', operations: [{ label: '修改片段', content: '- old\n+ new' }] });
        m.content[0].result.isError = true;
        expect(projectTurnFileChanges([m])).toEqual([]);
        m.content[0].result = { content: 'Will edit: /src/a.ts', isError: false };
        expect(projectTurnFileChanges([m])).toEqual([]);
        m.content[0].toolName = 'Write';
        m.content[0].result.content = 'update: /src/a.ts';
        expect(projectTurnFileChanges([m])[0].operations[0].kind).toBe('写入');
    });

    it('does not merge relative and absolute paths, or treat absent metadata as proof', () => {
        expect(projectTurnFileChanges([message('a','Edit','src/a'), message('b','Edit','/src/a')])).toHaveLength(2);
        const m = message('a'); if (m.type !== 'assistant' || m.content[0].type !== 'tool_use') throw Error();
        m.content[0].result = { content: 'ok', isError: false };
        expect(projectTurnFileChanges([m])).toEqual([]);
    });
});
