import { describe, expect, it } from 'vitest';
import type { Message, ToolCallState, ToolResult } from '@/types';
import { projectTurnPublications } from './turnPublications';

const result: ToolResult = { content: 'uploaded', isError: false, metadata: { structuredResult: {
    schema: 'external-resource/v1', kind: 'download', provider: 'oss', url: 'https://files.example.com/a.pptx',
    label: 'a.pptx', size: 1024, sha256: 'a'.repeat(64), objectKey: 'a.pptx',
    mimeType: 'application/octet-stream', permanentlyPublic: true, downloadExpected: true,
} } };
const message = (r?: ToolResult): Message => ({ type: 'assistant', uuid: 'a', timestamp: 1,
    content: [{ type: 'tool_use', toolUseId: 't', toolName: 'PublishArtifact', input: {}, result: r }],
} as Message);
const live = (r = result, status: ToolCallState['status'] = 'completed') => new Map<string, ToolCallState>([
    ['t', { toolName: 'PublishArtifact', input: {}, status, startTime: 0, result: r }],
]);

describe('projectTurnPublications', () => {
    it('deduplicates embedded and standalone historical results', () => {
        const carrier = { type: 'user', uuid: 'r', timestamp: 2,
            content: [{ type: 'tool_result', toolUseId: 't', ...result }] } as Message;
        expect(projectTurnPublications([message(result), carrier])).toHaveLength(1);
        expect(projectTurnPublications([carrier])).toHaveLength(1);
    });
    it('keeps live-only results in their streaming turn and restores from history', () => {
        expect(projectTurnPublications([], live())).toEqual([]);
        const cards = projectTurnPublications([], live(), true);
        expect(cards).toHaveLength(1);
        expect(projectTurnPublications([message(result)])).toEqual(cards);
        expect(projectTurnPublications([message()], live())).toEqual(cards);
    });
    it('does not promote running, cancelled, failed downloads or malformed results', () => {
        expect(projectTurnPublications([message(result)], live(result, 'running'))).toEqual([]);
        for (const r of [
            { ...result, isError: true },
            { ...result, metadata: { ...result.metadata, executionStatus: 'cancelled' } },
            { ...result, metadata: {} },
            { ...result, metadata: { structuredResult: { ...result.metadata!.structuredResult as object, url: 'javascript:alert(1)' } } },
        ]) expect(projectTurnPublications([message(r)])).toEqual([]);
    });
    it.each(['failed', 'unknown', 'deployed_unverified', 'public_verified'])('preserves Meoo state %s', state => {
        const r = { content: state, isError: state === 'failed', metadata: { structuredResult: {
            schema: 'site-publication/v1', provider: 'meoo', publicationId: 'p', label: 'Site', runtime: 'static',
            state, url: 'https://demo.meoo.pub', projectId: 'p', version: '1',
        } } };
        expect(projectTurnPublications([message(r)])[0]).toMatchObject({ kind: 'site', publication: { state } });
    });
});
