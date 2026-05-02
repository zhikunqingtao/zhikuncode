/**
 * CodeInsightStore — Git 数据管理
 * 管理 Git Log / Diff / Blame 数据状态
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';

// ── 类型定义（基于 Python API 响应） ──

export interface GitCommitFile {
    path: string;
    additions: number;
    deletions: number;
    status: string;
}

export interface GitCommit {
    sha: string;
    message: string;
    author: string;
    date: string;
    files: string[];
}

export interface GitDiff {
    summary: string;
    detailed: string;
    files_changed: number;
}

export interface GitBlameLine {
    line_no: number;
    sha: string;
    author: string;
    date: string;
    content: string;
}

export interface GitBlame {
    file_path: string;
    lines: GitBlameLine[];
    total_lines: number;
}

interface GitApiResponse<T = unknown> {
    success: boolean;
    data: T | null;
    error_code: string | null;
    error_message: string | null;
}

// ── Store 状态 ──

export interface CodeInsightState {
    // Git Log
    gitCommits: GitCommit[];
    gitLoading: boolean;
    gitError: string | null;
    gitTotal: number;

    // Git Diff
    activeDiff: GitDiff | null;
    diffLoading: boolean;

    // Git Blame
    activeBlame: GitBlame | null;
    blameLoading: boolean;

    // Actions
    fetchGitLog: (repoPath: string, maxCount?: number, branch?: string) => Promise<void>;
    fetchMoreGitLog: (repoPath: string, maxCount?: number, branch?: string) => Promise<void>;
    fetchGitDiff: (repoPath: string, ref1: string, ref2: string) => Promise<void>;
    fetchGitBlame: (repoPath: string, filePath: string, ref?: string) => Promise<void>;
    clearDiff: () => void;
    clearBlame: () => void;
    clearAll: () => void;
}

async function gitApiPost<T>(endpoint: string, body: Record<string, unknown>): Promise<T> {
    const resp = await fetch(`/api/git/${endpoint}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const json: GitApiResponse<T> = await resp.json();
    if (!json.success) throw new Error(json.error_message ?? 'Unknown error');
    return json.data as T;
}

export const useCodeInsightStore = create<CodeInsightState>()(
    subscribeWithSelector(immer((set, get) => ({
        gitCommits: [],
        gitLoading: false,
        gitError: null,
        gitTotal: 0,

        activeDiff: null,
        diffLoading: false,

        activeBlame: null,
        blameLoading: false,

        fetchGitLog: async (repoPath, maxCount = 20, branch) => {
            set(d => { d.gitLoading = true; d.gitError = null; });
            try {
                const data = await gitApiPost<{ commits: GitCommit[]; total: number }>('log', {
                    repo_path: repoPath,
                    max_count: maxCount,
                    ...(branch ? { branch } : {}),
                });
                set(d => {
                    d.gitCommits = data.commits;
                    d.gitTotal = data.total;
                    d.gitLoading = false;
                });
            } catch (e) {
                set(d => {
                    d.gitError = e instanceof Error ? e.message : String(e);
                    d.gitLoading = false;
                });
            }
        },

        fetchMoreGitLog: async (repoPath, maxCount = 20, branch) => {
            const currentCount = get().gitCommits.length;
            set(d => { d.gitLoading = true; });
            try {
                const data = await gitApiPost<{ commits: GitCommit[]; total: number }>('log', {
                    repo_path: repoPath,
                    max_count: currentCount + maxCount,
                    ...(branch ? { branch } : {}),
                });
                set(d => {
                    d.gitCommits = data.commits;
                    d.gitTotal = data.total;
                    d.gitLoading = false;
                });
            } catch (e) {
                set(d => {
                    d.gitError = e instanceof Error ? e.message : String(e);
                    d.gitLoading = false;
                });
            }
        },

        fetchGitDiff: async (repoPath, ref1, ref2) => {
            set(d => { d.diffLoading = true; });
            try {
                const data = await gitApiPost<GitDiff>('diff', {
                    repo_path: repoPath,
                    ref1,
                    ref2,
                });
                set(d => { d.activeDiff = data; d.diffLoading = false; });
            } catch (e) {
                console.error('[CodeInsight] fetchGitDiff failed:', e);
                set(d => { d.diffLoading = false; });
            }
        },

        fetchGitBlame: async (repoPath, filePath, ref) => {
            set(d => { d.blameLoading = true; });
            try {
                const data = await gitApiPost<GitBlame>('blame', {
                    repo_path: repoPath,
                    file_path: filePath,
                    ...(ref ? { ref } : {}),
                });
                set(d => { d.activeBlame = data; d.blameLoading = false; });
            } catch (e) {
                console.error('[CodeInsight] fetchGitBlame failed:', e);
                set(d => { d.blameLoading = false; });
            }
        },

        clearDiff: () => set(d => { d.activeDiff = null; }),
        clearBlame: () => set(d => { d.activeBlame = null; }),
        clearAll: () => set(d => {
            d.gitCommits = [];
            d.gitLoading = false;
            d.gitError = null;
            d.gitTotal = 0;
            d.activeDiff = null;
            d.diffLoading = false;
            d.activeBlame = null;
            d.blameLoading = false;
        }),
    })))
);
