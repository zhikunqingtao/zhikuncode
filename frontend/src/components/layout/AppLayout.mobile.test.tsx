import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import AppLayout from './AppLayout';
import { useAppUiStore } from '@/store/appUiStore';
import { useFeatureFlagStore } from '@/store/featureFlagStore';
const view = vi.hoisted(() => ({ mobile: true, tablet: false }));
vi.mock('@/hooks/useResponsive', () => ({ useResponsive: () => ({ isMobile: view.mobile, isTablet: view.tablet }), useViewportWidth: () => 390 }));
vi.mock('@/hooks/useWebSocket', () => ({ useWebSocket: () => {} }));
vi.mock('./Header', () => ({ Header: ({ onMenuClick, showMenuButton }: { onMenuClick?: () => void; showMenuButton?: boolean }) => showMenuButton ? <button onClick={onMenuClick}>打开会话列表</button> : null }));
vi.mock('./Sidebar', () => ({ Sidebar: () => <div data-testid="desktop-sidebar" />, SidebarTabContent: ({ onSessionActivated, onBack }: { onSessionActivated?: () => void; onBack?: () => void }) => <><button onClick={onBack}>返回列表标题</button><span>会话</span><button onClick={onSessionActivated}>选择会话</button></>, SIDEBAR_TAB_LABELS: { sessions: '会话', files: '文件' } }));
vi.mock('@/components/input/PromptInput/MobileComposerNavigation', () => ({ MobileComposerNavigation: () => null }));
vi.mock('./StatusBar', () => ({ StatusBar: () => <div data-testid="desktop-status" /> }));
vi.mock('@/components/apos/MobileStatusBar', () => ({ MobileStatusBar: () => <div data-testid="mobile-status" /> }));
beforeEach(() => {
    view.mobile = true;
    view.tablet = false;
    useAppUiStore.setState({ mobileNavTab: null });
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

it('移动端选择会话后从列表返回聊天内容', () => {
    useAppUiStore.setState({ mobileNavTab: 'sessions' });
    render(<AppLayout>聊天内容</AppLayout>);
    expect(screen.queryByText('聊天内容')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '选择会话' }));
    expect(screen.getByText('聊天内容')).toBeInTheDocument();
    expect(useAppUiStore.getState().mobileNavTab).toBeNull();
});

it('移动会话面板只保留列表自身的标题和返回入口', () => {
    useAppUiStore.setState({ mobileNavTab: 'sessions' });
    render(<AppLayout>聊天内容</AppLayout>);
    expect(screen.getAllByText('会话', { exact: true })).toHaveLength(1);
    expect(screen.queryByRole('button', { name: '返回' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '返回列表标题' }));
    expect(screen.getByText('聊天内容')).toBeInTheDocument();
});

it('其他移动面板仍保留外层返回栏', () => {
    useAppUiStore.setState({ mobileNavTab: 'files' });
    render(<AppLayout>聊天内容</AppLayout>);
    expect(screen.getByText('文件')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '返回' }));
    expect(screen.getByText('聊天内容')).toBeInTheDocument();
});

it.each(['phone', 'tablet'])('%s 菜单直接进入会话列表，选择后返回聊天', device => {
    view.mobile = device === 'phone';
    view.tablet = device === 'tablet';
    render(<AppLayout>聊天内容</AppLayout>);
    expect(screen.queryByTestId('desktop-sidebar')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '打开会话列表' }));
    expect(useAppUiStore.getState().mobileNavTab).toBe('sessions');
    expect(screen.getByRole('button', { name: '选择会话' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '选择会话' }));
    expect(screen.getByText('聊天内容')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '打开会话列表' }));
    fireEvent.click(screen.getByRole('button', { name: '返回列表标题' }));
    expect(screen.getByText('聊天内容')).toBeInTheDocument();
});

it('桌面保留侧栏，不显示紧凑菜单入口', () => {
    view.mobile = false;
    render(<AppLayout>聊天内容</AppLayout>);
    expect(screen.getByTestId('desktop-sidebar')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '打开会话列表' })).not.toBeInTheDocument();
});
