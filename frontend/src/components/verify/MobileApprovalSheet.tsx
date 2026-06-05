/**
 * MobileApprovalSheet — RV-4 移动端审批底部弹层
 *
 * 订阅 useEvidenceStore.attentions，将后端通过 STOMP 推送的 verify_attention
 * 通知聚合为可审批列表。设计风格对齐 JourneyVerifyPanel：
 * - 紧凑卡片：border rounded p-3
 * - verdict 配色：verified=green / failed=red / inconclusive=amber
 *
 * 审批/驳回操作复用既有 STOMP 通道：sendToServer('/app/evidence-decision', ...)
 * 后端 Controller 未就绪时，发送失败仅写日志，不阻塞 UI 关闭通知。
 */

import React, { useState } from 'react';
import { useEvidenceStore } from '@/store/evidenceStore';
import type { VerifyAttention } from '@/store/evidenceStore';
import { sendToServer } from '@/api/stompClient';
import { EvidenceBundleView } from '@/components/verify/EvidenceBundleView';

const APPROVAL_DESTINATION = '/app/evidence-decision';

export const MobileApprovalSheet: React.FC = () => {
    const attentions = useEvidenceStore((s) => s.attentions);
    const dismissAttention = useEvidenceStore((s) => s.dismissAttention);
    const [collapsed, setCollapsed] = useState(false);
    const [expandedBundleId, setExpandedBundleId] = useState<string | null>(null);

    if (!attentions || attentions.length === 0) return null;

    const handleDecide = (attention: VerifyAttention, decision: 'approved' | 'rejected') => {
        try {
            sendToServer(APPROVAL_DESTINATION, {
                bundleId: attention.bundleId,
                sessionId: attention.sessionId,
                decision,
                timestamp: new Date().toISOString(),
            });
        } catch (err) {
            console.warn('[MobileApprovalSheet] send decision failed:', err);
        }
        dismissAttention(attention.bundleId);
    };

    return (
        <div
            className={
                'mobile-approval-sheet fixed bottom-0 left-0 right-0 bg-white shadow-lg ' +
                'rounded-t-xl p-4 max-h-[60vh] overflow-y-auto z-50 border-t'
            }
            role="dialog"
            aria-label="Verification Attention"
        >
            <button
                type="button"
                onClick={() => setCollapsed((v) => !v)}
                aria-label={collapsed ? 'Expand approvals' : 'Collapse approvals'}
                className="block w-full"
            >
                <div className="w-12 h-1 bg-gray-300 rounded-full mx-auto mb-3" />
            </button>

            <div className="flex items-center justify-between mb-3">
                <h3 className="text-sm font-medium">Verification Attention</h3>
                <span className="px-2 py-0.5 text-xs rounded bg-amber-100 text-amber-700">
                    {attentions.length} pending
                </span>
            </div>

            {!collapsed && (
                <div className="space-y-2">
                    {attentions.map((attention) => (
                        <AttentionCard
                            key={attention.bundleId}
                            attention={attention}
                            onApprove={() => handleDecide(attention, 'approved')}
                            onReject={() => handleDecide(attention, 'rejected')}
                            expanded={expandedBundleId === attention.bundleId}
                            onToggleDetail={() => setExpandedBundleId(
                                expandedBundleId === attention.bundleId ? null : attention.bundleId
                            )}
                        />
                    ))}
                </div>
            )}
        </div>
    );
};

interface AttentionCardProps {
    attention: VerifyAttention;
    onApprove: () => void;
    onReject: () => void;
    expanded: boolean;
    onToggleDetail: () => void;
}

const AttentionCard: React.FC<AttentionCardProps> = ({ attention, onApprove, onReject, expanded, onToggleDetail }) => (
    <div className="border rounded-lg p-3">
        <div className="flex items-center justify-between gap-2 mb-1">
            <VerdictBadge verdict={attention.verdict} />
            <span className="text-[10px] text-gray-400">
                {formatRelative(attention.timestamp)}
            </span>
        </div>

        {attention.claim && (
            <div className="text-xs font-medium text-gray-800 truncate">{attention.claim}</div>
        )}

        {attention.summary && (
            <div className="text-xs text-gray-600 mt-1 line-clamp-3">{attention.summary}</div>
        )}

        <div className="text-[10px] text-gray-400 mt-1 font-mono truncate">
            bundle: {attention.bundleId}
        </div>

        <button
            type="button"
            onClick={onToggleDetail}
            className="mt-1.5 text-xs text-blue-600 hover:text-blue-800 hover:underline"
        >
            {expanded ? '收起详情' : '查看详情'}
        </button>
        {expanded && (
            <div className="mt-2 -mx-1">
                <EvidenceBundleView bundleId={attention.bundleId} />
            </div>
        )}

        {attention.requiresApproval && (
            <div className="flex gap-2 mt-2">
                <button
                    type="button"
                    onClick={onApprove}
                    className="flex-1 px-3 py-1.5 text-xs rounded bg-green-600 text-white hover:bg-green-700 active:bg-green-800"
                >
                    Approve
                </button>
                <button
                    type="button"
                    onClick={onReject}
                    className="flex-1 px-3 py-1.5 text-xs rounded bg-red-600 text-white hover:bg-red-700 active:bg-red-800"
                >
                    Reject
                </button>
            </div>
        )}
    </div>
);

const VerdictBadge: React.FC<{ verdict: string }> = ({ verdict }) => {
    const v = (verdict || '').toLowerCase();
    if (v === 'verified' || v === 'passed') {
        return <span className="px-2 py-0.5 text-xs rounded bg-green-100 text-green-700">Verified</span>;
    }
    if (v === 'failed') {
        return <span className="px-2 py-0.5 text-xs rounded bg-red-100 text-red-700">Failed</span>;
    }
    if (v === 'inconclusive') {
        return <span className="px-2 py-0.5 text-xs rounded bg-amber-100 text-amber-700">Inconclusive</span>;
    }
    return <span className="px-2 py-0.5 text-xs rounded bg-blue-100 text-blue-700">{verdict || 'Pending'}</span>;
};

function formatRelative(iso: string): string {
    const t = new Date(iso).getTime();
    if (isNaN(t)) return iso;
    const deltaSec = Math.max(1, Math.floor((Date.now() - t) / 1000));
    if (deltaSec < 60) return `${deltaSec}s ago`;
    if (deltaSec < 3600) return `${Math.floor(deltaSec / 60)}m ago`;
    if (deltaSec < 86400) return `${Math.floor(deltaSec / 3600)}h ago`;
    return new Date(iso).toLocaleString();
}

export default MobileApprovalSheet;
