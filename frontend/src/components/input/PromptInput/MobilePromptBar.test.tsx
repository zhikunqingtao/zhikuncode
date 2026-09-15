import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import PromptInput from './index';
import { usePromptDraftStore } from '@/store/promptDraftStore';
import { useWorkbenchViewStore } from '@/store/workbenchViewStore';
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
function mount(runActive = false) {
    return render(<PromptInput sessionId="mobile-review" onSubmit={vi.fn()} onSlashCommand={vi.fn()} onInterrupt={vi.fn()}
        disabled={false} runActive={runActive} compacting={false} permissionMode="read_write" messages={[]} commands={[]}
        onPasteImages={vi.fn()} onPublishLocalFile={vi.fn()} fileReferenceCapability={{ mode: 'native_path' }} />);
}
it('运行中使用与桌面一致的指令提示', () => {
    mount(true);
    expect(screen.getByRole('textbox')).toHaveAttribute(
        'placeholder',
        '输入补充指令，将在本次操作完成后执行…',
    );
});
it('默认卡片的语音固定于文本区外，密度有独立入口', () => {
    mount();
    const capsule = screen.getByTestId('mobile-prompt-text-area');
    const voice = screen.getByRole('button', { name: '语音输入' });
    expect(capsule).not.toContainElement(voice);
    expect(screen.queryByTestId('mobile-density-chip')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '更多' })).toBeInTheDocument();
    fireEvent.click(voice);
    expect(mocks.start).toHaveBeenCalledOnce();
    expect(screen.getByTestId('mobile-persistent-actions')).toContainElement(voice);
});
it('聚焦后仍只有一个附件入口，隐藏文件选择器保持可用', () => {
    mount();
    fireEvent.focus(screen.getByRole('textbox'));
    expect(screen.getByTestId('mobile-persistent-actions')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '引用本地文件路径' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '附件与工具' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '拍照' })).toBeInTheDocument();
    expect(document.querySelector('[data-mobile-camera-input]')).toHaveAttribute('capture', 'environment');
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
    fireEvent.click(screen.getByRole('button', { name: /^消息密度：/ }));
    expect(screen.getByRole('dialog', { name: '消息密度' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: /^详细/ }));
    expect(useTurnViewStore.getState().density).toBe('detailed');
    expect(screen.getByTestId('mobile-persistent-actions')).toBeInTheDocument();
});

it('工作台独立选择保留默认偏好，更多不再重复工作台和密度', () => {
    useWorkbenchViewStore.setState({ enabled: true, viewMode: 'development', defaultView: 'development' });
    mount();
    fireEvent.click(screen.getByRole('button', { name: /^工作台：/ }));
    fireEvent.click(screen.getByRole('button', { name: '简洁工作台' }));
    expect(useWorkbenchViewStore.getState().viewMode).toBe('simple');
    fireEvent.click(screen.getByRole('button', { name: '设为默认工作台' }));
    expect(useWorkbenchViewStore.getState().defaultView).toBe('simple');
    fireEvent.click(screen.getByRole('button', { name: '关闭工作台' }));
    fireEvent.click(screen.getByRole('button', { name: '更多' }));
    expect(screen.queryByRole('button', { name: '简洁工作台' })).not.toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: /^详细/ })).not.toBeInTheDocument();
});

it('命令位于附件和语音之间，点击打开斜杠命令入口', () => {
    const previousScroll = HTMLElement.prototype.scrollIntoView;
    HTMLElement.prototype.scrollIntoView = vi.fn();
    mount();
    const actions = screen.getByTestId('mobile-persistent-actions');
    const buttons = Array.from(actions.querySelectorAll('button'));
    expect(buttons.slice(0, 5).map(button => button.getAttribute('aria-label'))).toEqual(['文件引用', '图片附件', '拍照', '命令', '语音输入']);
    fireEvent.click(screen.getByRole('button', { name: '命令' }));
    expect(screen.getByRole('textbox')).toHaveValue('/');
    HTMLElement.prototype.scrollIntoView = previousScroll;
});
