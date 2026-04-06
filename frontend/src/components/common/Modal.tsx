/**
 * Modal — 通用模态框组件
 * SPEC: §8.2.6a
 * 支持 Escape 关闭、背景点击关闭、标题栏
 */

import { ReactNode, useEffect, useCallback } from 'react';

interface ModalProps {
    isOpen: boolean;
    onClose: () => void;
    title?: string;
    children: ReactNode;
}

export function Modal({ isOpen, onClose, title, children }: ModalProps) {
    const handleKeyDown = useCallback((e: KeyboardEvent) => {
        if (e.key === 'Escape') onClose();
    }, [onClose]);

    useEffect(() => {
        if (isOpen) {
            document.addEventListener('keydown', handleKeyDown);
            return () => document.removeEventListener('keydown', handleKeyDown);
        }
    }, [isOpen, handleKeyDown]);

    if (!isOpen) return null;

    return (
        <div
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
            onClick={onClose}
        >
            <div
                className="bg-[var(--bg-primary)] rounded-xl shadow-2xl max-w-lg w-full mx-4 max-h-[80vh] overflow-y-auto"
                onClick={e => e.stopPropagation()}
            >
                {title && (
                    <div className="px-5 py-3 border-b border-[var(--border)] font-semibold text-sm">
                        {title}
                    </div>
                )}
                <div className="p-5">{children}</div>
            </div>
        </div>
    );
}
