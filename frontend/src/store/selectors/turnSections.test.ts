import { describe, expect, it } from 'vitest';
import type { ContentBlock, Message } from '@/types';
import { buildTurnTaskSections, findProcessExpandKey, splitTurnLayers } from './turnSections';
import { buildTurns } from './turnProjection';
import { prepExpandKey, sectionExpandKey } from '@/store/turnViewStore';

const assistant = (uuid: string, content: ContentBlock[]): Message => ({
    type: 'assistant', uuid, timestamp: 1, content, stopReason: 'tool_use',
    usage: { inputTokens: 0, outputTokens: 0, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
});
const todo = (id: string, status = 'IN_PROGRESS') => ({ id, content: id, status });
const write = (id: string, todos: unknown[], result?: unknown, isError = false) => assistant(id, [{
    type: 'tool_use', toolUseId: id, toolName: 'TodoWrite', input: { todos },
    ...(result !== undefined ? { result: { content: JSON.stringify(result), isError } } : {}),
}]);
const boundary = (uuid: string, taskId: string): Extract<Message, { type: 'system' }> => ({
    type: 'system', uuid, timestamp: 1, content: '', subtype: 'task_boundary',
    metadata: { task_id: taskId, title: taskId, seq: 1 },
});
const work = (id: string) => assistant(id, [{ type: 'tool_use', toolUseId: id, toolName: 'Read', input: {} }]);

describe('historical task sections', () => {
    it('moves a streaming segment into process as soon as it contains a tool', () => {
        const message = assistant('live', [{ type: 'text', text: 'checking' }]);
        expect(splitTurnLayers(buildTurns([message])[0], 'live').answer?.uuid).toBe('live');
        const withTool = assistant('live', [{ type: 'text', text: 'checking' }, { type: 'tool_use', toolUseId: 't', toolName: 'Read', input: {} }]);
        const layers = splitTurnLayers(buildTurns([withTool])[0], 'live');
        expect(layers.answer).toBeNull();
        expect(layers.process).toEqual([withTool]);
    });
    it('prefers successful newTodos over input and leaves the switch in preparation', () => {
        const first = write('switch', [todo('wrong')], { oldTodos: [], newTodos: [todo('A')] });
        const result = buildTurnTaskSections([first, work('read')]);
        expect(result.sections.map(s => s.title)).toEqual(['A']);
        expect(result.prep?.messages).toEqual([first]);
        expect(result.sections[0].messages.map(m => m.uuid)).toEqual(['read']);
    });
    it('agrees with boundary semantics and deep links for multiple tools in one message', () => {
        const first = write('switch-A', [todo('A')]);
        const second = write('switch-B', [todo('B')]);
        if (second.type === 'assistant') second.content.push({ type: 'tool_use', toolUseId: 'extra', toolName: 'Read', input: {} });
        const process = [first, work('read-A'), second, work('read-B')];
        const fallback = buildTurnTaskSections(process);
        const explicit = buildTurnTaskSections([first, boundary('bA', 'A'), process[1], second, boundary('bB', 'B'), process[3]]);
        expect(fallback.sections.map(s => s.messages.map(m => m.uuid))).toEqual(
            explicit.sections.map(s => s.messages.filter(m => m.type !== 'system').map(m => m.uuid)));
        const turn = buildTurns(process)[0];
        expect(findProcessExpandKey(turn, 'switch-A')).toBe(prepExpandKey(turn.index));
        expect(findProcessExpandKey(turn, 'switch-B')).toBe(sectionExpandKey(turn.index, 0));
    });
    it('supports lowercase legacy input and empty newly started sections', () => {
        const result = buildTurnTaskSections([write('legacy', [todo('A', 'in_progress')])]);
        expect(result.sections).toHaveLength(1);
        expect(result.sections[0].messages).toEqual([]);
    });
    it('does not infer a transition from a failed or malformed result', () => {
        expect(buildTurnTaskSections([write('failed', [todo('A')], { newTodos: [todo('A')] }, true)]).hasTaskData).toBe(false);
        expect(buildTurnTaskSections([write('malformed', [todo('A')], 'not json')]).hasTaskData).toBe(false);
    });
    it('deduplicates tasks by ID and ignores unchanged IN_PROGRESS results', () => {
        const result = buildTurnTaskSections([
            write('one', [], { oldTodos: [todo('already')], newTodos: [todo('already'), todo('A')] }),
            write('two', [todo('A'), todo('B')]),
        ]);
        expect(result.sections.map(s => s.title)).toEqual(['A', 'B']);
    });
    it('uses valid explicit boundaries first, ignores empty payloads and repeated UUIDs', () => {
        const b = boundary('stable', 'B');
        const result = buildTurnTaskSections([write('fallback', [todo('A')]), b, b, work('read')]);
        expect(result.sections.map(s => s.title)).toEqual(['B']);
        const malformed: Message = { ...b, metadata: {} };
        expect(buildTurnTaskSections([malformed, write('legacy', [todo('A')])]).sections[0].title).toBe('A');
    });
    it('supports content JSON and camelCase boundary payloads', () => {
        const b: Message = { type: 'system', uuid: 'b', timestamp: 1, subtype: 'task_boundary', content: JSON.stringify({ taskId: 'A', title: 'A' }) };
        expect(buildTurnTaskSections([b]).sections[0].taskId).toBe('A');
    });
});
