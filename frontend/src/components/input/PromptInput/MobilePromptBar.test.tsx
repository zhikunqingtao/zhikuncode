import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import PromptInput from './index';
import { usePromptDraftStore } from '@/store/promptDraftStore';
import { useModelStore } from '@/store/modelStore';
import { useTurnViewStore } from '@/store/turnViewStore';

const mocks = vi.hoisted(() => ({ available: true, start: vi.fn() }));
vi.mock('@/hooks/useResponsive', () => ({ useResponsive: () => ({ isMobile: true }) }));
vi.mock('@/hooks/useAsrAvailability', () => ({ useAsrAvailability: () => mocks.available }));
vi.mock('@/hooks/useVoiceRecorder', () => ({ useVoiceRecorder: () => ({ state: 'idle', elapsedSeconds: 0, error: null, startRecording: mocks.start, stopRecording: vi.fn() }) }));

beforeEach(() => {
    mocks.available = true; mocks.start.mockClear();
    usePromptDraftStore.setState({ drafts: {} });
    useTurnViewStore.setState({ density: 'compact', expandOverrides: {} });
    useModelStore.setState({ models: [{ id: 'kimi-k3', displayName: 'Kimi K3', maxImages: 0, supportsImages: false }], loading: false, error: null });
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
it('不聚焦输入框也能打开三档选择并切换（上下列表）', () => {
    mount();
    fireEvent.click(screen.getByRole('button', { name: /^密度：/ }));
    expect(screen.getByRole('dialog', { name: '选择密度' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /^详细/ }));
    expect(useTurnViewStore.getState().density).toBe('detailed');
    expect(screen.getByTestId('mobile-persistent-actions')).toBeInTheDocument();
});

it('导航顺序统一为状态权限、模型、密度、更多（与桌面一致）', () => {
    mount();
    const nav = document.querySelector('.mobile-composer-navigation');
    expect(nav).not.toBeNull();
    const names = Array.from(nav!.querySelectorAll('button')).map(b => b.getAttribute('aria-label') ?? b.textContent);
    expect(names).toEqual([
        expect.stringMatching(/^权限：/),
        expect.stringMatching(/^模型：/),
        expect.stringMatching(/^密度：/),
        '更多',
    ]);
});

it('导航提供模型切换入口，更多不再重复模型和密度', () => {
    mount();
    expect(screen.queryByRole('button', { name: /^工作台：/ })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /^模型：/ }));
    expect(screen.getByRole('dialog', { name: '选择模型' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '完成' }));
    fireEvent.click(screen.getByRole('button', { name: '更多' }));
    const moreDialog = screen.getByRole('dialog', { name: '更多操作' });
    expect(within(moreDialog).queryByRole('region', { name: '模型选择' })).not.toBeInTheDocument();
    expect(within(moreDialog).queryByRole('tab', { name: /^详细/ })).not.toBeInTheDocument();
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
