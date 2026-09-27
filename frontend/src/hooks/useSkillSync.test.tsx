import { act, cleanup, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useSkillSync } from './useSkillSync';
import { useSkillStore } from '@/store/skillStore';

const originalLoad = useSkillStore.getState().loadSkills;

describe('useSkillSync', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    cleanup();
    useSkillStore.setState({ loadSkills: originalLoad });
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('refreshes visible clients every five seconds and on focus, then cleans up', () => {
    const loadSkills = vi.fn(async () => {});
    useSkillStore.setState({ loadSkills });
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    const { unmount } = renderHook(() => useSkillSync());
    expect(loadSkills).toHaveBeenCalledOnce();
    act(() => vi.advanceTimersByTime(5000));
    expect(loadSkills).toHaveBeenCalledTimes(2);
    act(() => window.dispatchEvent(new Event('focus')));
    expect(loadSkills).toHaveBeenCalledTimes(3);
    unmount();
    act(() => {
      vi.advanceTimersByTime(5000);
      window.dispatchEvent(new Event('focus'));
    });
    expect(loadSkills).toHaveBeenCalledTimes(3);
  });

  it('pauses background polling while hidden and refreshes when visible again', () => {
    const loadSkills = vi.fn(async () => {});
    useSkillStore.setState({ loadSkills });
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    renderHook(() => useSkillSync());
    act(() => vi.advanceTimersByTime(15000));
    expect(loadSkills).toHaveBeenCalledOnce();
    visibility.mockReturnValue('visible');
    act(() => document.dispatchEvent(new Event('visibilitychange')));
    expect(loadSkills).toHaveBeenCalledTimes(2);
  });
});
