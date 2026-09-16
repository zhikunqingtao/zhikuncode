/**
 * AppLayout — 应用主布局组件
 * SPEC: §8.6
 *
 * 三栏布局: Sidebar (左) | Main Content (中) | StatusBar (底)
 * 响应式: 手机和平板菜单直接进入会话列表，桌面保留 Sidebar
 */

import { useState, useCallback, useMemo } from 'react';
import { ChevronLeft } from 'lucide-react';
import { MobileComposerNavigation } from '@/components/input/PromptInput/MobileComposerNavigation';
import { Header } from './Header';
import { Sidebar, SidebarTabContent, SIDEBAR_TAB_LABELS, type TabType } from './Sidebar';
import { StatusBar } from './StatusBar';
import { useWebSocket } from '@/hooks/useWebSocket';
import { useResponsive, useViewportWidth } from '@/hooks/useResponsive';
import { useFeatureFlagStore } from '@/store/featureFlagStore';
import { useAppUiStore } from '@/store/appUiStore';
import { MobileStatusBar } from '@/components/apos/MobileStatusBar';

interface AppLayoutProps {
    children: React.ReactNode;
}

export function AppLayout({ children }: AppLayoutProps) {
    // 手机和平板共用会话列表入口，≥1024px 保留桌面侧栏。
    const { isMobile, isTablet } = useResponsive();
    const compactNavigation = isMobile || isTablet;

    const aposEnabled = useFeatureFlagStore((s) => s.flags.APOS_ACTIVITY_STREAM);
    const mobileStatusEnabled = useFeatureFlagStore((s) => s.flags.APOS_MOBILE_STATUS);

    // 手机和平板的会话列表在主区呈现（null = 聊天）。
    const mobileNavTab = useAppUiStore((s) => s.mobileNavTab);
    const setMobileNavTab = useAppUiStore((s) => s.setMobileNavTab);
    const viewportWidth = useViewportWidth();

    // 检测是否为独立 Sidebar 模式（新窗口打开）
    const isDetachedSidebar = useMemo(() => {
        const params = new URLSearchParams(window.location.search);
        return params.get('sidebar') === 'detached';
    }, []);

    const detachedTab = useMemo(() => {
        const params = new URLSearchParams(window.location.search);
        return params.get('tab') || undefined;
    }, []);

    // WebSocket 连接状态
    const [isConnected, setIsConnected] = useState(false);

    // WebSocket 连接
    useWebSocket({
        onConnect: () => {
            setIsConnected(true);
        },
        onDisconnect: () => {
            setIsConnected(false);
        },
        onError: (error) => {
            console.error('[AppLayout] WebSocket error:', error);
            setIsConnected(false);
        },
    });

    const openSessionList = useCallback(() => {
        setMobileNavTab('sessions');
    }, [setMobileNavTab]);

    // 独立 Sidebar 模式：只渲染 Sidebar 全屏
    if (isDetachedSidebar) {
        return (
            <div className="app-root app-workspace flex flex-col bg-[var(--v2-bg-surface)] overflow-hidden">
                <Sidebar className="flex-1" isDrawerMode={false} defaultTab={detachedTab} />
                {!isConnected && (
                    <div className="fixed bottom-4 left-1/2 -translate-x-1/2 
                        px-4 py-2 bg-red-500 text-white text-sm rounded-lg shadow-lg
                        flex items-center gap-2 z-50">
                        <span className="w-2 h-2 bg-white rounded-full animate-pulse" />
                        连接断开，正在重连...
                    </div>
                )}
            </div>
        );
    }

    return (
        <div className="app-root app-workspace flex flex-col bg-[var(--v2-bg-surface)] overflow-hidden">
            {/* Header */}
            <Header 
                onMenuClick={openSessionList}
                showMenuButton={compactNavigation}
            />

            {/* Main Layout */}
            <div className="flex-1 min-h-0 flex overflow-hidden">
                {/* Desktop Sidebar */}
                {!compactNavigation && (
                    <Sidebar className="shrink-0" />
                )}

                {/* Main Content Area */}
                <main className="flex-1 min-h-0 flex flex-col min-w-0">
                    {/* Content — 手机和平板菜单直接在主区打开会话列表 */}
                    <div className="flex-1 min-h-0 overflow-hidden relative">
                        {compactNavigation && mobileNavTab ? (
                            <div className="h-full flex flex-col">
                                {mobileNavTab !== 'sessions' && <div className="flex items-center gap-1 h-11 px-1 border-b border-[var(--v2-border-hairline)] flex-shrink-0">
                                    <button
                                        onClick={() => setMobileNavTab(null)}
                                        aria-label="返回"
                                        className="panel-control min-w-[44px] min-h-[44px] flex items-center justify-center rounded-xl
                                            text-t2 hover:bg-hover2 transition-colors duration-fast"
                                    >
                                        <ChevronLeft className="w-5 h-5" />
                                    </button>
                                    <span className="text-sm font-semibold text-t1 truncate">
                                        {SIDEBAR_TAB_LABELS[mobileNavTab as TabType] ?? mobileNavTab}
                                    </span>
                                </div>}
                                <div className="flex-1 min-h-0 overflow-hidden">
                                    <SidebarTabContent activeTab={mobileNavTab as TabType} width={viewportWidth} onSessionActivated={() => setMobileNavTab(null)} onBack={() => setMobileNavTab(null)} />
                                </div>
                            </div>
                        ) : (
                            children
                        )}
                    </div>

                    {isMobile && mobileNavTab && <MobileComposerNavigation />}
                    {/* StatusBar */}
                    {isMobile && aposEnabled && mobileStatusEnabled
                        ? <MobileStatusBar />
                        : !isMobile && <StatusBar />}
                </main>
            </div>

            {/* Connection Status Toast */}
            {!isConnected && (
                <div className="fixed bottom-12 left-1/2 -translate-x-1/2 
                    px-4 py-2 bg-red-500 text-white text-sm rounded-lg shadow-lg
                    flex items-center gap-2 z-50">
                    <span className="w-2 h-2 bg-white rounded-full animate-pulse" />
                    连接断开，正在重连...
                </div>
            )}

            {/* Phase 2: Mobile Status Bar */}
        </div>
    );
}

export default AppLayout;
