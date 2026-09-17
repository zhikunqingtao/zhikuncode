import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getSessionStatusLabel, Header } from '@/components/layout/Header';
import { ThemeProvider } from '@/components/theme/ThemeProvider';
import { useConfigStore } from '@/store/configStore';
import { useModelStore } from '@/store/modelStore';
import { useSessionStore } from '@/store/sessionStore';
import { useWorkbenchViewStore } from '@/store/workbenchViewStore';
import { useBridgeStore } from '@/store/bridgeStore';

describe('Header', () => {
    beforeEach(() => {
        useWorkbenchViewStore.setState({ enabled: false });
        useConfigStore.setState({
            theme: { ...useConfigStore.getState().theme, mode: 'dark' },
        });
        useSessionStore.setState({ sessionId: null, model: null });
        useBridgeStore.setState({ bridgeStatus: 'connected' });
    });

    afterEach(() => {
        vi.restoreAllMocks();
        vi.unstubAllGlobals();
        document.documentElement.classList.remove('light', 'dark', 'glass', 'system');
    });

    it.each(['system', 'unknown'])('safely renders unexpected runtime theme %s', mode => {
        useModelStore.setState({ loaded: true, loading: false, models: [], defaultModel: null });
        // 模拟未经持久化迁移的旧运行态，验证最后一层渲染防护。
        useConfigStore.setState({ theme: JSON.parse(JSON.stringify({ ...useConfigStore.getState().theme, mode })) });
        document.documentElement.classList.add('system');
        render(<ThemeProvider><Header /></ThemeProvider>);
        expect(screen.getByRole('button', { name: '外观设置' })).toBeVisible();
        expect(document.documentElement.classList.contains('system')).toBe(false);
        expect(document.documentElement.classList.contains('light') || document.documentElement.classList.contains('dark')).toBe(true);
    });

    it('presents an active run as 运行中 with a spinner on both desktop and mobile', () => {
        useModelStore.setState({
            loaded: true,
            loading: false,
            error: null,
            defaultModel: 'kimi-k3',
            models: [{ id: 'kimi-k3', displayName: 'Kimi K3', supportsImages: false, maxImages: 0 }],
        });
        useSessionStore.setState({ model: 'kimi-k3', status: 'streaming' });

        const { container } = render(<Header />);

        // 桌面右簇状态与移动端状态胶囊同时呈现"运行中"（PC/手机/平板全覆盖）
        const statusEls = screen.getAllByRole('status');
        expect(statusEls.length).toBeGreaterThanOrEqual(2);
        for (const el of statusEls) expect(el).toHaveTextContent('运行中');
        // 旋转图标（Loader2 animate-spin）替代原脉冲圆点
        expect(container.querySelectorAll('.animate-spin').length).toBeGreaterThan(0);
    });

    it('shows the session status in the header metrics cluster', () => {
        useModelStore.setState({ loaded: true, loading: false, error: null, models: [], defaultModel: null });
        useSessionStore.setState({ status: 'idle' });

        render(<Header />);

        // idle 时移动端无状态胶囊，role=status 唯一命中桌面右簇的会话状态
        expect(screen.getByRole('status')).toHaveTextContent('就绪');
    });
});

describe('getSessionStatusLabel', () => {
    it('maps known session statuses to Chinese labels', () => {
        expect(getSessionStatusLabel('idle')).toBe('就绪');
        expect(getSessionStatusLabel('streaming')).toBe('运行中');
        expect(getSessionStatusLabel('waiting_permission')).toBe('等待权限');
        expect(getSessionStatusLabel('compacting')).toBe('压缩中...');
    });

    it('falls back to the raw status for unknown values', () => {
        expect(getSessionStatusLabel('some_future_status')).toBe('some_future_status');
    });
});
