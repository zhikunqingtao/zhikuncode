/**
 * promptDraftPersistence — 草稿跨卸载/重挂载恢复（P1 修复回归测试）
 *
 * 移动端底部导航切换会整体卸载聊天树（AppLayout 条件渲染）；
 * 草稿（输入文本 + 图片附件）托管到 promptDraftStore 后，
 * 卸载/重挂载循环应原样恢复，且不同会话草稿相互隔离。
 */

import { act, renderHook } from '@testing-library/react';
import type { ClipboardEvent } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PublishedLocalFile } from '@/types';
import type { UsePromptStateParams } from './usePromptState';
import { usePromptState } from './usePromptState';
import { usePromptAttachments } from './usePromptAttachments';
import {
    useLocalFileReference,
    type UseLocalFileReferenceParams,
} from './useLocalFileReference';
import { usePromptDraftStore } from '@/store/promptDraftStore';

function createParams(sessionId: string | null): UsePromptStateParams {
    return {
        sessionId,
        onSubmit: vi.fn().mockResolvedValue(true),
        onSlashCommand: vi.fn().mockResolvedValue(true),
        onInterrupt: vi.fn(),
        disabled: false,
        runActive: false,
        compacting: false,
        onPasteImages: vi.fn().mockResolvedValue({ mode: 'base64' }),
        onPublishLocalFile: vi.fn(),
        fileReferenceCapability: null,
    };
}

