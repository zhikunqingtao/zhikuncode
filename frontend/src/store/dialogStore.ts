/**
 * DialogStore — 对话框状态管理
 * SPEC: §8.3 前端状态管理
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';

export type DialogType = 
    | 'permission' 
    | 'elicitation' 
    | 'settings' 
    | 'export' 
    | 'resume' 
    | 'theme' 
    | 'keybindings' 
    | null;

export interface DialogStoreState {
    // 状态
    activeDialog: DialogType;
    dialogData: Record<string, unknown>;

    // Actions
    openDialog: (type: DialogType, data?: Record<string, unknown>) => void;
    closeDialog: () => void;
    setDialogData: (data: Record<string, unknown>) => void;
}

export const useDialogStore = create<DialogStoreState>()(
    subscribeWithSelector(
        immer((set) => ({
            activeDialog: null,
            dialogData: {},

            openDialog: (type, data = {}) => set({ 
                activeDialog: type, 
                dialogData: data 
            }),

            closeDialog: () => set({ 
                activeDialog: null, 
                dialogData: {} 
            }),

            setDialogData: (data) => set((state) => {
                state.dialogData = { ...state.dialogData, ...data };
            }),
        }))
    )
);
