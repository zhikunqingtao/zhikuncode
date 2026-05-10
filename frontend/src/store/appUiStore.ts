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

    /**
     * 待激活的可视化 Tab 名（与 Sidebar tabs ID 对齐）。
     * Auto-Routing（v1.5 升级项 C Beta）命中后由 VisualizationMessage 写入；
     * Sidebar 挂载后读取并 setActiveTab，消费后置空。
     */
    pendingVisualizationTab: string | null;

    showElicitationDialog: (data: { requestId: string; question: string; options: unknown }) => void;
    dismissElicitationDialog: () => void;
    setPromptSuggestion: (data: PromptSuggestion | null) => void;
    updateSpeculation: (data: { id: string; accepted: boolean }) => void;
    requestVisualizationTab: (tab: string | null) => void;
}

export const useAppUiStore = create<AppUiStoreState>()(
    subscribeWithSelector(immer((set) => ({
        elicitationDialog: null,
        promptSuggestion: null,
        speculationResults: new Map(),
        pendingVisualizationTab: null,

        showElicitationDialog: (data) => set(d => { d.elicitationDialog = data; }),
        dismissElicitationDialog: () => set(d => { d.elicitationDialog = null; }),
        setPromptSuggestion: (data) => set(d => { d.promptSuggestion = data; }),
        updateSpeculation: (data) => set(d => { d.speculationResults.set(data.id, data.accepted); }),
        requestVisualizationTab: (tab) => set(d => { d.pendingVisualizationTab = tab; }),
    })))
);
