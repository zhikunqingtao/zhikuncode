import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ImageBlock from './ImageBlock';

describe('ImageBlock', () => {
    afterEach(cleanup);

    it.each([
        ['pointer', 1],
        ['keyboard activation', 0],
    ])('opens the zoom button overlay outside the message container for a %s click', (_source, detail) => {
        const { container } = render(
            <div style={{ transform: 'translateY(1px)', backdropFilter: 'blur(8px)' }}>
                <ImageBlock src="https://example.com/image.png" alt="preview" />
            </div>,
        );

        // Keyboard activation dispatches a click with detail=0; jsdom does not synthesize it from keydown.
        fireEvent.click(screen.getByRole('button', { name: 'Zoom image' }), { detail });
        const enlargedImage = screen.getAllByRole('img', { name: 'preview' })[1];

        expect(enlargedImage.parentElement?.parentElement).toBe(document.body);
        expect(container).not.toContainElement(enlargedImage);
        expect(screen.getByRole('button', { name: 'Close zoom' })).toBeInTheDocument();
    });

    it('closes the enlarged image when the close button is clicked', () => {
        render(<ImageBlock src="https://example.com/image.png" alt="preview" />);

        fireEvent.click(screen.getByRole('img', { name: 'preview' }));
        const closeButton = screen.getByRole('button', { name: 'Close zoom' });

        fireEvent.click(closeButton);

        expect(screen.queryByRole('button', { name: 'Close zoom' })).not.toBeInTheDocument();
    });

    it('keeps the portal open for image clicks and closes it for backdrop clicks', () => {
        const onMessageClick = vi.fn();
        render(
            <div onClick={onMessageClick}>
                <ImageBlock src="https://example.com/image.png" alt="preview" />
            </div>,
        );
        fireEvent.click(screen.getByRole('img', { name: 'preview' }));
        onMessageClick.mockClear();
        const enlargedImage = screen.getAllByRole('img', { name: 'preview' })[1];
        const overlay = enlargedImage.parentElement!;

        fireEvent.click(enlargedImage);

        expect(screen.getByRole('button', { name: 'Close zoom' })).toBeInTheDocument();
        expect(onMessageClick).not.toHaveBeenCalled();

        fireEvent.click(overlay);

        expect(overlay).not.toBeInTheDocument();
        expect(screen.getAllByRole('img', { name: 'preview' })).toHaveLength(1);
    });

    it('removes the portal when its message is unmounted', () => {
        const { unmount } = render(<ImageBlock src="https://example.com/image.png" alt="preview" />);
        fireEvent.click(screen.getByRole('img', { name: 'preview' }));
        const overlay = screen.getAllByRole('img', { name: 'preview' })[1].parentElement!;

        unmount();

        expect(overlay).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: 'Close zoom' })).not.toBeInTheDocument();
    });

    it('removes an open portal when the inline image reports a load error', () => {
        render(<ImageBlock src="https://example.com/image.png" alt="preview" />);
        const inlineImage = screen.getByRole('img', { name: 'preview' });
        fireEvent.click(inlineImage);

        fireEvent.error(inlineImage);

        expect(screen.getByText('Failed to load image')).toBeInTheDocument();
        expect(screen.queryByRole('button', { name: 'Close zoom' })).not.toBeInTheDocument();
    });

    describe('copy button', () => {
        let writeText: ReturnType<typeof vi.fn>;
        let write: ReturnType<typeof vi.fn>;

        beforeEach(() => {
            writeText = vi.fn().mockResolvedValue(undefined);
            write = vi.fn().mockResolvedValue(undefined);
            Object.defineProperty(navigator, 'clipboard', {
                value: { writeText, write },
                configurable: true,
            });
        });

        afterEach(() => {
            vi.unstubAllGlobals();
        });

        it('copies the zoomed image and shows copied feedback', async () => {
            // jsdom 无 ClipboardItem → 工具内部降级为复制 URL 文本
            vi.stubGlobal('ClipboardItem', undefined);
            vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
                ok: true,
                blob: async () => new Blob(['x'], { type: 'image/png' }),
            }));

            render(<ImageBlock src="https://example.com/image.png" alt="preview" />);
            fireEvent.click(screen.getByRole('img', { name: 'preview' }));
            fireEvent.click(screen.getByRole('button', { name: 'Copy image' }));

            expect(await screen.findByRole('button', { name: 'Image copied' })).toBeInTheDocument();
            expect(writeText).toHaveBeenCalledWith('https://example.com/image.png');
        });

        it('shows failed feedback when no clipboard path is available', async () => {
            // base64 图片 + 无 ClipboardItem + clipboard.write 不可用 → 工具抛异常
            vi.stubGlobal('ClipboardItem', undefined);
            Object.defineProperty(navigator, 'clipboard', {
                value: { writeText },
                configurable: true,
            });

            render(<ImageBlock base64Data="iVBORw0KGgo=" mediaType="image/png" alt="preview" />);
            fireEvent.click(screen.getByRole('img', { name: 'preview' }));
            fireEvent.click(screen.getByRole('button', { name: 'Copy image' }));

            expect(await screen.findByRole('button', { name: 'Copy image failed' })).toBeInTheDocument();
        });

        it('does not close the overlay when copy button is clicked', async () => {
            vi.stubGlobal('ClipboardItem', undefined);
            vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
                ok: true,
                blob: async () => new Blob(['x'], { type: 'image/png' }),
            }));

            render(<ImageBlock src="https://example.com/image.png" alt="preview" />);
            fireEvent.click(screen.getByRole('img', { name: 'preview' }));
            fireEvent.click(screen.getByRole('button', { name: 'Copy image' }));

            await screen.findByRole('button', { name: 'Image copied' });
            expect(screen.getByRole('button', { name: 'Close zoom' })).toBeInTheDocument();
        });

        it('base64 与 URL 双传入：ClipboardItem 不支持时降级复制 URL 文本而非显示失败', async () => {
            vi.stubGlobal('ClipboardItem', undefined);

            render(<ImageBlock base64Data="iVBORw0KGgo=" src="https://oss.example.com/a.png" mediaType="image/png" alt="preview" />);
            fireEvent.click(screen.getByRole('img', { name: 'preview' }));
            fireEvent.click(screen.getByRole('button', { name: 'Copy image' }));

            expect(await screen.findByRole('button', { name: 'Image copied' })).toBeInTheDocument();
            expect(writeText).toHaveBeenCalledWith('https://oss.example.com/a.png');
        });

        it('base64 与 URL 双传入：clipboard.write 拒绝时降级复制 URL 文本', async () => {
            class MockClipboardItem {
                constructor(public readonly items: Record<string, Blob>) {}
            }
            vi.stubGlobal('ClipboardItem', MockClipboardItem);
            write.mockRejectedValue(new Error('denied'));

            render(<ImageBlock base64Data="iVBORw0KGgo=" src="https://oss.example.com/a.png" mediaType="image/png" alt="preview" />);
            fireEvent.click(screen.getByRole('img', { name: 'preview' }));
            fireEvent.click(screen.getByRole('button', { name: 'Copy image' }));

            expect(await screen.findByRole('button', { name: 'Image copied' })).toBeInTheDocument();
            expect(write).toHaveBeenCalledTimes(1);
            expect(writeText).toHaveBeenCalledWith('https://oss.example.com/a.png');
        });
    });
});
