import { fireEvent, render, screen, waitFor, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TurnFileChanges } from './TurnFileChanges';
import type { TurnFileChange } from '@/store/selectors/turnFileChanges';
afterEach(cleanup);
const files: TurnFileChange[] = Array.from({length: 7}, (_, i) => ({ path: `/src/file${i}.ts`, operations: [{ id: `${i}-1`, kind: '编辑', label: '修改片段', content: '- old\n+ new' }, { id: `${i}-2`, kind: '写入', label: '写入内容', content: 'latest' }] }));
describe('TurnFileChanges', () => {
    it('renders nothing without proven modifications', () => {
        const { container } = render(<TurnFileChanges files={[]} running={false} />);
        expect(container).toBeEmptyDOMElement();
    });
    it('starts collapsed, limits list, opens latest record and returns focus', async () => {
        render(<TurnFileChanges files={files} running={false} />);
        expect(screen.queryByText('file0.ts')).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: '本轮修改 · 7 个文件' }));
        expect(screen.queryByText('file6.ts')).not.toBeInTheDocument();
        fireEvent.click(screen.getByText('查看全部 7 个文件'));
        const trigger = screen.getByRole('button', { name: /file6.ts/ });
        trigger.focus(); fireEvent.click(trigger);
        expect(screen.getByRole('dialog')).toBeInTheDocument();
        expect(screen.getByText('latest')).toBeInTheDocument();
        expect(screen.getByRole('button', { name: /第 1 次/ })).toHaveAttribute('aria-expanded', 'false');
        fireEvent.keyDown(document, { key: 'Escape' });
        await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
        expect(trigger).toHaveFocus();
    });
    it('updates without switching the open record, and reports clipboard failure', async () => {
        const { rerender } = render(<TurnFileChanges files={files} running />);
        fireEvent.click(screen.getByRole('button', { name: '本轮已记录修改 · 7 个文件' }));
        fireEvent.click(screen.getByRole('button', { name: /file0.ts/ }));
        const next = files.map((f, i) => i ? f : {...f, operations: [...f.operations, { id: 'new', kind: '写入' as const, label: '写入内容' as const, content: 'newest' }]});
        rerender(<TurnFileChanges files={next} running={false} />);
        expect(screen.queryByText('newest')).not.toBeInTheDocument();
        expect(screen.getByText('latest')).toBeInTheDocument();
        Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: vi.fn().mockRejectedValue(new Error()) } });
        fireEvent.click(screen.getByRole('button', { name: '复制文件路径' }));
        await screen.findByText('复制失败，请选择下方路径手动复制');
    });
});
