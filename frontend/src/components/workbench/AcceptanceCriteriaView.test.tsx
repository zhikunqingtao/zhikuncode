import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { WorkbenchCriterion } from '@/hooks/useSimpleWorkbenchData';
import { AcceptanceCriteriaView } from './AcceptanceCriteriaView';

function criterion(status: string): WorkbenchCriterion {
    return {
        id: 'criterion-1',
        type: 'business',
        text: '必须生成当前报告',
        status: status as WorkbenchCriterion['status'],
        detail: null,
        evidenceBundleId: null,
    };
}

describe('AcceptanceCriteriaView', () => {
    afterEach(cleanup);

    it('renders unknown statuses without crashing and shows neutral wording', () => {
        render(<AcceptanceCriteriaView
            business={[criterion('SOMETHING_NEW')]}
            technical={[criterion('MYSTERY_STATE')]}
            overall={'SOMETHING_NEW' as WorkbenchCriterion['status']}
        />);

        expect(screen.getAllByText('范围未知').length).toBeGreaterThanOrEqual(2);
        expect(screen.queryByText(/undefined/)).not.toBeInTheDocument();
    });

    it('does not present partial coverage as a partial pass', () => {
        render(<AcceptanceCriteriaView
            business={[criterion('PARTIAL')]}
            technical={[]}
            overall="PARTIAL"
        />);

        expect(screen.getAllByText('核验未完成')).toHaveLength(2);
        expect(screen.queryByText('部分通过')).not.toBeInTheDocument();
    });
});
