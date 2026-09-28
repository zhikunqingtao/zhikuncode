import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { CurrentWorkbenchView, RunSummary } from '@/hooks/useSimpleWorkbenchData';
import { useCostStore } from '@/store/costStore';
import { SimpleWorkbench } from './SimpleWorkbench';

vi.mock('@/api/dispatch', () => ({ recoverPendingInteractions: vi.fn() }));

function currentView(usage: Partial<RunSummary>): CurrentWorkbenchView {
    return {
        correlationMode: 'EXACT', requestMessageId: 'request-1', resultMessageId: null,
        rootRun: {
            id: 'run-1', sessionId: 'session-1', parentRunId: null, status: 'COMPLETED', agentType: 'query',
            startedAt: null, finishedAt: null, updatedAt: '2026-09-28T00:00:00Z',
            verificationStatus: 'NOT_REQUESTED', errorSummary: null, ...usage,
        },
        request: { messageId: 'request-1', text: '生成报告', timestamp: '2026-09-28T00:00:00Z' },
        result: null,
        structuredSummary: { conclusion: null, completed: [], issues: [], nextSteps: [] },
        delivery: { manifests: [], files: [], totalFiles: 0, primaryArtifactPath: null },
        verification: { businessCriteria: [], technicalChecks: [], evidence: [], overallStatus: 'NOT_VERIFIED' },
        pendingActionCount: 0, pendingActions: [], activities: [], previousAvailableDelivery: null, currentFailure: null,
    };
}

describe('SimpleWorkbench Run usage', () => {
    afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

    it.each([
        ['known zero', { usageStatus: 'known', totalTokens: 0 }, '0 Tokens'],
        ['known usage', { usageStatus: 'known', totalTokens: 42 }, '42 Tokens'],
        ['partial usage', { usageStatus: 'partial', totalTokens: 42 }, '42 Tokens（仅已报告部分）'],
        ['partial zero', { usageStatus: 'partial', totalTokens: 0 }, '0 Tokens（仅已报告部分）'],
        ['unknown usage', { usageStatus: 'unknown', totalTokens: 0 }, '未报告'],
        ['legacy snapshot', {}, '未报告'],
        ['missing status', { totalTokens: 42 }, '未报告'],
        ['future status', { usageStatus: 'future_status', totalTokens: 42 }, '未报告'],
        ['missing total', { usageStatus: 'known' }, '未报告'],
    ] satisfies [string, Partial<RunSummary>, string][])('renders %s from the current Run projection', async (_name, usage, expected) => {
        const costStateBefore = useCostStore.getState();
        const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
            const url = String(input);
            if (url === '/api/sessions/session-1/workbench/current') {
                return { ok: true, json: async () => currentView(usage) };
            }
            if (url === '/api/sessions/session-1') {
                return { ok: true, json: async () => ({
                    sessionId: 'session-1', model: 'test', workingDir: '/workspace', title: '报告', status: 'idle',
                    summary: null, createdAt: '2026-09-28T00:00:00Z', updatedAt: '2026-09-28T00:00:00Z',
                }) };
            }
            throw new Error(`Unexpected request: ${url}`);
        });
        vi.stubGlobal('fetch', fetchMock);

        render(<SimpleWorkbench sessionId="session-1" messages={[]} status="idle" />);

        expect(await screen.findByText(`本轮已观测用量：${expected}`)).toBeInTheDocument();
        expect(fetchMock).toHaveBeenCalledTimes(2);
        expect(useCostStore.getState()).toBe(costStateBefore);
    });
});
