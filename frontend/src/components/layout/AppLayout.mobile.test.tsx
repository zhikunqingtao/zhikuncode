import { render, screen } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import AppLayout from './AppLayout';
import { useFeatureFlagStore } from '@/store/featureFlagStore';
const view = vi.hoisted(() => ({ mobile: true }));
vi.mock('@/hooks/useResponsive', () => ({ useResponsive: () => ({ isMobile: view.mobile }), useViewportWidth: () => 390 }));
vi.mock('@/hooks/useWebSocket', () => ({ useWebSocket: () => {} }));
vi.mock('./Header', () => ({ Header: () => null }));
vi.mock('./Sidebar', () => ({ Sidebar: () => null, SidebarTabContent: () => null, SIDEBAR_TAB_LABELS: {} }));
vi.mock('./Drawer', () => ({ Drawer: () => null }));
vi.mock('./StatusBar', () => ({ StatusBar: () => <div data-testid="desktop-status" /> }));
vi.mock('@/components/apos/MobileStatusBar', () => ({ MobileStatusBar: () => <div data-testid="mobile-status" /> }));
beforeEach(() => {
    view.mobile = true;
    useFeatureFlagStore.setState(s => ({ flags: { ...s.flags, APOS_ACTIVITY_STREAM: true, APOS_MOBILE_STATUS: true } }));
});
it('移动状态栏启用时不再叠加桌面状态栏', () => {
    render(<AppLayout>content</AppLayout>);
    expect(screen.getByTestId('mobile-status')).toBeInTheDocument();
    expect(screen.queryByTestId('desktop-status')).not.toBeInTheDocument();
});
it('移动状态栏关闭时不显示桌面费用栏', () => {
    useFeatureFlagStore.setState(s => ({ flags: { ...s.flags, APOS_MOBILE_STATUS: false } }));
    render(<AppLayout>content</AppLayout>);
    expect(screen.queryByTestId('desktop-status')).not.toBeInTheDocument();
    expect(screen.queryByTestId('mobile-status')).not.toBeInTheDocument();
});
it('桌面状态栏不受影响', () => {
    view.mobile = false;
    render(<AppLayout>content</AppLayout>);
    expect(screen.getByTestId('desktop-status')).toBeInTheDocument();
    expect(screen.queryByTestId('mobile-status')).not.toBeInTheDocument();
});
