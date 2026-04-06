/**
 * McpStore — MCP 工具状态管理
 * SPEC: §8.3 Store #11
 * 持久化: 否 (从后端 mcp_tool_update 加载)
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type { McpTool } from '@/types';

export interface McpStoreState {
    mcpTools: Map<string, McpTool[]>;

    updateMcpTools: (data: { serverId: string; tools: McpTool[] }) => void;
    clearServerTools: (serverId: string) => void;
}

export const useMcpStore = create<McpStoreState>()(
    subscribeWithSelector(immer((set) => ({
        mcpTools: new Map(),

        updateMcpTools: (data) => set(d => {
            d.mcpTools.set(data.serverId, data.tools);
        }),
        clearServerTools: (id) => set(d => {
            d.mcpTools.delete(id);
        }),
    })))
);
