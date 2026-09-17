import { render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import PromptSendButton from './PromptSendButton';

const base = { sendDisabled: false, stopDisabled: false, onSend: () => {}, onInterrupt: () => {} };

it('运行时桌面/平板与手机的停止按钮统一为实心红底圆角方块 + 同款动效', () => {
    // 桌面/平板（desktop variant）
    const { unmount } = render(<PromptSendButton runActive {...base} />);
    const desktopStop = screen.getByRole('button', { name: '停止当前任务' });
    expect(desktopStop.className).toContain('bg-err');
    expect(desktopStop.className).toContain('rounded-[10px]');
    expect(desktopStop.className).toContain('stop-btn-running');
    unmount();

    // 手机（mobile variant）：同为实心红底 + 圆角矩形 + 同款动效，仅保留 44px 触控高度
    render(<PromptSendButton variant="mobile" runActive {...base} />);
    const mobileStop = screen.getByRole('button', { name: '停止当前任务' });
    expect(mobileStop.className).toContain('bg-err');
    expect(mobileStop.className).toContain('rounded-[10px]');
    expect(mobileStop.className).toContain('stop-btn-running');
    expect(mobileStop.className).toContain('h-11');
});

it('非运行状态不渲染停止按钮', () => {
    render(<PromptSendButton runActive={false} {...base} />);
    expect(screen.queryByRole('button', { name: '停止当前任务' })).not.toBeInTheDocument();
});

it('运行时发送按钮切换为干预语义', () => {
    render(<PromptSendButton variant="mobile" runActive {...base} />);
    expect(screen.getByRole('button', { name: '发送运行中干预' })).toBeInTheDocument();
});
