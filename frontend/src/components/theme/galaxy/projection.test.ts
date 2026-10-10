import { describe, expect, it } from 'vitest';
import { appendedText } from './anchors';
import { createToolProjection, currentReplyMessageId, normalizeFilePath, projectFiles, projectTool } from './projection';
import type { ToolCallState, Message } from '@/types';
const call = (toolName: string, input: unknown, result?: ToolCallState['result']): ToolCallState => ({ toolName, input, result, status: result ? 'completed' : 'running', startTime: 0 });
const success = (content = '', metadata?: Record<string, unknown>) => ({ content, isError: false, metadata });
describe('galaxy business projection', () => {
    it('normalizes file identity without guessing files from commands or directories', () => {
        expect(normalizeFilePath('./frontend/src/../package.json')).toBe('frontend/package.json');
        expect(normalizeFilePath('/tmp/a/')).toBeNull();
        expect(projectTool('t', call('Bash', { command: 'cat secret.txt' })).paths).toEqual([]);
        expect(projectTool('t', call('mcp__read', { path: 'secret.txt' })).paths).toEqual([]);
        expect(projectTool('t', call('Grep', { path: 'frontend/src', pattern: 'theme' })).paths).toEqual([]);
        expect(projectTool('t', call('Grep', { path: 'frontend' }, success('frontend/a.ts:12:hello\nfrontend/b.ts:3:world'))).paths).toEqual(['frontend/a.ts', 'frontend/b.ts']);
        expect(projectTool('t', call('Glob', {}, success('No files found'))).paths).toEqual([]);
    });
    it('uses authoritative successful diff, never prepared input or failed writes', () => {
        const metadata = { structuredResult: { schema: 'edit-diff/v1', filePath: 'actual.ts', diff: '@@ -1 +1 @@\n-x\n+y', truncated: true } };
        const input = { file_path: 'prepared.ts', old_string: 'x', new_string: 'y' };
        expect(projectTool('e', call('Edit', input)).actualDiff).toBe(false);
        const done = projectTool('e', call('Edit', input, success('', metadata)));
        expect(done).toMatchObject({ paths: ['actual.ts'], actualDiff: true, result: 'succeeded' });
        const failed = projectTool('e', call('Edit', input, { content: 'failure', isError: true, metadata }));
        expect(failed.actualDiff).toBe(false);
        expect(projectFiles('s', [failed])[0].state).toBe('discovered');
        expect(projectTool('w', call('Write', input, success())).actualDiff).toBe(false);
    });
    it('reprojects late input and results while preserving unchanged object identities', () => {
        const projection = createToolProjection(), first = call('Read', {});
        const state = { messages: [], activeToolCalls: new Map([['t', first]]) };
        const a = projection.read(state).get('t');
        expect(a?.paths).toEqual([]);
        expect(projection.read(state).get('t')).toBe(a);
        state.activeToolCalls.set('t', { ...first, input: { file_path: 'a.ts' } });
        expect(projection.read(state).get('t')?.paths).toEqual(['a.ts']);
        state.activeToolCalls.set('t', { ...first, input: { file_path: 'a.ts' }, result: success() });
        expect(projectFiles('s', projection.read(state).values())[0].state).toBe('read');
        projection.reset();
        expect(projection.read(state).get('t')).not.toBe(a);
    });
    it('keeps changed files changed after reads, differentiates paths and pins active files in a bounded display', () => {
        const tools = Array.from({ length: 30 }, (_, i) => projectTool(String(i), call('Read', { file_path: `dir${i}/index.ts` }, success())));
        const files = projectFiles('s', new Map(tools.map(t => [t.id, t])).values(), '0');
        expect(files).toHaveLength(16);
        expect(files.some(f => f.path === 'dir0/index.ts')).toBe(true);
        expect(new Set(files.map(f => f.id)).size).toBe(16);
        expect(projectFiles('other', tools)[0].id).not.toBe(projectFiles('s', tools)[0].id);
        const edited = projectTool('edit', call('Edit', { file_path: 'a.ts' }, success()));
        const read = projectTool('read', call('Read', { file_path: 'a.ts' }, success()));
        expect(projectFiles('s', [edited, read])).toHaveLength(1);
        expect(projectFiles('s', [edited, read])[0].state).toBe('changed');
    });
});
describe('rendered text incremental boundaries', () => {
    it('uses DOM UTF-16 offsets for Chinese, emoji and inline markup', () => {
        const el = document.createElement('div');
        el.innerHTML = '<p>你好<strong>星河🌌</strong></p>';
        const before = el.textContent!;
        el.querySelector('p')!.append('，继续');
        expect(appendedText(before, el.textContent!)).toEqual({ start: before.length, end: before.length + 3 });
    });
    it('does not animate hydration, replacements, unchanged commits or truncated text', () => {
        expect(appendedText(undefined, 'history')).toBeNull();
        expect(appendedText('stream', 'stream')).toBeNull();
        expect(appendedText('**hello', 'hello')).toBeNull();
        expect(appendedText('long reply', 'long')).toBeNull();
    });
});

describe('current reply identity', () => {
    const assistant = (uuid: string): Message => ({ type: 'assistant', uuid, timestamp: 0, stopReason: 'end_turn',
        content: [{ type: 'text', text: '回复' }], usage: { inputTokens: 0, outputTokens: 0, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 } });
    it('prefers the current streaming identity over committed history', () => {
        expect(currentReplyMessageId({ messages: [assistant('old')], streamingMessageId: 'live' })).toBe('live');
    });
    it('uses the committed reply after streaming finishes', () => {
        expect(currentReplyMessageId({ messages: [assistant('old'), assistant('latest')], streamingMessageId: null })).toBe('latest');
    });
    it('does not carry an earlier reply into a new unanswered user turn', () => {
        const user: Message = { type: 'user', uuid: 'new-question', timestamp: 1, content: [{ type: 'text', text: '新问题' }] };
        expect(currentReplyMessageId({ messages: [assistant('old'), user], streamingMessageId: null })).toBeNull();
    });
});
