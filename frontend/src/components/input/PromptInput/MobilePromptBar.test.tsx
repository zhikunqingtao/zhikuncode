import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import PromptInput from './index';
import { usePromptDraftStore } from '@/store/promptDraftStore';
import { useTurnViewStore } from '@/store/turnViewStore';

const mocks = vi.hoisted(() => ({ available: true, start: vi.fn() }));
vi.mock('@/hooks/useResponsive', () => ({ useResponsive: () => ({ isMobile: true }) }));
vi.mock('@/hooks/useAsrAvailability', () => ({ useAsrAvailability: () => mocks.available }));
vi.mock('@/hooks/useVoiceRecorder', () => ({ useVoiceRecorder: () => ({ state: 'idle', elapsedSeconds: 0, error: null, startRecording: mocks.start, stopRecording: vi.fn() }) }));

beforeEach(() => {
    mocks.available = true; mocks.start.mockClear();
    usePromptDraftStore.setState({ drafts: {} });
    useTurnViewStore.setState({ density: 'compact', expandOverrides: {} });
});
function mount() {
    return render(<PromptInput sessionId="mobile-review" onSubmit={vi.fn()} onSlashCommand={vi.fn()} onInterrupt={vi.fn()}
        disabled={false} runActive={false} compacting={false} permissionMode="read_write" messages={[]} commands={[]}
        onPasteImages={vi.fn()} onPublishLocalFile={vi.fn()} fileReferenceCapability={{ mode: 'native_path' }} />);
}
it('默认卡片的语音和密度固定于文本区外，点击语音可开始录音', () => {
    mount();
    const capsule = screen.getByTestId('mobile-prompt-text-area');
    const voice = screen.getByRole('button', { name: '语音输入' });
    expect(capsule).not.toContainElement(voice);
    expect(capsule).not.toContainElement(screen.getByTestId('mobile-density-chip'));
    fireEvent.click(voice);
    expect(mocks.start).toHaveBeenCalledOnce();
    expect(screen.getByTestId('mobile-persistent-actions')).toContainElement(voice);
});
it('聚焦后仍只有一个附件入口，隐藏文件选择器保持可用', () => {
    mount();
    fireEvent.focus(screen.getByRole('textbox'));
    expect(screen.getByTestId('mobile-persistent-actions')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '引用本地文件路径' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '附件与工具' }));
    expect(screen.getAllByRole('button', { name: '图片附件' })).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: '文件引用' })).toHaveLength(1);
    expect(document.querySelectorAll('[data-local-file-reference-input]')).toHaveLength(1);
});
it('语音服务不可用时保留禁用入口及原因', () => {
    mocks.available = false; mount();
    expect(screen.getByRole('button', { name: '语音输入（服务暂不可用）' })).toBeDisabled();
});
it('不聚焦输入框也能打开三档选择并切换', () => {
    mount();
    fireEvent.click(screen.getByTestId('mobile-density-chip'));
    expect(screen.getByRole('dialog', { name: '消息密度' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /^详细/ }));
    expect(useTurnViewStore.getState().density).toBe('detailed');
    expect(screen.getByTestId('mobile-persistent-actions')).toBeInTheDocument();
});
