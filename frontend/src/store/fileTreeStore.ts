/**
 * FileTreeStore — 文件树状态管理
 * 管理侧边栏文件树的数据加载、展开/折叠、选中、搜索过滤
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';

// ── 类型定义 ──

export interface FileTreeNode {
    name: string;
    path: string;
    type: 'file' | 'dir';
    children?: FileTreeNode[];
    size?: number;
    extension?: string;
}

export interface FileTreeState {
    treeData: FileTreeNode | null;
    loading: boolean;
    error: string | null;
    selectedPath: string | null;
    searchQuery: string;

    // Actions
    fetchTree: (rootPath: string) => Promise<void>;
    setSelected: (path: string | null) => void;
    setSearchQuery: (query: string) => void;
    refresh: (rootPath: string) => Promise<void>;
}

export const useFileTreeStore = create<FileTreeState>()(
    subscribeWithSelector(immer((set, get) => ({
        treeData: null,
        loading: false,
        error: null,
        selectedPath: null,
        searchQuery: '',

        fetchTree: async (rootPath: string) => {
            set(d => { d.loading = true; d.error = null; });
            try {
                const resp = await fetch('/api/files/tree', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ root_path: rootPath }),
                });
                if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
                const json = await resp.json();
                if (!json.success) throw new Error('Failed to load file tree');
                set(d => {
                    d.treeData = json.data as FileTreeNode;
                    d.loading = false;
                });
            } catch (e) {
                set(d => {
                    d.error = e instanceof Error ? e.message : String(e);
                    d.loading = false;
                });
            }
        },

        setSelected: (path) => set(d => { d.selectedPath = path; }),
        setSearchQuery: (query) => set(d => { d.searchQuery = query; }),

        refresh: async (rootPath: string) => {
            await get().fetchTree(rootPath);
        },
    })))
);
