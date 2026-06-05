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
        <div className="journey-verify-panel border rounded-lg p-4 mt-2">
            <div className="flex items-center justify-between mb-3">
                <h3 className="text-sm font-medium">Runtime Verification</h3>
                <StatusBadge status={status} verdict={verdict} />
            </div>

            <div className="space-y-1">
                {steps.map((step) => (
                    <div key={step.stepIndex} className="flex items-center gap-2 text-xs">
                        <span className={step.ok ? 'text-green-500' : 'text-red-500'}>
                            {step.ok ? '✓' : '✗'}
                        </span>
                        <span className="font-mono">{step.action}</span>
                        <span className="text-gray-400 ml-auto">{step.durationMs}ms</span>
                    </div>
                ))}
            </div>

            {errorMessage && (
                <div className="mt-2 text-xs text-red-600 bg-red-50 rounded p-2">
                    {errorMessage}
                </div>
            )}

            {bundleId && (status === 'passed' || status === 'failed') && (
                <>
                    <button
                        type="button"
                        onClick={() => setShowEvidence((v) => !v)}
                        className="mt-2 text-xs text-blue-600 hover:text-blue-800 hover:underline cursor-pointer flex items-center gap-1"
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

const StatusBadge: React.FC<StatusBadgeProps> = ({ status }) => {
    if (status === 'running') {
        return <span className="px-2 py-0.5 text-xs rounded bg-blue-100 text-blue-700">Running...</span>;
    }
    if (status === 'passed') {
        return <span className="px-2 py-0.5 text-xs rounded bg-green-100 text-green-700">Passed</span>;
    }
    if (status === 'failed') {
        return <span className="px-2 py-0.5 text-xs rounded bg-red-100 text-red-700">Failed</span>;
    }
    return null;
};

export default JourneyVerifyPanel;
