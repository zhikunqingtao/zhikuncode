/**
 * commandStore — 命令列表存储
 * SPEC: §4.4 命令自动补全
 */

import { create } from 'zustand';
import type { CommandItem } from '@/utils/fuzzyMatch';

export interface CommandStoreState {
    commands: CommandItem[];
    loaded: boolean;
    loadCommands: () => Promise<void>;
}

export const useCommandStore = create<CommandStoreState>((set) => ({
    commands: [],
    loaded: false,
    loadCommands: async () => {
        try {
            const res = await fetch('/api/commands');
            const data: CommandItem[] = await res.json();
            set({ commands: data, loaded: true });
        } catch (err) {
            console.error('Failed to load commands:', err);
        }
    },
}));
