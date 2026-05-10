/**
 * BrowserReplayTimeline 单元测试 — 对应 Task3-5 方案 §11.11 资产 #9。
 *
 * MVP 3 用例（骨架：fetch mock + 基本渲染 + 错误处理），预备周补到 8 用例。
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import BrowserReplayTimeline from '@/components/browser/BrowserReplayTimeline';

const mockSnap = {
    snapshotId: 'sid-1',
    sessionId: 'sess-1',
    capturedAt: new Date().toISOString(),
    url: 'https://example.com',
    title: 'Example',
    selector: null,
    nodeCount: 5,
    interactive: [{ role: 'button', name: 'OK' }],
    tree: null,
    screenshotBase64: null,
};

describe('BrowserReplayTimeline', () => {
    beforeEach(() => {
        global.fetch = vi.fn(() =>
            Promise.resolve({
                ok: true,
                status: 200,
                json: () => Promise.resolve([mockSnap]),
            } as unknown as Response)
        );
    });

    afterEach(() => {
        vi.restoreAllMocks();
    });

    it('BRT-01 open=true 时拉取 /api/browser/replay/{sessionId}', async () => {
        render(
            <BrowserReplayTimeline
                open={true}
                onClose={() => {}}
                sessionId="sess-1"
            />
        );

        await waitFor(() => {
            expect(global.fetch).toHaveBeenCalledWith(
                expect.stringContaining('/api/browser/replay/sess-1')
            );
        });
    });

    it('BRT-02 渲染时间线项目（URL / nodeCount 展示）', async () => {
        render(
            <BrowserReplayTimeline
                open={true}
                onClose={() => {}}
                sessionId="sess-1"
            />
        );

        await waitFor(() => {
            expect(screen.getByText(/example\.com/i)).toBeInTheDocument();
        });
    });

    it('BRT-03 fetch 非 200 响应时展示错误消息', async () => {
        global.fetch = vi.fn(() =>
            Promise.resolve({
                ok: false,
                status: 500,
                json: () => Promise.resolve({}),
            } as unknown as Response)
        );

        render(
            <BrowserReplayTimeline
                open={true}
                onClose={() => {}}
                sessionId="sess-1"
            />
        );

        await waitFor(() => {
            expect(screen.getByText(/HTTP 500/i)).toBeInTheDocument();
        });
    });

    // 预备周补 5 条
    it.skip('BRT-04 点击 refresh 按钮重新拉取时间线', async () => {});
    it.skip('BRT-05 点击 clear 按钮发送 DELETE 并清空本地状态', async () => {});
    it.skip('BRT-06 选中某帧后展开 interactive 表', async () => {});
    it.skip('BRT-07 空时间线展示 empty state 提示', async () => {});
    it.skip('BRT-08 screenshotBase64 存在时渲染缩略图', async () => {});
});
