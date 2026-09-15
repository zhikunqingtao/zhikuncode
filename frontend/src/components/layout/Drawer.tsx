import { GlassMaterial } from '@/components/theme/GlassMaterial';
/**
 * Drawer — 移动端抽屉覆盖层组件
 * SPEC: §8.8.2 / §7.5 / §8.5
 *
 * 移动端 Sidebar 替代方案：从左侧滑入的 overlay 抽屉。
 * 支持背景点击关闭、Escape 关闭、过渡动画。
 * §7.5 容器规范：宽 280px / max 82%，rounded-r-panel + shadow-e4，
 * overlay2 遮罩，滑入时长走 §5.1 时长表 Drawer 行（duration-sheet 320ms）。
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
            {/* Overlay 背景 — overlay2 令牌 */}
            <div
                className={`fixed inset-0 z-40 bg-overlay2 transition-opacity duration-sheet ease-soft
                    ${open ? 'opacity-100' : 'opacity-0 pointer-events-none'}`}
                onClick={onClose}
                aria-hidden="true"
            />

            {/* 抽屉面板 — 宽 280px / max 82%，rounded-r-panel + shadow-e4 */}
            <div
                role="dialog"
                aria-modal="true"
                aria-label="侧边栏"
                className={`glass-surface fixed top-0 ${side === 'left' ? 'left-0' : 'right-0'} z-50
                    h-full bg-surfacev2 shadow-e4 overflow-hidden
                    ${side === 'left' ? 'rounded-r-panel' : 'rounded-l-panel'}
                    transition-transform duration-sheet ease-soft
                    ${open
                        ? 'translate-x-0'
                        : side === 'left'
                            ? '-translate-x-full'
                            : 'translate-x-full'
                    }`}
                style={{ width: `${width}px`, maxWidth: '82%' }}
            >
                <GlassMaterial kind="overlay" />
                <div className="h-full flex flex-col overscroll-contain">
                    {children}
                </div>
            </div>
        </>
    );
}
