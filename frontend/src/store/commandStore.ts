/**
 * CommandStore — Slash 命令管理
 * SPEC: §8.3 前端状态管理
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type { Command } from '@/types';

export interface CommandStoreState {
    // 状态
    commands: Command[];
    history: string[];
    historyIndex: number;

    // Actions
    setCommands: (commands: Command[]) => void;
    addToHistory: (command: string) => void;
    navigateHistory: (direction: 'up' | 'down') => string | null;
    clearHistory: () => void;
}

export const useCommandStore = create<CommandStoreState>()(
    subscribeWithSelector(
        immer((set, get) => ({
            commands: [],
            history: [],
            historyIndex: -1,

            setCommands: (commands) => set({ commands }),

            addToHistory: (command) => set((state) => {
                if (command.trim() && !state.history.includes(command)) {
                    state.history.unshift(command);
                    if (state.history.length > 50) {
                        state.history.pop();
                    }
                }
                state.historyIndex = -1;
            }),

            navigateHistory: (direction) => {
                const { history, historyIndex } = get();
                if (history.length === 0) return null;

                let newIndex = historyIndex;
                if (direction === 'up') {
                    newIndex = historyIndex < history.length - 1 ? historyIndex + 1 : historyIndex;
                } else {
                    newIndex = historyIndex > 0 ? historyIndex - 1 : -1;
                }

                set({ historyIndex: newIndex });
                return newIndex >= 0 ? history[newIndex] : null;
            },

            clearHistory: () => set({ history: [], historyIndex: -1 }),
        }))
    )
);
