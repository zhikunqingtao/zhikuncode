/**
 * AppUiStore — 应用全局 UI 状态管理
 * SPEC: §8.3 Store #7
 * 持久化: 否
 * 管理: elicitation / prompt_suggestion / speculation 等杂项状态
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type { ElicitationRequest, PromptSuggestion } from '@/types';

export interface AppUiStoreState {
    elicitationDialog: ElicitationRequest | null;
    promptSuggestion: PromptSuggestion | null;
    speculationResults: Map<string, boolean>;

    showElicitationDialog: (data: { requestId: string; question: string; options: unknown }) => void;
    dismissElicitationDialog: () => void;
    setPromptSuggestion: (data: PromptSuggestion | null) => void;
    updateSpeculation: (data: { id: string; accepted: boolean }) => void;
}

export const useAppUiStore = create<AppUiStoreState>()(
    subscribeWithSelector(immer((set) => ({
        elicitationDialog: null,
        promptSuggestion: null,
        speculationResults: new Map(),

        showElicitationDialog: (data) => set(d => { d.elicitationDialog = data; }),
        dismissElicitationDialog: () => set(d => { d.elicitationDialog = null; }),
        setPromptSuggestion: (data) => set(d => { d.promptSuggestion = data; }),
        updateSpeculation: (data) => set(d => { d.speculationResults.set(data.id, data.accepted); }),
    })))
);
