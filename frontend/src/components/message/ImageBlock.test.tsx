import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import ImageBlock from './ImageBlock';

describe('ImageBlock', () => {
    afterEach(cleanup);

    it('closes the enlarged image when the close button is clicked', () => {
        render(<ImageBlock src="https://example.com/image.png" alt="preview" />);

        fireEvent.click(screen.getByRole('img', { name: 'preview' }));
        const closeButton = screen.getByRole('button', { name: 'Close zoom' });

        fireEvent.click(closeButton);

        expect(screen.queryByRole('button', { name: 'Close zoom' })).not.toBeInTheDocument();
    });
});
