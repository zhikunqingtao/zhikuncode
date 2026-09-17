import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { SessionTitle } from './SessionTitle';

it('详情保留完整 ID，只有复制成功后显示成功，失败允许手动复制', async () => {
    const writeText = vi.fn().mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('denied'));
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    render(<SessionTitle title="检查长会话" sessionId="full-session-123456789" connection="已连接" />);
    // 单行透出：连接状态 + 会话 ID 前 8 位
    expect(screen.getByText('已连接 · full-ses')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '查看会话详情' }));
    expect(screen.getByText('full-session-123456789')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '复制会话 ID' }));
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('已复制'));
    expect(writeText).toHaveBeenCalledWith('full-session-123456789');
    fireEvent.click(screen.getByRole('button', { name: '复制会话 ID' }));
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('复制失败'));
});

it('无会话时仅显示连接状态，不透出 ID 片段', () => {
    render(<SessionTitle title="新任务" sessionId={null} connection="连接中" />);
    expect(screen.getByText('连接中')).toBeInTheDocument();
    expect(screen.queryByText(/·/)).not.toBeInTheDocument();
});
