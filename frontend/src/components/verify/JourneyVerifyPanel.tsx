/**
 * JourneyVerifyPanel — PR-C.6 运行时验证（Runtime Verification）进度面板
 *
 * 订阅 journeyVerifyStore，实时展示后端 STOMP 推送的验证步骤进度与最终判定。
 * 仅在状态非 idle 时渲染。
 */

import React, { useState } from 'react';
import { useJourneyVerifyStore } from '@/store/journeyVerifyStore';
import type { JourneyVerifyStatus } from '@/store/journeyVerifyStore';
import { EvidenceBundleView } from '@/components/verify/EvidenceBundleView';

export const JourneyVerifyPanel: React.FC = () => {
    const status = useJourneyVerifyStore((s) => s.status);
    const steps = useJourneyVerifyStore((s) => s.steps);
    const verdict = useJourneyVerifyStore((s) => s.verdict);
    const errorMessage = useJourneyVerifyStore((s) => s.errorMessage);
    const bundleId = useJourneyVerifyStore((s) => s.bundleId);

    const [showEvidence, setShowEvidence] = useState(false);

    if (status === 'idle') return null;

    return (
        <div className="journey-verify-panel border rounded-[14px] p-4 mt-2">
            <div className="flex items-center justify-between mb-1">
                <h3 className=" text-base font-semibold">Runtime Verification</h3>
                <StatusBadge status={status} verdict={verdict} />
            </div>
            <p className="mb-3 text-[13px] text-t2">范围有限：仅覆盖所列步骤在该次运行中的执行状态</p>

            <div className="space-y-1">
                {steps.map((step) => (
                    <div key={step.stepIndex} className="flex items-center gap-2 text-[13px]">
                        <span className={step.ok ? 'text-ok' : 'text-err'}>
                            {step.ok ? '✓' : '✗'}
                        </span>
                        <span className="font-mono">{step.action}</span>
                        <span className="text-t2 ml-auto">{step.durationMs}ms</span>
                    </div>
                ))}
            </div>

            {errorMessage && (
                <div className="mt-2 text-[13px] text-err bg-errsoft rounded p-2">
                    {errorMessage}
                </div>
            )}

            {bundleId && (status === 'passed' || status === 'failed') && (
                <>
                    <button
                        type="button"
                        onClick={() => setShowEvidence((v) => !v)}
                        className="panel-control mt-2 text-[13px] text-accent2-ink hover:text-accent2-ink hover:underline cursor-pointer flex items-center gap-1"
                    >
                        <span>{showEvidence ? '▼' : '▶'}</span>
                        <span>Evidence: {bundleId.slice(0, 12)}…</span>
                    </button>
                    {showEvidence && <EvidenceBundleView bundleId={bundleId} />}
                </>
            )}
        </div>
    );
};

interface StatusBadgeProps {
    status: JourneyVerifyStatus;
    verdict: string | null;
}

const StatusBadge: React.FC<StatusBadgeProps> = ({ status, verdict }) => {
    const v = (verdict || '').toLowerCase();
    if (status === 'running') {
        return <span className="px-2 py-0.5 text-[13px] rounded bg-accent2-soft text-accent2-ink">Running...</span>;
    }
    if (v === 'verified' || v === 'passed') {
        return <span className="px-2 py-0.5 text-[13px] rounded bg-oksoft text-ok">Passed</span>;
    }
    if (v === 'unavailable') {
        return <span className="px-2 py-0.5 text-[13px] rounded bg-warnsoft text-warn">Unavailable</span>;
    }
    if (v === 'inconclusive') {
        return <span className="px-2 py-0.5 text-[13px] rounded bg-warnsoft text-warn">Inconclusive</span>;
    }
    if (v === 'failed') {
        return <span className="px-2 py-0.5 text-[13px] rounded bg-errsoft text-err">Failed</span>;
    }
    return <span className="px-2 py-0.5 text-[13px] rounded bg-accent2-soft text-accent2-ink">范围未知</span>;
};

export default JourneyVerifyPanel;
