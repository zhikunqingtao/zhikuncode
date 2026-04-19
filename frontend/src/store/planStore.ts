/**
 * planStore — Plan Mode 状态管理
 * SPEC: §F7 Plan Mode
 *
 * 管理计划模式的步骤列表、当前步骤、快照历史等状态。
 * 使用 Zustand + immer + subscribeWithSelector 中间件。
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';

export interface PlanStep {
    id: string;
    title: string;
    description: string;
    status: 'pending' | 'in_progress' | 'completed' | 'failed';
    substeps?: PlanStep[];
    estimatedMinutes?: number;
    files?: string[];
    checked?: boolean;
}

export interface PlanSnapshot {
    id: string;
    planName: string;
    steps: PlanStep[];
    createdAt: number;
}

export interface PlanStoreState {
    isPlanMode: boolean;
    planName: string;
    planOverview: string;
    steps: PlanStep[];
    currentStepId: string | null;
    history: PlanSnapshot[];

    enablePlanMode: (name: string, overview: string) => void;
    disablePlanMode: () => void;
    setSteps: (steps: PlanStep[]) => void;
    updateStepStatus: (stepId: string, status: PlanStep['status']) => void;
    setCurrentStep: (stepId: string | null) => void;
    addStep: (step: PlanStep) => void;
    removeStep: (stepId: string) => void;
    toggleStepChecked: (stepId: string) => void;
    reorderSteps: (fromIndex: number, toIndex: number) => void;
    saveSnapshot: () => void;
    restoreSnapshot: (snapshotId: string) => void;
}

export const usePlanStore = create<PlanStoreState>()(
    subscribeWithSelector(immer((set, get) => ({
        isPlanMode: false,
        planName: '',
        planOverview: '',
        steps: [],
        currentStepId: null,
        history: [],

        enablePlanMode: (name, overview) => set(d => {
            d.isPlanMode = true;
            d.planName = name;
            d.planOverview = overview;
        }),
        disablePlanMode: () => set(d => {
            d.isPlanMode = false;
            d.planName = '';
            d.planOverview = '';
            d.steps = [];
            d.currentStepId = null;
        }),
        setSteps: (steps) => set(d => { d.steps = steps; }),
        updateStepStatus: (id, status) => set(d => {
            const step = d.steps.find(s => s.id === id);
            if (step) step.status = status;
        }),
        setCurrentStep: (id) => set(d => { d.currentStepId = id; }),
        addStep: (step) => set(d => { d.steps.push(step); }),
        removeStep: (id) => set(d => {
            d.steps = d.steps.filter(s => s.id !== id);
        }),
        toggleStepChecked: (id) => set(d => {
            const step = d.steps.find(s => s.id === id);
            if (step) step.checked = !step.checked;
        }),
        reorderSteps: (fromIndex, toIndex) => set(d => {
            const [moved] = d.steps.splice(fromIndex, 1);
            d.steps.splice(toIndex, 0, moved);
        }),
        saveSnapshot: () => set(d => {
            d.history.push({
                id: crypto.randomUUID(),
                planName: d.planName,
                steps: JSON.parse(JSON.stringify(d.steps)),
                createdAt: Date.now(),
            });
        }),
        restoreSnapshot: (snapshotId) => {
            const snapshot = get().history.find(h => h.id === snapshotId);
            if (snapshot) {
                set(d => { d.steps = JSON.parse(JSON.stringify(snapshot.steps)); });
            }
        },
    })))
);
