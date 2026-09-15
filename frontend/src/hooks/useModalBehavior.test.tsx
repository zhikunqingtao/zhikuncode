import { useRef } from 'react';
import { fireEvent, render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useModalBehavior } from './useModalBehavior';
function Panel({ name, close }: { name: string; close: () => void }) {
  const ref = useRef<HTMLDivElement>(null);
  useModalBehavior(true, ref, close);
  return <div ref={ref} role="dialog" aria-modal="true" aria-label={name} tabIndex={-1}>
    <button>{name} first</button><div style={{ display: 'none' }}><button>hidden</button></div><button>{name} last</button>
  </div>;
}
afterEach(cleanup);
describe('modal stack', () => {
  it('only closes the top dialog and retains scroll lock until both close', () => {
    const lower = vi.fn(), upper = vi.fn();
    document.body.style.overflow = 'auto';
    const a = render(<Panel name="lower" close={lower} />);
    const b = render(<Panel name="upper" close={upper} />);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(upper).toHaveBeenCalledOnce();
    expect(lower).not.toHaveBeenCalled();
    b.unmount();
    expect(document.body.style.overflow).toBe('hidden');
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(lower).toHaveBeenCalledOnce();
    a.unmount();
    expect(document.body.style.overflow).toBe('auto');
  });
  it('wraps focus and preserves focus on rerender', () => {
    const view = render(<Panel name="panel" close={() => {}} />);
    const first = screen.getByText('panel first'), last = screen.getByText('panel last');
    last.focus();
    fireEvent.keyDown(document, { key: 'Tab' });
    expect(document.activeElement).toBe(first);
    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(last);
    view.rerender(<Panel name="panel" close={() => {}} />);
    expect(document.activeElement).toBe(last);
  });
});
