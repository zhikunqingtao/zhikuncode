import {
    cleanup,
    fireEvent,
    render,
    screen,
    waitFor,
} from '@testing-library/react';
import {
    afterEach,
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest';
import PromptInput from './PromptInput';
import type { Command, PastePublishResult } from '@/types';
import { useWorkbenchViewStore } from '@/store/workbenchViewStore';

function renderInput(
    onSubmit: (event: unknown) => Promise<boolean>,
    onSlashCommand = vi.fn().mockResolvedValue(true),
    commands: Command[] = [],
    state: { runActive?: boolean; compacting?: boolean; simpleMode?: boolean } = {},
    onPasteImages = vi.fn().mockResolvedValue({ mode: 'oss', items: [] }),
) {
    render(
        <PromptInput
            onSubmit={onSubmit}
            onSlashCommand={onSlashCommand}
            onInterrupt={vi.fn()}
            disabled={false}
            runActive={state.runActive ?? false}
            compacting={state.compacting ?? false}
            permissionMode="read_write"
            messages={[]}
            commands={commands}
            simpleMode={state.simpleMode}
            onPasteImages={onPasteImages}
        />,
    );
}

describe('PromptInput asynchronous submit', () => {
    beforeEach(() => {
        useWorkbenchViewStore.setState({
            enabled: true,
            activeSessionId: 'session-a',
            defaultView: 'simple',
            viewMode: 'simple',
        });
        Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
            configurable: true,
            value: vi.fn(),
        });
    });

    afterEach(() => {
        cleanup();
        delete (HTMLElement.prototype as {
            scrollIntoView?: unknown;
        }).scrollIntoView;
        vi.restoreAllMocks();
        vi.unstubAllGlobals();
    });

    it('uploads a pasted screenshot through the fixed OSS path and submits its URL', async () => {
        const onSubmit = vi.fn().mockResolvedValue(true);
        const onPasteImages = vi.fn().mockResolvedValue({
            mode: 'oss',
            items: [{
                name: 'clipboard.png',
                size: 8,
                mediaType: 'image/png',
                url: 'https://bucket.oss-cn-beijing.aliyuncs.com/prefix/clipboard/image.png',
            }],
        });
        vi.stubGlobal('URL', {
            ...URL,
            createObjectURL: vi.fn().mockReturnValue('blob:preview'),
            revokeObjectURL: vi.fn(),
        });
        renderInput(onSubmit, undefined, [], {}, onPasteImages);
        const input = screen.getByRole('textbox', { name: '输入消息' });
        const file = new File([new Uint8Array(8)], 'clipboard.png', { type: 'image/png' });

        fireEvent.paste(input, {
            clipboardData: {
                items: [{ kind: 'file', type: 'image/png', getAsFile: () => file }],
                files: [file],
            },
        });

        await waitFor(() => expect(onPasteImages).toHaveBeenCalledWith([file]));
        await waitFor(() => expect(screen.getByAltText('clipboard.png')).toBeInTheDocument());
        fireEvent.change(input, { target: { value: '看看这张图' } });
        fireEvent.click(screen.getByRole('button', { name: '发送消息' }));

        await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
            attachments: [expect.objectContaining({
                type: 'image',
                url: 'https://bucket.oss-cn-beijing.aliyuncs.com/prefix/clipboard/image.png',
                base64Data: '',
            })],
        })));
    });

    it('falls back to inline base64 attachments when OSS is not configured', async () => {
        const onSubmit = vi.fn().mockResolvedValue(true);
        const onPasteImages = vi.fn().mockResolvedValue({ mode: 'base64' });
        vi.stubGlobal('URL', {
            ...URL,
            createObjectURL: vi.fn().mockReturnValue('blob:preview'),
            revokeObjectURL: vi.fn(),
        });
        renderInput(onSubmit, undefined, [], {}, onPasteImages);
        const input = screen.getByRole('textbox', { name: '输入消息' });
        const file = new File([new Uint8Array(8)], 'clipboard.png', { type: 'image/png' });

        fireEvent.paste(input, {
            clipboardData: {
                items: [{ kind: 'file', type: 'image/png', getAsFile: () => file }],
                files: [file],
            },
        });

        await waitFor(() => expect(onPasteImages).toHaveBeenCalledWith([file]));
        await waitFor(() => expect(screen.getByAltText('clipboard.png')).toBeInTheDocument());
        fireEvent.change(input, { target: { value: '看看这张图' } });
        fireEvent.click(screen.getByRole('button', { name: '发送消息' }));

        await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
            attachments: [expect.objectContaining({
                type: 'image',
                url: undefined,
                base64Data: expect.stringMatching(/.+/),
            })],
        })));
    });

    it('caps pasted images at the 20-image limit', async () => {
        const onPasteImages = vi.fn((files: File[]) => Promise.resolve({
            mode: 'oss' as const,
            items: files.map((f, i) => ({
                name: f.name,
                size: f.size,
                mediaType: f.type,
                url: `https://bucket.oss-cn-beijing.aliyuncs.com/prefix/clipboard/${i}.png`,
            })),
        }));
        vi.stubGlobal('URL', {
            ...URL,
            createObjectURL: vi.fn().mockReturnValue('blob:preview'),
            revokeObjectURL: vi.fn(),
        });
        renderInput(vi.fn().mockResolvedValue(true), undefined, [], {}, onPasteImages);
        const input = screen.getByRole('textbox', { name: '输入消息' });
        const files = Array.from({ length: 21 }, (_, i) =>
            new File([new Uint8Array(4)], `img-${i}.png`, { type: 'image/png' }));

        fireEvent.paste(input, {
            clipboardData: {
                items: files.map(f => ({ kind: 'file', type: 'image/png', getAsFile: () => f })),
                files,
            },
        });

        await waitFor(() => expect(onPasteImages).toHaveBeenCalledTimes(1));
        // 第 21 张在调用上传前就被截断，附件总数不超过 20
        expect(onPasteImages.mock.calls[0][0]).toHaveLength(20);
        await waitFor(() => expect(screen.getAllByRole('img')).toHaveLength(20));
        expect(screen.queryByAltText('img-20.png')).not.toBeInTheDocument();
        expect(screen.getByText('20/20 张图片')).toBeInTheDocument();
    });

    it('skips pasted images above 5MB and keeps the rest', async () => {
        const onPasteImages = vi.fn((files: File[]) => Promise.resolve({
            mode: 'oss' as const,
            items: files.map(f => ({
                name: f.name,
                size: f.size,
                mediaType: f.type,
                url: `https://bucket.oss-cn-beijing.aliyuncs.com/prefix/clipboard/${f.name}`,
            })),
        }));
        vi.stubGlobal('URL', {
            ...URL,
            createObjectURL: vi.fn().mockReturnValue('blob:preview'),
            revokeObjectURL: vi.fn(),
        });
        renderInput(vi.fn().mockResolvedValue(true), undefined, [], {}, onPasteImages);
        const input = screen.getByRole('textbox', { name: '输入消息' });
        const oversize = new File([new Uint8Array(8)], 'oversize.png', { type: 'image/png' });
        Object.defineProperty(oversize, 'size', { value: 5 * 1024 * 1024 + 1 });
        const normal = new File([new Uint8Array(8)], 'normal.png', { type: 'image/png' });
        const files = [oversize, normal];

        fireEvent.paste(input, {
            clipboardData: {
                items: files.map(f => ({ kind: 'file', type: f.type, getAsFile: () => f })),
                files,
            },
        });

        await waitFor(() => expect(onPasteImages).toHaveBeenCalledTimes(1));
        expect(onPasteImages.mock.calls[0][0]).toEqual([normal]);
        await waitFor(() => expect(screen.getByAltText('normal.png')).toBeInTheDocument());
        expect(screen.queryByAltText('oversize.png')).not.toBeInTheDocument();
    });

    it('enforces the 20-image limit across drag-drop uploads and paste combined', async () => {
        const onPasteImages = vi.fn((files: File[]) => Promise.resolve({
            mode: 'oss' as const,
            items: files.map(f => ({
                name: f.name,
                size: f.size,
                mediaType: f.type,
                url: `https://bucket.oss-cn-beijing.aliyuncs.com/prefix/clipboard/${f.name}`,
            })),
        }));
        vi.stubGlobal('URL', {
            ...URL,
            createObjectURL: vi.fn().mockReturnValue('blob:preview'),
            revokeObjectURL: vi.fn(),
        });
        renderInput(vi.fn().mockResolvedValue(true), undefined, [], {}, onPasteImages);
        const input = screen.getByRole('textbox', { name: '输入消息' });

        // 先通过拖拽（与按钮上传共用 handleFiles 路径）上传 19 张
        const dropped = Array.from({ length: 19 }, (_, i) =>
            new File([new Uint8Array(4)], `drop-${i}.png`, { type: 'image/png' }));
        fireEvent.drop(input, { dataTransfer: { files: dropped } });
        await waitFor(() => expect(screen.getAllByRole('img')).toHaveLength(19));

        // 再粘贴 3 张：剩余额度仅 1，总数按合计生效
        const pasted = Array.from({ length: 3 }, (_, i) =>
            new File([new Uint8Array(4)], `paste-${i}.png`, { type: 'image/png' }));
        fireEvent.paste(input, {
            clipboardData: {
                items: pasted.map(f => ({ kind: 'file', type: 'image/png', getAsFile: () => f })),
                files: pasted,
            },
        });

        await waitFor(() => expect(onPasteImages).toHaveBeenCalledTimes(1));
        expect(onPasteImages.mock.calls[0][0]).toEqual([pasted[0]]);
        await waitFor(() => expect(screen.getAllByRole('img')).toHaveLength(20));
        expect(screen.getByText('20/20 张图片')).toBeInTheDocument();
    });

    it('revokes preview URLs when the component unmounts during a pending paste upload', async () => {
        let resolvePaste!: (result: PastePublishResult) => void;
        const onPasteImages = vi.fn(() => new Promise<PastePublishResult>(resolve => {
            resolvePaste = resolve;
        }));
        const createObjectURL = vi.fn().mockReturnValue('blob:pending');
        const revokeObjectURL = vi.fn();
        vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL });
        renderInput(vi.fn().mockResolvedValue(true), undefined, [], {}, onPasteImages);
        const input = screen.getByRole('textbox', { name: '输入消息' });
        const file = new File([new Uint8Array(8)], 'clipboard.png', { type: 'image/png' });

        fireEvent.paste(input, {
            clipboardData: {
                items: [{ kind: 'file', type: 'image/png', getAsFile: () => file }],
                files: [file],
            },
        });
        await waitFor(() => expect(onPasteImages).toHaveBeenCalledTimes(1));

        // await 期间卸载组件，随后上传才完成
        cleanup();
        resolvePaste({
            mode: 'oss',
            items: [{
                name: 'clipboard.png',
                size: 8,
                mediaType: 'image/png',
                url: 'https://bucket.oss-cn-beijing.aliyuncs.com/prefix/clipboard/image.png',
            }],
        });

        // 卸载后新建的预览 URL 必须被就地回收
        await waitFor(() => expect(revokeObjectURL).toHaveBeenCalledWith('blob:pending'));
    });

    it('clears the draft only after the message was sent', async () => {
        let resolveSubmit!: (sent: boolean) => void;
        const onSubmit = vi.fn(() => new Promise<boolean>(resolve => {
            resolveSubmit = resolve;
        }));
        renderInput(onSubmit);
        const input = screen.getByRole('textbox', {
            name: '输入消息',
        });

        fireEvent.change(input, { target: { value: 'hello' } });
        fireEvent.click(screen.getByRole('button', {
            name: '发送消息',
        }));

        expect(input).toHaveValue('hello');
        expect(input).toBeDisabled();
        resolveSubmit(true);
        await waitFor(() => expect(input).toHaveValue(''));
        expect(input).toBeEnabled();
        expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
            text: 'hello',
        }));
    });

    it('keeps the draft when authorization or sending is canceled', async () => {
        const onSubmit = vi.fn().mockResolvedValue(false);
        renderInput(onSubmit);
        const input = screen.getByRole('textbox', {
            name: '输入消息',
        });

        fireEvent.change(input, { target: { value: 'keep this draft' } });
        fireEvent.click(screen.getByRole('button', {
            name: '发送消息',
        }));

        await waitFor(() => expect(input).toBeEnabled());
        expect(input).toHaveValue('keep this draft');
    });

    it('uses result-oriented copy in the simple workbench', () => {
        renderInput(vi.fn().mockResolvedValue(true), undefined, [], {
            simpleMode: true,
        });
        expect(screen.getByRole('textbox', { name: '输入消息' }))
            .toHaveAttribute('placeholder', '描述你希望完成或继续修改的事情…');
    });

    it('clears a slash command only after it was accepted', async () => {
        let resolveCommand!: (accepted: boolean) => void;
        const onSlashCommand = vi.fn(() => new Promise<boolean>(resolve => {
            resolveCommand = resolve;
        }));
        renderInput(vi.fn().mockResolvedValue(true), onSlashCommand);
        const input = screen.getByRole('textbox', {
            name: '输入消息',
        });

        fireEvent.change(input, { target: { value: '/compact' } });
        fireEvent.click(screen.getByRole('button', {
            name: '发送消息',
        }));

        expect(input).toHaveValue('/compact');
        expect(input).toBeDisabled();
        resolveCommand(true);
        await waitFor(() => expect(input).toHaveValue(''));
        expect(input).toBeEnabled();
        expect(onSlashCommand).toHaveBeenCalledWith('/compact');
    });

    it('keeps a slash command when it was rejected', async () => {
        const onSlashCommand = vi.fn().mockResolvedValue(false);
        renderInput(vi.fn().mockResolvedValue(true), onSlashCommand);
        const input = screen.getByRole('textbox', {
            name: '输入消息',
        });

        fireEvent.change(input, { target: { value: '/retry-me' } });
        fireEvent.click(screen.getByRole('button', {
            name: '发送消息',
        }));

        await waitFor(() => expect(input).toBeEnabled());
        expect(input).toHaveValue('/retry-me');
    });

    it('preserves a normal draft after a global command succeeds', async () => {
        const onSlashCommand = vi.fn().mockResolvedValue(true);
        renderInput(
            vi.fn().mockResolvedValue(true),
            onSlashCommand,
            [{
                name: 'compact',
                description: 'Compact context',
                group: 'Commands',
            }],
        );
        const input = screen.getByRole('textbox', {
            name: '输入消息',
        });
        fireEvent.change(input, {
            target: { value: 'keep this normal draft' },
        });

        fireEvent.keyDown(window, { key: 'k', ctrlKey: true });
        fireEvent.click(screen.getByRole('button', {
            name: /\/compact/,
        }));

        await waitFor(() => expect(onSlashCommand)
            .toHaveBeenCalledWith('/compact'));
        expect(input).toHaveValue('keep this normal draft');
    });

    it('sends slash-looking text as steering input while a run is active', async () => {
        const onSubmit = vi.fn().mockResolvedValue(true);
        const onSlashCommand = vi.fn().mockResolvedValue(true);
        renderInput(onSubmit, onSlashCommand, [], { runActive: true });
        const input = screen.getByRole('textbox', { name: '输入消息' });

        fireEvent.change(input, { target: { value: '/change direction' } });
        fireEvent.click(screen.getByRole('button', { name: '发送运行中干预' }));

        await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(
            expect.objectContaining({ text: '/change direction' }),
        ));
        expect(onSlashCommand).not.toHaveBeenCalled();
        expect(screen.getByRole('button', { name: '停止当前任务' })).toBeEnabled();
    });

    it('disables input and command submission while compacting', () => {
        const onSubmit = vi.fn().mockResolvedValue(true);
        const onSlashCommand = vi.fn().mockResolvedValue(true);
        renderInput(onSubmit, onSlashCommand, [], { compacting: true });

        expect(screen.getByRole('textbox', { name: '输入消息' })).toBeDisabled();
        expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled();
        fireEvent.keyDown(window, { key: 'k', ctrlKey: true });
        expect(onSlashCommand).not.toHaveBeenCalled();
    });
});
