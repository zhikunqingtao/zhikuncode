import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { GitCommitPanel } from '../GitCommitPanel';
import { GitDiffPanel } from '../GitDiffPanel';
afterEach(cleanup);
it('keeps commit disabled until a message exists and submits exactly that message', async () => {
    const onCommit = vi.fn();
    render(<GitCommitPanel data={{ status: '', stagedDiff: '', detailedDiff: '', changedFiles: ['src/main.ts'], fileCount: 1 }} onCommit={onCommit} onGenerateMessage={async () => '整理面板样式'} />);
    const buttons = screen.getAllByRole('button');
    expect(buttons.at(-1)).toBeDisabled();
    fireEvent.click(buttons[0]);
    await waitFor(() => expect(screen.getByRole('textbox')).toHaveValue('整理面板样式'));
    expect(onCommit).not.toHaveBeenCalled();
    fireEvent.click(buttons.at(-1)!);
    expect(onCommit).toHaveBeenCalledTimes(1);
    expect(onCommit).toHaveBeenCalledWith('整理面板样式');
});
it('expands and collapses the original added and deleted diff lines', () => {
    render(<GitDiffPanel data={{ staged: false, stat: '', fileCount: 1, diff: 'diff --git a/demo.ts b/demo.ts\n--- a/demo.ts\n+++ b/demo.ts\n@@ -1 +1 @@\n-oldValue\n+newValue' }} />);
    const toggle = screen.getByRole('button', { name: /demo.ts/ });
    expect(screen.queryByText('+newValue')).not.toBeInTheDocument();
    fireEvent.click(toggle);
    expect(screen.getByText('+newValue')).toBeVisible();
    expect(screen.getByText('-oldValue')).toBeVisible();
    fireEvent.click(toggle);
    expect(screen.queryByText('+newValue')).not.toBeInTheDocument();
});
