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
import type { Command } from '@/types';
import { useWorkbenchViewStore } from '@/store/workbenchViewStore';

function renderInput(
    onSubmit: (event: unknown) => Promise<boolean>,
    onSlashCommand = vi.fn().mockResolvedValue(true),
    commands: Command[] = [],
    state: { runActive?: boolean; compacting?: boolean; simpleMode?: boolean } = {},
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
