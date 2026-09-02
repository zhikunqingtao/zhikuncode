import { renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { usePageExitGuard } from './usePageExitGuard';

describe('usePageExitGuard', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('blocks a hard page exit and removes the listener on unmount', () => {
    const addListener = vi.spyOn(window, 'addEventListener');
    const removeListener = vi.spyOn(window, 'removeEventListener');
    const { unmount } = renderHook(() => usePageExitGuard());

    const registration = addListener.mock.calls.find(([type]) => type === 'beforeunload');
    expect(registration).toBeDefined();

    const handler = registration?.[1] as (event: BeforeUnloadEvent) => void;
    const event = {
      preventDefault: vi.fn(),
      returnValue: undefined,
    } as unknown as BeforeUnloadEvent;

    handler(event);

    expect(event.preventDefault).toHaveBeenCalledOnce();
    expect(event.returnValue).toBe(true);

    unmount();
    expect(removeListener).toHaveBeenCalledWith('beforeunload', handler);
  });
});
