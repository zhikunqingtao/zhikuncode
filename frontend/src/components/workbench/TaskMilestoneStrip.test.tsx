import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { CurrentWorkbenchView } from '@/hooks/useSimpleWorkbenchData';
import { TaskMilestoneStrip } from './TaskMilestoneStrip';

function viewWith(overallStatus: string): CurrentWorkbenchView {
    return {
        rootRun: null,
        request: null,
        delivery: { manifests: [], files: [], totalFiles: 0, primaryArtifactPath: null },
        verification: { businessCriteria: [], technicalChecks: [], evidence: [], overallStatus },
    } as unknown as CurrentWorkbenchView;
}

describe('TaskMilestoneStrip', () => {
    afterEach(cleanup);

    it('falls back to neutral wording instead of undefined for unknown verification status', () => {
        render(<TaskMilestoneStrip current={viewWith('SOMETHING_NEW')} />);

        expect(screen.getByLabelText('任务里程碑')).toBeInTheDocument();
        expect(screen.getByTitle('核验范围未知')).toBeInTheDocument();
        expect(screen.queryByTitle('undefined')).not.toBeInTheDocument();
    });

    it('scopes the positive verification wording to the listed checks', () => {
        render(<TaskMilestoneStrip current={viewWith('PASSED')} />);

        expect(screen.getByTitle('所列检查已通过')).toBeInTheDocument();
    });

    it('does not claim that partial verification has checked some requirements', () => {
        render(<TaskMilestoneStrip current={viewWith('PARTIAL')} />);

        expect(screen.getByTitle('核验未完成')).toBeInTheDocument();
        expect(screen.queryByTitle('部分要求已检查')).not.toBeInTheDocument();
    });
});
