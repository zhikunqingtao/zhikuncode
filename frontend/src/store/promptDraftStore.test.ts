import { beforeEach, describe, expect, it } from 'vitest';
import type { LocalAttachment, PublishedLocalFile } from '@/types';
import {
    PROMPT_DRAFT_FALLBACK_KEY,
    capturePromptDraftTarget,
    resolvePromptDraftKey,
    usePromptDraftStore,
} from './promptDraftStore';

function makeAttachment(id: string): LocalAttachment {
    return {
        id,
        name: `${id}.png`,
        size: 8,
        type: 'image/png',
        file: new File([new Uint8Array(8)], `${id}.png`, { type: 'image/png' }),
        base64Content: 'AAAA',
        previewUrl: `blob:${id}`,
    };
}

function makePublishedLocalFile(name: string): PublishedLocalFile {
    return {
        artifactId: `artifact-${name}`,
        name,
        size: 16,
        sha256: `sha-${name}`,
        url: `https://oss.example.com/${name}`,
        mediaType: 'text/plain',
    };
}

describe('promptDraftStore', () => {
    beforeEach(() => {
        usePromptDraftStore.setState({ drafts: {} });
    });

    it('keeps an operation attached to its draft through migration and later fallback reuse', () => {
        const resolveTarget = capturePromptDraftTarget(PROMPT_DRAFT_FALLBACK_KEY);
        usePromptDraftStore.getState().migrateFallbackTo('session-a');
        usePromptDraftStore.getState().setInput(PROMPT_DRAFT_FALLBACK_KEY, 'new draft');
        usePromptDraftStore.getState().migrateFallbackTo('session-b');
        expect(resolveTarget()).toBe('session-a');
    });

    it('invalidates an old operation when its draft is cleared and recreated', () => {
        const resolveTarget = capturePromptDraftTarget('session-a');
        usePromptDraftStore.getState().clear('session-a');
        usePromptDraftStore.getState().setInput('session-a', 'replacement draft');
        expect(resolveTarget()).toBeUndefined();
    });

    it('stores and reads the input draft per session', () => {
        usePromptDraftStore.getState().setInput('session-a', 'draft a');

        expect(usePromptDraftStore.getState().drafts['session-a']?.input).toBe('draft a');
        expect(usePromptDraftStore.getState().drafts['session-b']).toBeUndefined();
    });

    it('supports functional updates for the input draft', () => {
        usePromptDraftStore.getState().setInput('session-a', 'hello');
        usePromptDraftStore.getState().setInput('session-a', prev => `${prev} world`);

        expect(usePromptDraftStore.getState().drafts['session-a']?.input).toBe('hello world');
    });

    it('stores attachments per session alongside the input draft', () => {
        const attachment = makeAttachment('att-1');
        usePromptDraftStore.getState().setAttachments('session-a', [attachment]);
        usePromptDraftStore.getState().setInput('session-a', 'with image');

        const draft = usePromptDraftStore.getState().drafts['session-a'];
        expect(draft?.attachments).toHaveLength(1);
        expect(draft?.attachments[0]).toMatchObject({ id: 'att-1', previewUrl: 'blob:att-1' });
        expect(draft?.input).toBe('with image');
    });

    it('supports functional updates for attachments', () => {
        usePromptDraftStore.getState().setAttachments('session-a', [makeAttachment('att-1')]);
        usePromptDraftStore.getState()
            .setAttachments('session-a', prev => [...prev, makeAttachment('att-2')]);
        usePromptDraftStore.getState()
            .setAttachments('session-a', prev => prev.filter(a => a.id !== 'att-1'));

        const attachments = usePromptDraftStore.getState().drafts['session-a']?.attachments;
        expect(attachments?.map(a => a.id)).toEqual(['att-2']);
    });

    it('keeps drafts isolated between two session ids', () => {
        usePromptDraftStore.getState().setInput('session-a', 'draft a');
        usePromptDraftStore.getState().setAttachments('session-a', [makeAttachment('att-a')]);
        usePromptDraftStore.getState().setInput('session-b', 'draft b');
        usePromptDraftStore.getState().setAttachments('session-b', [makeAttachment('att-b')]);

        const { drafts } = usePromptDraftStore.getState();
        expect(drafts['session-a']?.input).toBe('draft a');
        expect(drafts['session-a']?.attachments.map(a => a.id)).toEqual(['att-a']);
        expect(drafts['session-b']?.input).toBe('draft b');
        expect(drafts['session-b']?.attachments.map(a => a.id)).toEqual(['att-b']);
    });

    it('clearing one session does not affect another', () => {
        usePromptDraftStore.getState().setInput('session-a', 'draft a');
        usePromptDraftStore.getState().setAttachments('session-a', [makeAttachment('att-a')]);
        usePromptDraftStore.getState().setInput('session-b', 'draft b');
        usePromptDraftStore.getState().setAttachments('session-b', [makeAttachment('att-b')]);

        usePromptDraftStore.getState().clear('session-a');

        const { drafts } = usePromptDraftStore.getState();
        expect(drafts['session-a']).toBeUndefined();
        expect(drafts['session-b']?.input).toBe('draft b');
        expect(drafts['session-b']?.attachments.map(a => a.id)).toEqual(['att-b']);
    });

    it('clearing an unknown session is a no-op', () => {
        usePromptDraftStore.getState().clear('session-missing');

        expect(usePromptDraftStore.getState().drafts).toEqual({});
    });

    it('resolves a stable fallback key for empty session ids', () => {
        expect(PROMPT_DRAFT_FALLBACK_KEY).toBe('__none__');
        expect(resolvePromptDraftKey(null)).toBe(PROMPT_DRAFT_FALLBACK_KEY);
        expect(resolvePromptDraftKey(undefined)).toBe(PROMPT_DRAFT_FALLBACK_KEY);
        expect(resolvePromptDraftKey('')).toBe(PROMPT_DRAFT_FALLBACK_KEY);
        expect(resolvePromptDraftKey('session-a')).toBe('session-a');
    });

    it('migrates the fallback draft onto the first bound session', () => {
        usePromptDraftStore.getState().setInput(PROMPT_DRAFT_FALLBACK_KEY, 'unsent draft');
        usePromptDraftStore.getState()
            .setAttachments(PROMPT_DRAFT_FALLBACK_KEY, [makeAttachment('att-1')]);

        usePromptDraftStore.getState().migrateFallbackTo('session-new');

        const { drafts } = usePromptDraftStore.getState();
        expect(drafts[PROMPT_DRAFT_FALLBACK_KEY]).toBeUndefined();
        expect(drafts['session-new']?.input).toBe('unsent draft');
        expect(drafts['session-new']?.attachments.map(a => a.id)).toEqual(['att-1']);
    });

    it('migrateFallbackTo is a no-op without a fallback draft or target session', () => {
        usePromptDraftStore.getState().migrateFallbackTo('session-new');
        usePromptDraftStore.getState().setInput('session-a', 'draft a');
        usePromptDraftStore.getState().migrateFallbackTo('');

        expect(usePromptDraftStore.getState().drafts).toEqual({
            'session-a': {
                id: expect.any(String),
                input: 'draft a',
                attachments: [],
                localFiles: [],
                publishedLocalFiles: [],
            },
        });
    });

    it('stores local file references per session alongside the rest of the draft', () => {
        usePromptDraftStore.getState().setLocalFiles('session-a', [
            { path: '/tmp/a.ts', name: 'a.ts', size: 128 },
        ]);
        usePromptDraftStore.getState().setPublishedLocalFiles('session-a', [
            makePublishedLocalFile('a.ts'),
        ]);

        const draft = usePromptDraftStore.getState().drafts['session-a'];
        expect(draft?.input).toBe('');
        expect(draft?.attachments).toEqual([]);
        expect(draft?.localFiles).toEqual([{ path: '/tmp/a.ts', name: 'a.ts', size: 128 }]);
        expect(draft?.publishedLocalFiles).toHaveLength(1);
        expect(draft?.publishedLocalFiles[0]).toMatchObject({ name: 'a.ts', sha256: 'sha-a.ts' });
        expect(usePromptDraftStore.getState().drafts['session-b']).toBeUndefined();
    });

    it('supports functional updates and dedupe-style removal for local file references', () => {
        usePromptDraftStore.getState().setLocalFiles('session-a', [
            { path: '/tmp/a.ts', name: 'a.ts', size: 128 },
        ]);
        usePromptDraftStore.getState()
            .setLocalFiles('session-a', prev => [...prev, { path: '/tmp/b.ts', name: 'b.ts', size: 64 }]);
        usePromptDraftStore.getState()
            .setLocalFiles('session-a', prev => prev.filter(f => f.path !== '/tmp/a.ts'));

        expect(usePromptDraftStore.getState().drafts['session-a']?.localFiles)
            .toEqual([{ path: '/tmp/b.ts', name: 'b.ts', size: 64 }]);

        usePromptDraftStore.getState().setPublishedLocalFiles('session-a', [
            makePublishedLocalFile('a.ts'),
        ]);
        usePromptDraftStore.getState()
            .setPublishedLocalFiles('session-a', prev => prev.filter(f => f.name !== 'a.ts'));
        expect(usePromptDraftStore.getState().drafts['session-a']?.publishedLocalFiles)
            .toEqual([]);
    });

    it('migrates local file references with the fallback draft onto the first bound session', () => {
        usePromptDraftStore.getState().setInput(PROMPT_DRAFT_FALLBACK_KEY, 'unsent draft');
        usePromptDraftStore.getState().setLocalFiles(PROMPT_DRAFT_FALLBACK_KEY, [
            { path: '/tmp/a.ts', name: 'a.ts', size: 128 },
        ]);
        usePromptDraftStore.getState()
            .setPublishedLocalFiles(PROMPT_DRAFT_FALLBACK_KEY, [makePublishedLocalFile('a.ts')]);

        usePromptDraftStore.getState().migrateFallbackTo('session-new');

        const { drafts } = usePromptDraftStore.getState();
        expect(drafts[PROMPT_DRAFT_FALLBACK_KEY]).toBeUndefined();
        expect(drafts['session-new']?.input).toBe('unsent draft');
        expect(drafts['session-new']?.localFiles)
            .toEqual([{ path: '/tmp/a.ts', name: 'a.ts', size: 128 }]);
        expect(drafts['session-new']?.publishedLocalFiles.map(f => f.name)).toEqual(['a.ts']);
    });

    it('clears local file references with the rest of the draft', () => {
        usePromptDraftStore.getState().setLocalFiles('session-a', [
            { path: '/tmp/a.ts', name: 'a.ts', size: 128 },
        ]);
        usePromptDraftStore.getState()
            .setPublishedLocalFiles('session-a', [makePublishedLocalFile('a.ts')]);

        usePromptDraftStore.getState().clear('session-a');

        expect(usePromptDraftStore.getState().drafts['session-a']).toBeUndefined();
    });
});
