import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { SessionTitle } from './SessionTitle';

it('详情保留完整 ID，只有复制成功后显示成功，失败允许手动复制', async () => {
    const writeText = vi.fn().mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('denied'));
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    render(<SessionTitle title="检查长会话" sessionId="full-session-123456789" connection="已连接" />);
    fireEvent.click(screen.getByRole('button', { name: '查看会话详情' }));
    expect(screen.getByText('full-session-123456789')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '复制会话 ID' }));
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('已复制'));
    expect(writeText).toHaveBeenCalledWith('full-session-123456789');
    fireEvent.click(screen.getByRole('button', { name: '复制会话 ID' }));
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('复制失败'));
});
