/**
 * McpStore — MCP 工具状态管理 + 健康状态管理 + 资源发现
 * SPEC: §8.3 Store #11
 * 持久化: 否 (从后端 mcp_tool_update / mcp_health_status 加载)
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type { McpTool, McpResource, McpResourceContent, McpPrompt, McpPromptExecuteResult } from '@/types';

/** MCP 服务器健康状态 */
export interface McpHealthState {
    serverName: string;
    status: string;            // CONNECTED | DEGRADED | FAILED | PENDING
    consecutiveFailures: number;
    lastSuccessfulPing: number | null;  // epoch millis
    timestamp: number;         // 事件时间戳
}

export interface McpStoreState {
    mcpTools: Map<string, McpTool[]>;
    /** MCP 服务器健康状态映射 (serverName → McpHealthState) */
    mcpHealthStates: Map<string, McpHealthState>;
    /** MCP 资源列表，按服务器分组 */
    resources: Map<string, McpResource[]>;
    /** 当前选中的资源 */
    selectedResource: McpResource | null;
    /** 当前资源内容 */
    resourceContent: string | null;
    /** 资源加载状态 */
    loadingResources: boolean;

    // ── Prompt 发现状态 ──
    prompts: McpPrompt[];
    selectedPrompt: McpPrompt | null;
    promptResult: McpPromptExecuteResult | null;
    loadingPrompts: boolean;
    executingPrompt: boolean;

    updateMcpTools: (data: { serverId: string; tools: McpTool[] }) => void;
    clearServerTools: (serverId: string) => void;
    /** 更新 MCP 服务器健康状态 */
    updateHealthStatus: (data: McpHealthState) => void;
    /** 从后端获取所有 MCP 资源 */
    fetchResources: (server?: string) => Promise<void>;
    /** 读取指定资源内容 */
    readResource: (uri: string, server: string) => Promise<string | null>;
    /** 重连指定 MCP 服务器 */
    reconnectServer: (server: string) => Promise<boolean>;
    /** 选中资源 */
    selectResource: (resource: McpResource | null) => void;

    // ── Prompt 方法 ──
    fetchPrompts: (server?: string) => Promise<void>;
    executePrompt: (name: string, serverName: string, args: Record<string, string>) => Promise<void>;
    selectPrompt: (prompt: McpPrompt | null) => void;
    clearPromptResult: () => void;
}

export const useMcpStore = create<McpStoreState>()(
    subscribeWithSelector(immer((set, _get) => ({
        mcpTools: new Map(),
        mcpHealthStates: new Map(),
        resources: new Map(),
        selectedResource: null,
        resourceContent: null,
        loadingResources: false,
        prompts: [],
        selectedPrompt: null,
        promptResult: null,
        loadingPrompts: false,
        executingPrompt: false,

        updateMcpTools: (data) => set(d => {
            d.mcpTools.set(data.serverId, data.tools);
        }),
        clearServerTools: (id) => set(d => {
            d.mcpTools.delete(id);
        }),
        updateHealthStatus: (data) => set(d => {
            d.mcpHealthStates.set(data.serverName, data);
        }),
        selectResource: (resource) => set(d => {
            d.selectedResource = resource;
            d.resourceContent = null;
        }),

        fetchResources: async (server?: string) => {
            set(d => { d.loadingResources = true; });
            try {
                const params = server ? `?server=${encodeURIComponent(server)}` : '';
                const resp = await fetch(`/api/mcp/resources${params}`);
                if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
                const data = await resp.json();
                const grouped: Record<string, McpResource[]> = data.resources || {};
                set(d => {
                    for (const [serverName, resList] of Object.entries(grouped)) {
                        d.resources.set(serverName, resList as McpResource[]);
                    }
                });
            } catch (e) {
                console.error('[McpStore] fetchResources failed:', e);
            } finally {
                set(d => { d.loadingResources = false; });
            }
        },

        readResource: async (uri: string, server: string) => {
            try {
                const params = `?uri=${encodeURIComponent(uri)}&server=${encodeURIComponent(server)}`;
                const resp = await fetch(`/api/mcp/resources/read${params}`);
                if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
                const data: McpResourceContent = await resp.json();
                set(d => { d.resourceContent = data.content; });
                return data.content;
            } catch (e) {
                console.error('[McpStore] readResource failed:', e);
                return null;
            }
        },

        reconnectServer: async (server: string) => {
            try {
                const resp = await fetch(
                    `/api/mcp/reconnect?server=${encodeURIComponent(server)}`,
                    { method: 'POST' }
                );
                if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
                const data = await resp.json();
                return data.success === true;
            } catch (e) {
                console.error('[McpStore] reconnectServer failed:', e);
                return false;
            }
        },

        // ── Prompt 方法 ──
        fetchPrompts: async (server?: string) => {
            set(d => { d.loadingPrompts = true; });
            try {
                const params = server ? `?server=${encodeURIComponent(server)}` : '';
                const resp = await fetch(`/api/mcp/prompts${params}`);
                if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
                const data = await resp.json();
                const allPrompts: McpPrompt[] = [];
                if (data.prompts && typeof data.prompts === 'object') {
                    for (const [sName, promptList] of Object.entries(data.prompts)) {
                        for (const p of (promptList as any[])) {
                            allPrompts.push({
                                name: p.name,
                                description: p.description || '',
                                serverName: sName,
                                arguments: (p.arguments || []).map((a: any) => ({
                                    name: a.name,
                                    description: a.description || '',
                                    required: a.required ?? false,
                                })),
                            });
                        }
                    }
                }
                set(d => { d.prompts = allPrompts; });
            } catch (e) {
                console.error('[McpStore] fetchPrompts failed:', e);
            } finally {
                set(d => { d.loadingPrompts = false; });
            }
        },

        executePrompt: async (name: string, serverName: string, args: Record<string, string>) => {
            set(d => { d.executingPrompt = true; d.promptResult = null; });
            try {
                const resp = await fetch('/api/mcp/prompts/execute', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ promptName: name, server: serverName, arguments: args }),
                });
                const data = await resp.json();
                set(d => { d.promptResult = data as McpPromptExecuteResult; });
            } catch (e) {
                set(d => {
                    d.promptResult = {
                        success: false,
                        serverName,
                        promptName: name,
                        error: e instanceof Error ? e.message : 'Unknown error',
                    };
                });
            } finally {
                set(d => { d.executingPrompt = false; });
            }
        },

        selectPrompt: (prompt) => set(d => {
            d.selectedPrompt = prompt;
            d.promptResult = null;
        }),

        clearPromptResult: () => set(d => { d.promptResult = null; }),
    })))
);
