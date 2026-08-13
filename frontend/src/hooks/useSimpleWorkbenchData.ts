import { useEffect, useRef, useState } from 'react';
import type { ActivityData } from '@/types/apos';

export interface SessionDetail {
    sessionId: string;
    model: string;
    workingDir: string;
    title: string | null;
    status: string;
    summary: string | null;
    createdAt: string;
    updatedAt: string;
}

export interface RunSummary {
    id: string;
    sessionId: string;
    parentRunId: string | null;
    status: string;
    agentType: string;
    startedAt: string | null;
    finishedAt: string | null;
    updatedAt: string;
    verificationStatus: string;
    errorSummary: string | null;
}

export interface ArtifactEntrySummary {
    id: string;
    filePath: string;
    operation: 'created' | 'modified' | 'deleted';
    state: string;
    fileSize: number | null;
    verified: boolean;
    mismatchDetail: string | null;
}

export interface ArtifactManifestSummary {
    id: string;
    runId: string;
    sessionId: string;
    workspaceRoot: string;
    status: string;
    createdAt: string;
    updatedAt: string;
    totalFiles: number;
    verifiedFiles: number;
    failedFiles: number;
    entries: ArtifactEntrySummary[];
}

export interface WorkbenchMessage {
    messageId: string;
    text: string;
    timestamp: string;
}

export interface StructuredSummary {
    conclusion: string | null;
    completed: string[];
    issues: string[];
    nextSteps: string[];
}

export type CriterionStatus = 'PASSED' | 'FAILED' | 'PARTIAL' | 'NOT_VERIFIED';
export interface WorkbenchCriterion {
    id: string | null;
    type: 'business' | 'technical';
    text: string;
    status: CriterionStatus;
    detail: string | null;
    evidenceBundleId: string | null;
}

export interface DeliveryView {
    manifests: ArtifactManifestSummary[];
    files: DeliveryFileView[];
    totalFiles: number;
    primaryArtifactPath: string | null;
}

export interface DeliveryFileView extends ArtifactEntrySummary {
    manifestId: string;
    workspaceRoot: string;
    relativePath: string;
    primary: boolean;
}

export interface WorkbenchPendingAction {
    interactionId: string;
    runId: string;
    interactionType: 'permission' | 'elicitation' | 'plan_approval';
    prompt: Record<string, unknown>;
    createdAt: string;
}

export interface WorkbenchActivity extends ActivityData {
    sessionId: string;
    runId: string;
}

export interface CurrentWorkbenchView {
    correlationMode: 'EXACT' | 'LEGACY_FALLBACK';
    requestMessageId: string | null;
    resultMessageId: string | null;
    rootRun: RunSummary | null;
    request: WorkbenchMessage | null;
    result: WorkbenchMessage | null;
    structuredSummary: StructuredSummary;
    delivery: DeliveryView;
    verification: {
        businessCriteria: WorkbenchCriterion[];
        technicalChecks: WorkbenchCriterion[];
        evidence: unknown[];
        overallStatus: CriterionStatus;
    };
    pendingActionCount: number;
    pendingActions: WorkbenchPendingAction[];
    activities: WorkbenchActivity[];
    previousAvailableDelivery: {
        rootRunId: string;
        finishedAt: string | null;
        result: WorkbenchMessage | null;
        delivery: DeliveryView;
    } | null;
    currentFailure: { status: string; reason: string } | null;
}

interface SectionState<T> { loading: boolean; error: string | null; data: T; }
export interface SimpleWorkbenchData {
    session: SectionState<SessionDetail | null>;
    current: SectionState<CurrentWorkbenchView | null>;
}

const emptyState = (): SimpleWorkbenchData => ({
    session: { loading: false, error: null, data: null },
    current: { loading: false, error: null, data: null },
});

async function readJson<T>(url: string, sessionId: string, signal: AbortSignal): Promise<T> {
    const response = await fetch(url, { headers: { 'X-Session-Id': sessionId }, signal });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response.json() as Promise<T>;
}

export function useSimpleWorkbenchData(sessionId: string | null): SimpleWorkbenchData {
    const [state, setState] = useState<SimpleWorkbenchData>(emptyState);
    const generationRef = useRef(0);

    useEffect(() => {
        const generation = ++generationRef.current;
        const controller = new AbortController();
        if (!sessionId) {
            setState(emptyState());
            return () => controller.abort();
        }
        const isCurrent = () => generationRef.current === generation && !controller.signal.aborted;
        setState({
            session: { loading: true, error: null, data: null },
            current: { loading: true, error: null, data: null },
        });

        void readJson<SessionDetail>(`/api/sessions/${encodeURIComponent(sessionId)}`, sessionId, controller.signal)
            .then(data => isCurrent() && setState(previous => ({ ...previous, session: { loading: false, error: null, data } })))
            .catch(error => isCurrent() && setState(previous => ({ ...previous, session: { loading: false, error: error instanceof Error ? error.message : String(error), data: null } })));

        const fetchCurrent = () => readJson<CurrentWorkbenchView>(
            `/api/sessions/${encodeURIComponent(sessionId)}/workbench/current`, sessionId, controller.signal,
        ).then(data => {
            if (!isCurrent()) return;
            setState(previous => ({ ...previous, current: { loading: false, error: null, data } }));
        }).catch(error => {
            if (!isCurrent()) return;
            setState(previous => ({ ...previous, current: { loading: false, error: error instanceof Error ? error.message : String(error), data: null } }));
        });
        void fetchCurrent();
        const poll = window.setInterval(() => { void fetchCurrent(); }, 5000);
        return () => { controller.abort(); window.clearInterval(poll); };
    }, [sessionId]);

    return state;
}
