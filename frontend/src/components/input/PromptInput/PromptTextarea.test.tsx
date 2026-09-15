import { createRef } from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import PromptTextarea from './PromptTextarea';

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

describe('PromptTextarea mobile sizing', () => {
    it('recomputes the viewport cap without changing the draft, and shrinks after clearing', () => {
        const viewport = Object.assign(new EventTarget(), { height: 800 });
        vi.stubGlobal('visualViewport', viewport);
        vi.spyOn(HTMLTextAreaElement.prototype, 'scrollHeight', 'get').mockImplementation(function (this: HTMLTextAreaElement) {
            return this.value ? 500 : 28;
        });
        const ref = createRef<HTMLTextAreaElement>();
        const props = {
            textareaRef: ref, variant: 'mobile' as const, value: '保留草稿',
            onValueChange: vi.fn(), onCursorChange: vi.fn(), onAtQueryChange: vi.fn(),
            onSlashIntent: vi.fn(), onKeyDown: vi.fn(), onPaste: vi.fn(),
            compacting: false, runActive: false, simpleMode: false, disabled: false,
        };
        const { rerender, unmount } = render(<PromptTextarea {...props} />);
        expect(ref.current?.style.height).toBe(`${800 * 0.382 - 20}px`);
        viewport.height = 300;
        viewport.dispatchEvent(new Event('resize'));
        expect(ref.current?.style.height).toBe(`${300 * 0.382 - 20}px`);
        expect(screen.getByRole('textbox')).toHaveValue('保留草稿');
        expect(props.onValueChange).not.toHaveBeenCalled();
        rerender(<PromptTextarea {...props} value="" />);
        expect(ref.current?.style.height).toBe('28px');
        unmount();
        expect(() => viewport.dispatchEvent(new Event('resize'))).not.toThrow();
    });
});
