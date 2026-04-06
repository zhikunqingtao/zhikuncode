/**
 * Drawer — 移动端抽屉覆盖层组件
 * SPEC: §8.8.2
 *
 * 移动端 Sidebar 替代方案：从左侧滑入的 overlay 抽屉。
 * 支持背景点击关闭、Escape 关闭、过渡动画。
 */

import { useEffect, useCallback, ReactNode } from 'react';

interface DrawerProps {
    open: boolean;
    onClose: () => void;
    children: ReactNode;
    /** 抽屉宽度, 默认 280px */
    width?: number;
    /** 从哪一侧滑入, 默认 left */
    side?: 'left' | 'right';
}

export function Drawer({
    open,
    onClose,
    children,
    width = 280,
    side = 'left',
}: DrawerProps) {
    const handleKeyDown = useCallback((e: KeyboardEvent) => {
        if (e.key === 'Escape') onClose();
    }, [onClose]);

    useEffect(() => {
        if (open) {
            document.addEventListener('keydown', handleKeyDown);
            // 防止背景滚动
            document.body.style.overflow = 'hidden';
            return () => {
                document.removeEventListener('keydown', handleKeyDown);
                document.body.style.overflow = '';
            };
        }
    }, [open, handleKeyDown]);

    return (
        <>
            {/* Overlay 背景 */}
            <div
                className={`fixed inset-0 z-40 bg-black/50 transition-opacity duration-200
                    ${open ? 'opacity-100' : 'opacity-0 pointer-events-none'}`}
                onClick={onClose}
                aria-hidden="true"
            />

            {/* 抽屉面板 */}
            <div
                role="dialog"
                aria-modal="true"
                aria-label="侧边栏"
                className={`fixed top-0 ${side === 'left' ? 'left-0' : 'right-0'} z-50
                    h-full bg-[var(--bg-primary)] shadow-2xl
                    transition-transform duration-200 ease-out
                    ${open
                        ? 'translate-x-0'
                        : side === 'left'
                            ? '-translate-x-full'
                            : 'translate-x-full'
                    }`}
                style={{ width: `${width}px` }}
            >
                <div className="h-full overflow-y-auto overscroll-contain">
                    {children}
                </div>
            </div>
        </>
    );
}
