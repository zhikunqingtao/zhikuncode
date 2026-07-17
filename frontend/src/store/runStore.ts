/**
 * RunStore — 运行状态与产物清单管理
 * 支持运行产物的完整性验证和状态跟踪
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';

export interface ArtifactEntry {
    id: string;
    manifestId: string;
    filePath: string;
    operation: 'created' | 'modified' | 'deleted';
    expectedHash: string | null;
    actualHash: string | null;
    fileSize: number | null;
    verified: boolean;
    mismatchDetail: string | null;
    createdAt: string;
}

export interface ArtifactManifest {
    id: string;
    runId: string;
    sessionId: string;
    status: 'pending' | 'verified' | 'partial' | 'failed';
    totalFiles: number;
    verifiedFiles: number;
    failedFiles: number;
    createdAt: string;
    verifiedAt: string | null;
    entries: ArtifactEntry[];
    lastAccessedAt: number;
}

export interface VerificationResult {
    status: 'verified' | 'partial' | 'failed';
    verifiedFiles: number;
    failedFiles: number;
    totalFiles: number;
    failures: Array<{ path: string; reason: string }>;
}

export interface VerificationResultPayload {
    runId: string;
    result: VerificationResult;
}

export interface RunStoreState {
    manifests: Map<string, ArtifactManifest>;
    verificationResults: Map<string, VerificationResult>;
    recoverySnapshots: Map<string, Record<string, unknown>>;
    recoveryEventSeq: Map<string, number>;

    setManifest: (runId: string, manifest: ArtifactManifest) => void;
    handleVerificationResult: (data: VerificationResultPayload) => void;
    clearManifest: (runId: string) => void;
    cleanup: () => void;
    replaceRecoverySnapshot: (runId: string, snapshot: Record<string, unknown>, eventSeq: number) => void;
}

const ONE_HOUR_MS = 60 * 60 * 1000;

export const useRunStore = create<RunStoreState>()(
    subscribeWithSelector(immer((set) => ({
        manifests: new Map<string, ArtifactManifest>(),
        verificationResults: new Map<string, VerificationResult>(),
        recoverySnapshots: new Map<string, Record<string, unknown>>(),
        recoveryEventSeq: new Map<string, number>(),

        replaceRecoverySnapshot: (runId, snapshot, eventSeq) => set(state => {
            state.recoverySnapshots.set(runId, snapshot);
            state.recoveryEventSeq.set(runId, eventSeq);
        }),

        setManifest: (runId, manifest) => set(state => {
            manifest.lastAccessedAt = Date.now();
            state.manifests.set(runId, manifest);
            // Trigger cleanup on each setManifest call
            const now = Date.now();
            for (const [key, entry] of state.manifests.entries()) {
                if (now - entry.lastAccessedAt > ONE_HOUR_MS) {
                    state.manifests.delete(key);
                    state.verificationResults.delete(key);
                    state.recoverySnapshots.delete(key);
                    state.recoveryEventSeq.delete(key);
                }
            }
        }),

        handleVerificationResult: (data) => set(state => {
            state.verificationResults.set(data.runId, data.result);
            // Also update manifest status if present
            const manifest = state.manifests.get(data.runId);
            if (manifest) {
                manifest.status = data.result.status;
                manifest.verifiedFiles = data.result.verifiedFiles;
                manifest.failedFiles = data.result.failedFiles;
                manifest.lastAccessedAt = Date.now();
            }
        }),

        clearManifest: (runId) => set(state => {
            state.manifests.delete(runId);
            state.verificationResults.delete(runId);
            state.recoverySnapshots.delete(runId);
            state.recoveryEventSeq.delete(runId);
        }),

        cleanup: () => set(state => {
            const now = Date.now();
            for (const [key, entry] of state.manifests.entries()) {
                if (now - entry.lastAccessedAt > ONE_HOUR_MS) {
                    state.manifests.delete(key);
                    state.verificationResults.delete(key);
                    state.recoverySnapshots.delete(key);
                    state.recoveryEventSeq.delete(key);
                }
            }
        }),
    })))
);
