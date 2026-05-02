/**
 * AppLayout — 应用主布局组件
 * SPEC: §8.6
 *
 * 三栏布局: Sidebar (左) | Main Content (中) | StatusBar (底)
 * 响应式: 移动端 Sidebar 变为 Drawer
 */

import { useState, useCallback, useEffect, useMemo } from 'react';
import { Header } from './Header';
import { Sidebar } from './Sidebar';
import { StatusBar } from './StatusBar';
import { Drawer } from './Drawer';
import { useWebSocket } from '@/hooks/useWebSocket';

interface AppLayoutProps {
    children: React.ReactNode;
}

export function AppLayout({ children }: AppLayoutProps) {
    const [sidebarOpen, setSidebarOpen] = useState(false);
    const [isMobile, setIsMobile] = useState(false);

    // 检测是否为独立 Sidebar 模式（新窗口打开）
    const isDetachedSidebar = useMemo(() => {
        const params = new URLSearchParams(window.location.search);
        return params.get('sidebar') === 'detached';
    }, []);

    const detachedTab = useMemo(() => {
        const params = new URLSearchParams(window.location.search);
        return params.get('tab') || undefined;
    }, []);

    // 检测移动端
    useEffect(() => {
        const checkMobile = () => {
            setIsMobile(window.innerWidth < 1024);
        };
        
        checkMobile();
        window.addEventListener('resize', checkMobile);
        return () => window.removeEventListener('resize', checkMobile);
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
            <div className="h-screen flex flex-col bg-[var(--bg-primary)] overflow-hidden">
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
        <div className="h-screen flex flex-col bg-[var(--bg-primary)] overflow-hidden">
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

                {/* Mobile Drawer */}
                {isMobile && (
                    <Drawer open={sidebarOpen} onClose={closeSidebar}>
                        <Sidebar isDrawerMode />
                    </Drawer>
                )}

                {/* Main Content Area */}
                <main className="flex-1 flex flex-col min-w-0">
                    {/* Content */}
                    <div className="flex-1 overflow-hidden relative">
                        {children}
                    </div>

                    {/* StatusBar */}
                    <StatusBar />
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
        </div>
    );
}

export default AppLayout;
