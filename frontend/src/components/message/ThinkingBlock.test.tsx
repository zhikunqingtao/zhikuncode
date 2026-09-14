import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import ThinkingBlock from './ThinkingBlock';

describe('ThinkingBlock 折叠态耗时文案', () => {
    it('durationMs 存在且 ≥1s 时显示「已思考 Ns」', () => {
        render(<ThinkingBlock content="some reasoning" durationMs={8000} />);
        expect(screen.getByText('已思考 8s')).toBeInTheDocument();
        expect(screen.queryByText('some reasoning')).not.toBeInTheDocument();
    });

    it('durationMs 不足 1 秒时显示「已思考 <1s」', () => {
        render(<ThinkingBlock content="some reasoning" durationMs={500} />);
        expect(screen.getByText('已思考 <1s')).toBeInTheDocument();
    });

    it('durationMs 缺失时保持原预览文案', () => {
        render(<ThinkingBlock content="some reasoning" />);
        expect(screen.getByText('some reasoning')).toBeInTheDocument();
        expect(screen.queryByText(/已思考/)).not.toBeInTheDocument();
    });

    it('展开行为不变：点击后展示思考内容', () => {
        render(<ThinkingBlock content="some reasoning" durationMs={8000} />);
        fireEvent.click(screen.getByRole('button'));
        // 展开后 header 恢复 Thinking 标题，正文可见
        expect(screen.getByText('Thinking')).toBeInTheDocument();
        expect(screen.getByText('some reasoning')).toBeInTheDocument();
    });
});
