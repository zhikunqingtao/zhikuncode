/**
 * EvidenceStore — RV-4 证据包可视化状态管理
 *
 * 维护：
 * 1. 当前查看的证据包详情（fetchBundle 拉取）
 * 2. 会话证据包列表（fetchSessionBundles 拉取）
 * 3. 后端通过 STOMP 推送的 verify_attention 待审批通知列表
 *
 * 与 journeyVerifyStore 保持一致：使用 Zustand create()，并暴露到 window 供 E2E 访问。
 */

import { create } from 'zustand';

export interface EvidenceItem {
    id: string;
    /** "command" | "screenshot" | "video" | "har" | "console" | "test" | "diff" | ... */
    type: string;
    summary: string | null;
    blobSha256: string | null;
    meta: Record<string, any>;
}

export interface EvidenceBundle {
    bundleId: string;
    sessionId: string;
    agentId: string | null;
    /** "journey" | "qa" | "visual" | "repro" | ... */
    kind: string;
    claim: string | null;
    /** "verified" | "failed" | "inconclusive" */
    verdict: string;
    items: EvidenceItem[];
    /** ISO-8601 */
    createdAt: string;
}

export interface VerifyAttention {
    /** 固定为 "verify_attention" */
    type: string;
    sessionId: string;
    bundleId: string;
    verdict: string;
    claim: string;
    summary: string;
    requiresApproval: boolean;
    /** ISO-8601 或客户端接收时间 */
    timestamp: string;
}

interface EvidenceState {
    currentBundle: EvidenceBundle | null;
    sessionBundles: EvidenceBundle[];
    loading: boolean;
    error: string | null;
    attentions: VerifyAttention[];

    fetchBundle: (bundleId: string) => Promise<void>;
    fetchSessionBundles: (sessionId: string) => Promise<void>;
    addAttention: (attention: VerifyAttention) => void;
    dismissAttention: (bundleId: string) => void;
    clearCurrentBundle: () => void;
}

const initialState = {
    currentBundle: null as EvidenceBundle | null,
    sessionBundles: [] as EvidenceBundle[],
    loading: false,
    error: null as string | null,
    attentions: [] as VerifyAttention[],
};

export const useEvidenceStore = create<EvidenceState>((set, get) => ({
    ...initialState,

    fetchBundle: async (bundleId: string) => {
        if (!bundleId) return;
        set({ loading: true, error: null });
        try {
            const resp = await fetch(`/api/evidence/${encodeURIComponent(bundleId)}`);
            if (!resp.ok) {
                throw new Error(`HTTP ${resp.status}`);
            }
            const bundle = (await resp.json()) as EvidenceBundle;
            set({ currentBundle: bundle, loading: false });
        } catch (err) {
            console.error('[Evidence] fetchBundle failed:', err);
            set({
                loading: false,
                error: err instanceof Error ? err.message : 'Failed to load evidence bundle',
            });
        }
    },

    fetchSessionBundles: async (sessionId: string) => {
        if (!sessionId) return;
        set({ loading: true, error: null });
        try {
            const resp = await fetch(`/api/evidence/session/${encodeURIComponent(sessionId)}`);
            if (!resp.ok) {
                throw new Error(`HTTP ${resp.status}`);
            }
            const bundles = (await resp.json()) as EvidenceBundle[];
            set({ sessionBundles: Array.isArray(bundles) ? bundles : [], loading: false });
        } catch (err) {
            console.error('[Evidence] fetchSessionBundles failed:', err);
            set({
                loading: false,
                error: err instanceof Error ? err.message : 'Failed to load session bundles',
            });
        }
    },

    addAttention: (attention: VerifyAttention) => {
        if (!attention || !attention.bundleId) return;
        // 去重：相同 bundleId 视为同一通知，刷新到列表头部
        const existing = get().attentions.filter((a) => a.bundleId !== attention.bundleId);
        set({ attentions: [attention, ...existing] });
    },

    dismissAttention: (bundleId: string) =>
        set((state) => ({
            attentions: state.attentions.filter((a) => a.bundleId !== bundleId),
        })),

    clearCurrentBundle: () => set({ currentBundle: null, error: null }),
}));

// 暴露到 window 以便 E2E 测试访问同一实例（与 journeyVerifyStore / anomalyStore 一致）
if (typeof window !== 'undefined') {
    (window as unknown as { __evidenceStore__?: typeof useEvidenceStore }).__evidenceStore__ =
        useEvidenceStore;
}
