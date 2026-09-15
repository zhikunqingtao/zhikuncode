/**
 * BackToLatestCapsule 组件测试 + 显隐纯函数（shouldShowBackToLatest）测试
 * 覆盖：显隐逻辑（atBottom / 空会话）、点击回调、隐藏态 a11y（aria-hidden + 移出 Tab 序）、
 * run 进行中 accent 呼吸点。
 */

import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import BackToLatestCapsule, { shouldShowBackToLatest } from './BackToLatestCapsule';

describe('shouldShowBackToLatest 显隐纯函数', () => {
    it('在底部 → 不显示', () => {
        expect(shouldShowBackToLatest(true, 5)).toBe(false);
    });

    it('不在底部且有消息 → 显示', () => {
        expect(shouldShowBackToLatest(false, 5)).toBe(true);
    });

    it('空会话（messages=0）→ 不显示', () => {
        expect(shouldShowBackToLatest(false, 0)).toBe(false);
        expect(shouldShowBackToLatest(true, 0)).toBe(false);
    });
});

describe('BackToLatestCapsule 组件', () => {
    it('visible=true → 可点击并触发 onClick（平滑滚底由调用方执行）', () => {
        const onClick = vi.fn();
        render(<BackToLatestCapsule visible isRunActive={false} onClick={onClick} />);
        const button = screen.getByTestId('back-to-latest');
        expect(button).toHaveTextContent('最新进展');
        expect(button).not.toHaveAttribute('aria-hidden', 'true');
        fireEvent.click(button);
        expect(onClick).toHaveBeenCalledTimes(1);
    });

    it('visible=false → aria-hidden + 移出 Tab 序（不遮挡消息交互）', () => {
        render(<BackToLatestCapsule visible={false} isRunActive={false} onClick={() => {}} />);
        const button = screen.getByTestId('back-to-latest');
        expect(button).toHaveAttribute('aria-hidden', 'true');
        expect(button).toHaveAttribute('tabindex', '-1');
        expect(button).toBeDisabled();
        expect(button).toHaveClass('pointer-events-none');
        expect(button).not.toHaveClass('pointer-events-auto');
    });

    it('run 进行中 → accent 呼吸点', () => {
        const { container } = render(
            <BackToLatestCapsule visible isRunActive onClick={() => {}} />,
        );
        expect(container.querySelector('.animate-accent-pulse')).not.toBeNull();
    });

    it('run 未进行 → 无呼吸点', () => {
        const { container } = render(
            <BackToLatestCapsule visible isRunActive={false} onClick={() => {}} />,
        );
        expect(container.querySelector('.animate-accent-pulse')).toBeNull();
    });
});
