import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useConfigStore } from '@/store/configStore';
import { resolveZkSyntaxStyle } from '@/styles/zkSyntax';
import type { ThemeConfig } from '@/types';
import CodeBlock from './CodeBlock';

const originalTheme = useConfigStore.getState().theme;
const longCode = Array.from({ length: 100 }, (_, i) => `const value${i} = ${i};`).join('\n');

function setMode(mode: ThemeConfig['mode']) {
    act(() => { useConfigStore.getState().setTheme({ mode }); });
}

afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    act(() => { useConfigStore.setState({ theme: originalTheme }); });
});

describe('CodeBlock ink 配色与交互回归', () => {
    it.each(['ink-havoc', 'ink-havoc-night'] as const)('%s：标题、控件与长代码使用代码块本地色域', mode => {
        setMode(mode);
        const { container } = render(
            <div className="user-message"><div className="bg-surfacev2">
                <CodeBlock code={longCode} fileName="source.ts" />
            </div></div>,
        );
        const block = container.querySelector<HTMLElement>('.code-block')!;
        expect(block).toHaveAttribute('data-spider-exclude', 'code-block');
        const style = resolveZkSyntaxStyle(mode);
        expect(block.style.getPropertyValue('--code-ink-text')).toBe(style['pre[class*="language-"]'].color);
        expect(block.style.getPropertyValue('--code-ink-muted')).toBe(style.comment.color);
        expect(block).toHaveStyle({ color: style['pre[class*="language-"]'].color });
        expect(screen.getByText('source.ts')).toHaveClass('text-[color:var(--code-ink-muted)]');
        expect(screen.getByRole('button', { name: 'Copy code' })).toHaveClass(
            'text-[color:var(--code-ink-muted)]', 'hover:text-[color:var(--code-ink-text)]',
        );
        const pre = container.querySelector('pre')!;
        expect(pre).toHaveClass('text-[color:var(--code-ink-text)]');
        expect(pre.textContent).toBe(longCode);
        expect(container.querySelector('.token')).toBeNull();

        fireEvent.click(screen.getByRole('button', { name: 'Enable highlighting' }));
        expect(screen.queryByRole('button', { name: 'Enable highlighting' })).toBeNull();
        expect(container.querySelector('.token')).not.toBeNull();
    });

    it.each(['light', 'dark', 'glass', 'spaceship'] as const)('从 ink 切回 %s 移除局部色域并恢复原控件样式', mode => {
        setMode('ink-havoc');
        const { container } = render(<CodeBlock code={longCode} fileName="source.ts" />);
        setMode(mode);
        const block = container.querySelector<HTMLElement>('.code-block')!;
        expect(block.style.getPropertyValue('--code-ink-text')).toBe('');
        expect(block.style.getPropertyValue('--code-ink-muted')).toBe('');
        expect(block.style.color).toBe('');
        expect(screen.getByText('source.ts')).toHaveClass('text-t3');
        expect(screen.getByRole('button', { name: 'Copy code' })).toHaveClass('text-t4', 'hover:text-t1');
        expect(container.querySelector('pre')).toHaveClass('text-t1');
    });

    it.each(['ink-havoc', 'ink-havoc-night'] as const)('%s：复制保留完整文本及绿色成功反馈', async mode => {
        vi.useFakeTimers();
        const writeText = vi.fn().mockResolvedValue(undefined);
        vi.stubGlobal('navigator', { clipboard: { writeText } });
        setMode(mode);
        render(<CodeBlock code={longCode} />);
        const copyButton = screen.getByRole('button', { name: 'Copy code' });
        await act(async () => { fireEvent.click(copyButton); });
        expect(writeText).toHaveBeenCalledWith(longCode);
        expect(copyButton).toHaveAttribute('title', '已复制');
        expect(copyButton.querySelector('.lucide-check')).toHaveStyle({ color: resolveZkSyntaxStyle(mode).string.color });
        act(() => { vi.advanceTimersByTime(2000); });
        expect(copyButton).toHaveAttribute('title', '复制');
    });
});
