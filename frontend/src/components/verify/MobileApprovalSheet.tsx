/**
 * MobileApprovalSheet — RV-4 移动端审批底部弹层（§8.4 sheet 形态）
 *
 * 订阅 useEvidenceStore.attentions，将后端通过 STOMP 推送的 verify_attention
 * 通知聚合为可审批列表。迁移为 §8.4 Bottom Sheet 形态：
 * - 基于 SheetShell（grabber 36×4 + 顶部 rounded-panel + overlay2 遮罩 + 拖拽/Esc 关闭）
 * - 收起态保留为底部细条（grabber + 标题 + pending 计数），点击重新展开
 * - 审批按钮触控 ≥44px（§8.6）
 *
 * 审批/驳回操作复用既有 STOMP 通道：sendToServer('/app/evidence-decision', ...)
 * 后端 Controller 未就绪时，发送失败仅写日志，不阻塞 UI 关闭通知。
 */

import React, { useState } from 'react';
import { useEvidenceStore } from '@/store/evidenceStore';
import type { VerifyAttention } from '@/store/evidenceStore';
import { sendToServer } from '@/api/stompClient';
import { EvidenceBundleView } from '@/components/verify/EvidenceBundleView';
import { SheetShell } from '@/components/apos/MobileBottomSheet';

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

    const pendingBadge = (
        <span className="px-2 py-0.5 text-[13px] rounded bg-warnsoft text-warnstrong">
            {attentions.length} pending
        </span>
    );

    return (
        <>
            {/* 收起态细条 — 点击重新展开 sheet */}
            {collapsed && (
                <button
                    type="button"
                    onClick={() => setCollapsed(false)}
                    aria-label="Expand approvals"
                    className="panel-control mobile-approval-sheet fixed bottom-0 left-0 right-0 z-50 block w-full
                        bg-surfacev2 border-t border-hairline shadow-e3 rounded-t-panel px-4 pt-2
                        pb-[max(env(safe-area-inset-bottom),8px)]"
                >
                    <div className="h-1 w-9 rounded-full bg-[color:color-mix(in_srgb,var(--v2-text-3)_40%,transparent)] mx-auto mb-2" aria-hidden="true" />
                    <div className="flex items-center justify-between">
                        <span className="text-sm font-medium text-t1">Verification Attention</span>
                        {pendingBadge}
                    </div>
                </button>
            )}

            {/* §8.4 sheet 形态审批层 */}
            <SheetShell
                isOpen={!collapsed}
                onClose={() => setCollapsed(true)}
                ariaLabel="Verification Attention"
                header={
                    <div className="flex items-center justify-between px-4 pb-3 border-b border-hairline">
                        <h3 className="text-t1 text-base font-semibold">Verification Attention</h3>
                        {pendingBadge}
                    </div>
                }
            >
                <div className="space-y-2 px-4 py-3">
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
            </SheetShell>
        </>
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
    <div className="border border-hairline rounded-[14px] p-3">
        <div className="flex items-center justify-between gap-2 mb-1">
            <VerdictBadge verdict={attention.verdict} />
            <span className="text-[13px] text-t3">
                {formatRelative(attention.timestamp)}
            </span>
        </div>

        {attention.claim && (
            <div className="text-[13px] font-medium text-t1 truncate">{attention.claim}</div>
        )}

        {attention.summary && (
            <div className="text-[13px] text-t2 mt-1 line-clamp-3">{attention.summary}</div>
        )}

        <div className="text-[13px] text-t3 mt-1 font-mono truncate">
            bundle: {attention.bundleId}
        </div>

        <button
            type="button"
            onClick={onToggleDetail}
            className="panel-control mt-1.5 inline-flex items-center min-h-[44px] text-[13px] text-accent2-ink hover:underline"
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
                    className="panel-control flex-1 px-3 min-h-[44px] text-[13px] rounded-xl bg-okstrong text-white dark:text-app2 hover:brightness-110 active:scale-[.97] transition-interactive duration-fast"
                >
                    Approve
                </button>
                <button
                    type="button"
                    onClick={onReject}
                    className="panel-control flex-1 px-3 min-h-[44px] text-[13px] rounded-xl bg-err text-white active:scale-[.97] transition-interactive duration-fast"
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
        return <span className="px-2 py-0.5 text-[13px] rounded bg-oksoft text-okstrong">Verified</span>;
    }
    if (v === 'failed') {
        return <span className="px-2 py-0.5 text-[13px] rounded bg-errsoft text-errstrong">Failed</span>;
    }
    if (v === 'inconclusive') {
        return <span className="px-2 py-0.5 text-[13px] rounded bg-warnsoft text-warnstrong">Inconclusive</span>;
    }
    return <span className="px-2 py-0.5 text-[13px] rounded bg-accent2-soft text-accent2-ink">{verdict || 'Pending'}</span>;
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
