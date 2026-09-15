import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { Drawer } from './Drawer';
vi.mock('@/components/theme/GlassMaterial', () => ({ GlassMaterial: () => null }));
afterEach(cleanup);
it('portals outside workspace stacking contexts and disables the closed panel', () => {
    const close = vi.fn();
    const { container, rerender } = render(<Drawer open onClose={close}><button>外观</button></Drawer>);
    const panel = screen.getByRole('dialog', { name: '侧边栏' });
    expect(container).not.toContainElement(panel);
    expect(panel.parentElement).toBe(document.body);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(close).toHaveBeenCalledOnce();
    rerender(<Drawer open={false} onClose={close}><button>外观</button></Drawer>);
    expect(panel).toHaveAttribute('inert');
    expect(panel).toHaveAttribute('aria-hidden', 'true');
    expect(document.body.style.overflow).toBe('');
});
