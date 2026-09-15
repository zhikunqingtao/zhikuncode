import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { useMessageStore } from '@/store/messageStore';
import { useTurnViewStore } from '@/store/turnViewStore';
import { buildTurns } from '@/store/selectors/turnProjection';
import { appendStreamDelta, flushStreamingBuffer } from '@/hooks/useStreamingText';
import TurnCard from '../TurnCard';
vi.mock('@/hooks/useTtsAvailability', () => ({ useTtsAvailability: () => false }));

function Conversation() {
    const state = useMessageStore();
    return <TurnCard turn={buildTurns(state.messages)[0]} sessionId="s" isRunActive
        streamingMessageId={state.streamingMessageId} streamingContent={state.streamingContent}
        thinkingContent={state.thinkingContent} activeToolCalls={state.activeToolCalls} />;
}
beforeEach(() => {
    useMessageStore.getState().clearMessages();
    useMessageStore.getState().addMessage({ type: 'user', uuid: 'u', timestamp: 1, content: [{ type: 'text', text: '完整用户指令' }] });
    useMessageStore.getState().addMessage({ type: 'system', uuid: 'b', timestamp: 2, content: '', subtype: 'task_boundary', metadata: { task_id: 'A', title: '任务 A', seq: 1 } });
    useMessageStore.getState().appendStreamDelta('');
});
it.each(['compact', 'balanced', 'detailed'] as const)('%s follows density when tools appear and applies the same disclosure defaults to streaming and final answers', density => {
    useTurnViewStore.setState({ density, expandOverrides: {} });
    render(<Conversation />);
    act(() => { appendStreamDelta('检查文件'); flushStreamingBuffer(); });
    if (density === 'compact') {
        expect(screen.queryByText('检查文件')).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: /最终回复/ })).toHaveAttribute('aria-expanded', 'false');
    } else expect(screen.getByText('检查文件')).toBeInTheDocument();
    act(() => { useMessageStore.getState().startToolCall('read', 'Read', { file_path: '/example' }); });
    expect(screen.getByText('完整用户指令')).toBeInTheDocument();
    if (density === 'detailed') {
        expect(screen.getByText('检查文件')).toBeInTheDocument();
        act(() => { appendStreamDelta('，继续'); flushStreamingBuffer(); });
        expect(screen.getByText('检查文件，继续')).toBeInTheDocument();
    } else {
        expect(screen.queryByText('检查文件')).not.toBeInTheDocument();
    }
    act(() => {
        useMessageStore.getState().finalizeAssistantSegment();
        useMessageStore.getState().appendStreamDelta('完整最终回复');
    });
    if (density === 'compact') {
        expect(screen.queryByText('完整最终回复')).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: /最终回复/ }));
    }
    expect(screen.getByText('完整最终回复')).toBeInTheDocument();
});
