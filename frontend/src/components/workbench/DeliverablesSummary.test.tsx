import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { DeliveryFileView } from '@/hooks/useSimpleWorkbenchData';
import { DeliverablesSummary } from './DeliverablesSummary';

const files: DeliveryFileView[] = [
    { manifestId: 'm1', workspaceRoot: '/workspace', id: 'code', filePath: '/workspace/src/main.ts', relativePath: 'src/main.ts', operation: 'modified', state: 'sealed', fileSize: 512, verified: false, mismatchDetail: null, primary: false },
    { manifestId: 'm1', workspaceRoot: '/workspace', id: 'html', filePath: '/workspace/report.html', relativePath: 'report.html', operation: 'created', state: 'integrity_verified', fileSize: 1024, verified: true, mismatchDetail: null, primary: true },
];

describe('DeliverablesSummary', () => {
    afterEach(cleanup);

    it('uses the backend primary file and exposes only real actions', () => {
        const onOpenFile = vi.fn();
        render(<DeliverablesSummary files={files} loading={false} error={null} onOpenFile={onOpenFile} onPreviewFile={onOpenFile} onRevealFile={onOpenFile} />);

        expect(screen.getByText('report.html')).toBeInTheDocument();
        expect(screen.queryByRole('button', { name: '查看修改记录' })).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: '在文件夹中显示' }));
        expect(onOpenFile).toHaveBeenCalledWith('/workspace/report.html');
    });
});
