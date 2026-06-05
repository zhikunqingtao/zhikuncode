/**
 * JourneyVerifyStore — PR-C.6 运行时验证进度状态管理
 *
 * 接收后端通过 STOMP 推送的 `verify_progress` / `verification_result` 消息，
 * 维护单次运行时验证（Runtime Verification）的步骤进度与最终判定。
 */

import { create } from 'zustand';

export interface StepProgress {
    stepIndex: number;
    action: string;
    ok: boolean;
    durationMs: number;
}

export type JourneyVerifyStatus = 'idle' | 'running' | 'passed' | 'failed';

interface JourneyVerifyState {
    status: JourneyVerifyStatus;
    steps: StepProgress[];
    currentStep: number;
    verdict: string | null;
    bundleId: string | null;
    errorMessage: string | null;

    addStepProgress: (step: StepProgress) => void;
    setResult: (verdict: string, bundleId: string, errorMessage: string) => void;
    setRunning: () => void;
    reset: () => void;
}

const initialState = {
    status: 'idle' as JourneyVerifyStatus,
    steps: [] as StepProgress[],
    currentStep: -1,
    verdict: null as string | null,
    bundleId: null as string | null,
    errorMessage: null as string | null,
};

export const useJourneyVerifyStore = create<JourneyVerifyState>((set) => ({
    ...initialState,

    addStepProgress: (step) => set((state) => ({
        status: 'running',
        steps: [...state.steps, step],
        currentStep: step.stepIndex,
    })),

    setResult: (verdict, bundleId, errorMessage) => set({
        status: verdict === 'verified' ? 'passed' : 'failed',
        verdict,
        bundleId,
        errorMessage: errorMessage || null,
    }),

    setRunning: () => set({ status: 'running', steps: [], currentStep: -1, verdict: null, bundleId: null, errorMessage: null }),

    reset: () => set({ ...initialState }),
}));

// 暴露到 window 以便 E2E 测试访问同一实例（与 anomalyStore 保持一致约定）
if (typeof window !== 'undefined') {
    (window as unknown as { __journeyVerifyStore__?: typeof useJourneyVerifyStore }).__journeyVerifyStore__ = useJourneyVerifyStore;
}
