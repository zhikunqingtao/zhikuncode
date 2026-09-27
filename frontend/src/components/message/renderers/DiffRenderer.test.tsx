import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DiffRenderer, diffStats } from './DiffRenderer';

const diff = '--- /tmp/file\n+++ /tmp/file\n@@ -8,2 +8,2 @@\n context\n---old\n+++new\n\\ No newline at end of file\n@@ -20 +20 @@\n-中文\r\n+新内容\r';

describe('actual unified diff', () => {
    it('excludes file headers and counts +/- source lines inside hunks', () => {
        expect(diffStats(diff)).toEqual({ added: 2, removed: 2 });
        render(<DiffRenderer content={diff} filePath="/tmp/file" />);
        expect(screen.getByText('+2')).toBeInTheDocument();
        expect(screen.getByText('−2')).toBeInTheDocument();
        const oldRow = screen.getByText('--old').parentElement!;
        expect(oldRow.children[0].textContent).toBe('9');
        expect(oldRow.children[1].textContent).toBe('');
        const newRow = screen.getByText('新内容').parentElement!;
        expect(newRow.children[0].textContent).toBe('');
        expect(newRow.children[1].textContent).toBe('20');
    });

    it('does not advance source line numbers for no-newline markers', () => {
        render(<DiffRenderer content={'@@ -1 +1 @@\n-old\n\\ No newline at end of file\n+new\n\\ No newline at end of file'} />);
        const row = screen.getByText('new').parentElement!;
        expect(row.children[1].textContent).toBe('1');
    });

    it('handles zero-length ranges and marks truncated counts as partial', () => {
        expect(diffStats('--- f\n+++ f\n@@ -0,0 +1 @@\n+first')).toEqual({ added: 1, removed: 0 });
        render(<DiffRenderer content={'@@ -1,2 +1 @@\n-old'} filePath="f" truncated />);
        expect(screen.getByText('（已展示部分）')).toBeInTheDocument();
        expect(screen.getByText(/差异过大，仅展示部分内容/)).toBeInTheDocument();
    });
});