function createLocalFileParams(sessionId: string | null): UseLocalFileReferenceParams {
    return {
        sessionId,
        disabled: false,
        runActive: false,
        compacting: false,
        isSubmitting: false,
        isUploadingPaste: false,
        onPublishLocalFile: vi.fn(),
        fileReferenceCapability: null,
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

function makeImagePasteEvent(file: File): ClipboardEvent<HTMLTextAreaElement> {
    return {
        clipboardData: {
            items: [{ kind: 'file', type: file.type, getAsFile: () => file }],
            files: [file],
        },
        preventDefault: vi.fn(),
    } as unknown as ClipboardEvent<HTMLTextAreaElement>;
}

describe('prompt draft persistence across unmount/remount', () => {
    beforeEach(() => {
        usePromptDraftStore.setState({ drafts: {} });
        vi.stubGlobal('URL', {
            ...URL,
            createObjectURL: vi.fn().mockReturnValue('blob:preview'),
            revokeObjectURL: vi.fn(),
        });
    });

    afterEach(() => {
        vi.unstubAllGlobals();
        vi.restoreAllMocks();
    });

    it('restores the draft text after an unmount/remount cycle (mobile tab switch)', () => {
        const first = renderHook(() => usePromptState(createParams('session-a')));
        act(() => first.result.current.setInput('尚未发送的草稿'));

        expect(first.result.current.input).toBe('尚未发送的草稿');
        first.unmount();

        const second = renderHook(() => usePromptState(createParams('session-a')));
        expect(second.result.current.input).toBe('尚未发送的草稿');
        second.unmount();
    });

    it('restores attachments after an unmount/remount cycle', async () => {
        const first = renderHook(() => usePromptAttachments({
            runActive: false,
            compacting: false,
            onPasteImages: vi.fn(),
            sessionId: 'session-a',
        }));
        const file = new File([new Uint8Array(8)], 'photo.png', { type: 'image/png' });
        await act(async () => {
            await first.result.current.handleFiles([file]);
        });

        expect(first.result.current.attachments).toHaveLength(1);
        expect(first.result.current.attachments[0].previewUrl).toBe('blob:preview');
        first.unmount();

        const second = renderHook(() => usePromptAttachments({
            runActive: false,
            compacting: false,
            onPasteImages: vi.fn(),
            sessionId: 'session-a',
        }));
        expect(second.result.current.attachments).toHaveLength(1);
        expect(second.result.current.attachments[0]).toMatchObject({
            name: 'photo.png',
            type: 'image/png',
            previewUrl: 'blob:preview',
        });
        second.unmount();
    });

    it('keeps drafts separate when switching between two sessions', () => {
        const { result, rerender, unmount } = renderHook(
            ({ sessionId }) => usePromptState(createParams(sessionId)),
            { initialProps: { sessionId: 'session-a' as string | null } },
        );
        act(() => result.current.setInput('draft for a'));

        rerender({ sessionId: 'session-b' });
        expect(result.current.input).toBe('');
        act(() => result.current.setInput('draft for b'));

        rerender({ sessionId: 'session-a' });
        expect(result.current.input).toBe('draft for a');

        rerender({ sessionId: 'session-b' });
        expect(result.current.input).toBe('draft for b');
        unmount();
    });

    it('carries a no-session draft over to the first bound session', () => {
        const { result, rerender, unmount } = renderHook(
            ({ sessionId }) => usePromptState(createParams(sessionId)),
            { initialProps: { sessionId: null as string | null } },
        );
        act(() => result.current.setInput('draft before session exists'));

        rerender({ sessionId: 'session-created' });
        expect(result.current.input).toBe('draft before session exists');
        expect(usePromptDraftStore.getState().drafts['__none__']).toBeUndefined();
        unmount();
    });

    it('clears the draft in the store only after a successful submit', async () => {
        const params = createParams('session-a');
        const first = renderHook(() => usePromptState(params));
        act(() => first.result.current.setInput('要发送的内容'));

        await act(async () => {
            await first.result.current.handleSubmit();
        });
        expect(params.onSubmit).toHaveBeenCalledWith(expect.objectContaining({
            text: '要发送的内容',
        }));
        expect(first.result.current.input).toBe('');
        first.unmount();

        // 提交清空已写回 store：重挂载后不会复活旧草稿
        const second = renderHook(() => usePromptState(createParams('session-a')));
        expect(second.result.current.input).toBe('');
        second.unmount();
    });

    it('clears the NEW session draft when the session is created mid-submit (first message)', async () => {
        // P1 回归：首条消息提交期间 ensureSessionReady() 创建会话
        // （sessionId null → 'session-new'），提交成功后的清空必须落到
        // 消息实际发往的新会话键，而不是已迁空的 '__none__'。
        let resolveSubmit!: (sent: boolean) => void;
        const base = createParams(null);
        base.onSubmit = vi.fn().mockImplementation(
            () => new Promise<boolean>(resolve => { resolveSubmit = resolve; }),
        );
        const { result, rerender, unmount } = renderHook(
            ({ params }) => usePromptState(params),
            { initialProps: { params: base } },
        );
        act(() => result.current.setInput('first message of a new session'));

        let submitPromise!: Promise<void>;
        await act(async () => {
            submitPromise = result.current.handleSubmit();
            await Promise.resolve();
        });
        expect(base.onSubmit).toHaveBeenCalledWith(expect.objectContaining({
            text: 'first message of a new session',
        }));

        // 会话在提交中途创建：React 重渲染触发兜底草稿迁移到 'session-new'
        rerender({ params: { ...base, sessionId: 'session-new' } });
        expect(usePromptDraftStore.getState().drafts['session-new']?.input)
            .toBe('first message of a new session');

        await act(async () => {
            resolveSubmit(true);
            await submitPromise;
        });

        // 清空写落在会话实际键上：兜底键不被重新创建，新会话草稿为空
        expect(usePromptDraftStore.getState().drafts['session-new']?.input).toBe('');
        expect(usePromptDraftStore.getState().drafts['__none__']).toBeUndefined();
        expect(result.current.input).toBe('');
        unmount();
    });

    it('keeps the draft when a mid-submit session creation ends in a rejected send', async () => {
        let resolveSubmit!: (sent: boolean) => void;
        const base = createParams(null);
        base.onSubmit = vi.fn().mockImplementation(
            () => new Promise<boolean>(resolve => { resolveSubmit = resolve; }),
        );
        const { result, rerender, unmount } = renderHook(
            ({ params }) => usePromptState(params),
            { initialProps: { params: base } },
        );
        act(() => result.current.setInput('do not lose me'));

        let submitPromise!: Promise<void>;
        await act(async () => {
            submitPromise = result.current.handleSubmit();
            await Promise.resolve();
        });
        rerender({ params: { ...base, sessionId: 'session-new' } });

        await act(async () => {
            resolveSubmit(false);
            await submitPromise;
        });

        // 发送未成功：新会话键上的草稿原样保留
        expect(usePromptDraftStore.getState().drafts['session-new']?.input)
            .toBe('do not lose me');
        expect(result.current.input).toBe('do not lose me');
        unmount();
    });

    it('lands OSS paste attachments on the NEW session created mid-publish (first message)', async () => {
        // P1 回归：无会话时粘贴图片，OSS 发布链路 ensureSessionReady()
        // 创建会话后才返回；await 之后的附件写入必须落到新会话键，
        // 否则首条消息丢失附件（附件被写进已迁空的 '__none__'）。
        let resolvePaste!: (result: { mode: 'oss'; items: Array<{
            name: string; size: number; mediaType: string; url: string;
        }> }) => void;
        const onPasteImages = vi.fn().mockImplementation(
            () => new Promise(resolve => { resolvePaste = resolve; }),
        );
        const { result, rerender, unmount } = renderHook(
            ({ sessionId }) => usePromptAttachments({
                runActive: false,
                compacting: false,
                onPasteImages,
                sessionId,
            }),
            { initialProps: { sessionId: null as string | null } },
        );

        const file = new File([new Uint8Array(8)], 'pasted.png', { type: 'image/png' });
        act(() => result.current.handlePaste(makeImagePasteEvent(file)));
        expect(onPasteImages).toHaveBeenCalledWith([file]);

        // 发布链路中途创建了会话
        rerender({ sessionId: 'session-new' });

        await act(async () => {
            resolvePaste({ mode: 'oss', items: [{
                name: 'pasted.png',
                size: 8,
                mediaType: 'image/png',
                url: 'https://oss.example.com/pasted.png',
            }] });
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        const { drafts } = usePromptDraftStore.getState();
        expect(drafts['session-new']?.attachments).toHaveLength(1);
        expect(drafts['session-new']?.attachments[0])
            .toMatchObject({ name: 'pasted.png', remoteUrl: 'https://oss.example.com/pasted.png' });
        expect(drafts['__none__']).toBeUndefined();
        expect(result.current.attachments).toHaveLength(1);
        unmount();
    });

    it('restores local file references after an unmount/remount cycle (P2)', () => {
        const first = renderHook(() => useLocalFileReference(createLocalFileParams('session-a')));
        act(() => {
            first.result.current.setLocalFiles([{ path: '/tmp/a.ts', name: 'a.ts', size: 128 }]);
            first.result.current.setPublishedLocalFiles([makePublishedLocalFile('a.ts')]);
        });
        expect(first.result.current.localFiles).toHaveLength(1);
        first.unmount();

        const second = renderHook(() => useLocalFileReference(createLocalFileParams('session-a')));
        expect(second.result.current.localFiles)
            .toEqual([{ path: '/tmp/a.ts', name: 'a.ts', size: 128 }]);
        expect(second.result.current.publishedLocalFiles.map(f => f.name)).toEqual(['a.ts']);
        second.unmount();
    });

    it('migrates local file references from the fallback key to the first bound session', () => {
        const { result, rerender, unmount } = renderHook(
            ({ sessionId }) => usePromptState(createParams(sessionId)),
            { initialProps: { sessionId: null as string | null } },
        );
        act(() => {
            result.current.localFileReference
                .setLocalFiles([{ path: '/tmp/a.ts', name: 'a.ts', size: 128 }]);
            result.current.localFileReference
                .setPublishedLocalFiles([makePublishedLocalFile('a.ts')]);
        });

        rerender({ sessionId: 'session-created' });

        expect(result.current.localFileReference.localFiles)
            .toEqual([{ path: '/tmp/a.ts', name: 'a.ts', size: 128 }]);
        expect(result.current.localFileReference.publishedLocalFiles.map(f => f.name))
            .toEqual(['a.ts']);
        expect(usePromptDraftStore.getState().drafts['__none__']).toBeUndefined();
        unmount();
    });

    it('keeps each session\'s local file references when switching sessions', () => {
        const { result, rerender, unmount } = renderHook(
            ({ sessionId }) => useLocalFileReference(createLocalFileParams(sessionId)),
            { initialProps: { sessionId: 'session-a' as string | null } },
        );
        act(() => result.current.setLocalFiles([{ path: '/tmp/a.ts', name: 'a.ts', size: 1 }]));

        rerender({ sessionId: 'session-b' });
        expect(result.current.localFiles).toEqual([]);
        act(() => result.current.setLocalFiles([{ path: '/tmp/b.ts', name: 'b.ts', size: 2 }]));

        // 切回时原样恢复（与图片附件/文本草稿同语义）
        rerender({ sessionId: 'session-a' });
        expect(result.current.localFiles).toEqual([{ path: '/tmp/a.ts', name: 'a.ts', size: 1 }]);
        rerender({ sessionId: 'session-b' });
        expect(result.current.localFiles).toEqual([{ path: '/tmp/b.ts', name: 'b.ts', size: 2 }]);
        unmount();
    });
});
