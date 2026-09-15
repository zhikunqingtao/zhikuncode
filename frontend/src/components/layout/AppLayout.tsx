/**
 * AppLayout — 应用主布局组件
 * SPEC: §8.6
 *
 * 三栏布局: Sidebar (左) | Main Content (中) | StatusBar (底)
 * 响应式: 移动端 Sidebar 变为 Drawer
 */

import { useState, useCallback, useMemo } from 'react';
import { ChevronLeft } from 'lucide-react';
import { Header } from './Header';
import { Sidebar, SidebarTabContent, SIDEBAR_TAB_LABELS, type TabType } from './Sidebar';
import { StatusBar } from './StatusBar';
import { Drawer } from './Drawer';
import { useWebSocket } from '@/hooks/useWebSocket';
import { useResponsive, useViewportWidth } from '@/hooks/useResponsive';
import { useFeatureFlagStore } from '@/store/featureFlagStore';
import { useAppUiStore } from '@/store/appUiStore';
import { MobileStatusBar } from '@/components/apos/MobileStatusBar';

interface AppLayoutProps {
    children: React.ReactNode;
}

export function AppLayout({ children }: AppLayoutProps) {
    const [sidebarOpen, setSidebarOpen] = useState(false);
    // §8.1 断点统一：<768px 走 Drawer + 移动 Chrome；768–1023px compact 保留桌面侧栏；≥1024px 桌面
    const { isMobile } = useResponsive();

    const aposEnabled = useFeatureFlagStore((s) => s.flags.APOS_ACTIVITY_STREAM);
    const mobileStatusEnabled = useFeatureFlagStore((s) => s.flags.APOS_MOBILE_STATUS);

    // §7.5 移动端：抽屉文字列表选中的 Tab 在主区呈现（null = 聊天）
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

    const toggleSidebar = useCallback(() => {
        setSidebarOpen(prev => !prev);
    }, []);

    const closeSidebar = useCallback(() => {
        setSidebarOpen(false);
    }, []);

    // 独立 Sidebar 模式：只渲染 Sidebar 全屏
    if (isDetachedSidebar) {
        return (
            <div className="app-workspace h-screen flex flex-col bg-[var(--bg-primary)] overflow-hidden">
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
        <div className="app-workspace h-screen flex flex-col bg-[var(--bg-primary)] overflow-hidden">
            {/* Header */}
            <Header 
                onMenuClick={toggleSidebar} 
                showMenuButton={isMobile}
            />

            {/* Main Layout */}
            <div className="flex-1 flex overflow-hidden">
                {/* Desktop Sidebar */}
                {!isMobile && (
                    <Sidebar className="shrink-0" />
                )}

                {/* Mobile Drawer — §7.5 文字列表，选中 Tab 后自动关闭 */}
                {isMobile && (
                    <Drawer open={sidebarOpen} onClose={closeSidebar}>
                        <Sidebar isDrawerMode onNavigate={closeSidebar} />
                    </Drawer>
                )}

                {/* Main Content Area */}
                <main className="flex-1 flex flex-col min-w-0">
                    {/* Content — 移动形态下抽屉选中 Tab 时主区切换为对应面板 */}
                    <div className="flex-1 overflow-hidden relative">
                        {isMobile && mobileNavTab ? (
                            <div className="h-full flex flex-col">
                                <div className="flex items-center gap-1 h-11 px-1 border-b border-[var(--border)] flex-shrink-0">
                                    <button
                                        onClick={() => setMobileNavTab(null)}
                                        aria-label="返回"
                                        className="min-w-[44px] min-h-[44px] flex items-center justify-center rounded-xl
                                            text-t2 hover:bg-hover2 transition-colors duration-fast"
                                    >
                                        <ChevronLeft className="w-5 h-5" />
                                    </button>
                                    <span className="text-sm font-semibold text-t1 truncate">
                                        {SIDEBAR_TAB_LABELS[mobileNavTab as TabType] ?? mobileNavTab}
                                    </span>
                                </div>
                                <div className="flex-1 min-h-0 overflow-hidden">
                                    <SidebarTabContent activeTab={mobileNavTab as TabType} width={viewportWidth} />
                                </div>
                            </div>
                        ) : (
                            children
                        )}
                    </div>

                    {/* StatusBar */}
                    {isMobile && aposEnabled && mobileStatusEnabled
                        ? <div aria-hidden="true" className="shrink-0" style={{ height: 'calc(36px + env(safe-area-inset-bottom))' }} />
                        : <StatusBar />}
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
            {isMobile && aposEnabled && mobileStatusEnabled && (
                <MobileStatusBar />
            )}
        </div>
    );
}

export default AppLayout;
