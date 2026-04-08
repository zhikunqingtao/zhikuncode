/**
 * ToolStore — 工具注册和结果缓存
 * SPEC: §8.3 前端状态管理
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type { ToolResult } from '@/types';

export interface ToolDefinition {
    name: string;
    description: string;
    inputSchema: Record<string, unknown>;
}

export interface ToolStoreState {
    // 状态
    registeredTools: Map<string, ToolDefinition>;
    toolResults: Map<string, ToolResult>;
    activeToolIds: Set<string>;

    // Actions
    registerTool: (tool: ToolDefinition) => void;
    unregisterTool: (name: string) => void;
    cacheToolResult: (toolUseId: string, result: ToolResult) => void;
    getToolResult: (toolUseId: string) => ToolResult | undefined;
    setToolActive: (toolUseId: string, active: boolean) => void;
    clearToolResults: () => void;
}

export const useToolStore = create<ToolStoreState>()(
    subscribeWithSelector(
        immer((set, get) => ({
            registeredTools: new Map(),
            toolResults: new Map(),
            activeToolIds: new Set(),

            registerTool: (tool) => set((state) => {
                state.registeredTools.set(tool.name, tool);
            }),

            unregisterTool: (name) => set((state) => {
                state.registeredTools.delete(name);
            }),

            cacheToolResult: (toolUseId, result) => set((state) => {
                state.toolResults.set(toolUseId, result);
            }),

            getToolResult: (toolUseId) => {
                return get().toolResults.get(toolUseId);
            },

            setToolActive: (toolUseId, active) => set((state) => {
                if (active) {
                    state.activeToolIds.add(toolUseId);
                } else {
                    state.activeToolIds.delete(toolUseId);
                }
            }),

            clearToolResults: () => set({ toolResults: new Map(), activeToolIds: new Set() }),
        }))
    )
);
