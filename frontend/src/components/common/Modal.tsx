/**
 * Modal — 通用模态框组件
 * SPEC: §8.2.6a
 * 支持 Escape 关闭、背景点击关闭、标题栏
 */

import { ReactNode, useRef } from 'react';
import { useModalBehavior } from '@/hooks/useModalBehavior';

interface ModalProps {
    isOpen: boolean;
    onClose: () => void;
    title?: string;
    children: ReactNode;
}

export function Modal({ isOpen, onClose, title, children }: ModalProps) {
    const panelRef = useRef<HTMLDivElement>(null);
    useModalBehavior(isOpen, panelRef, onClose);

    if (!isOpen) return null;

    return (
        <div
            className="fixed inset-0 z-50 flex items-center justify-center bg-overlay2 backdrop-blur-[3px]"
            onClick={onClose}
        >
            <div
                ref={panelRef}
                role="dialog"
                aria-modal="true"
                aria-label={title ?? '详情'}
                tabIndex={-1}
                className="bg-surfacev2 border border-hairline rounded-panel shadow-e4 motion-safe:animate-scale-in max-w-lg w-full mx-4 max-h-[80vh] overflow-y-auto"
                onClick={e => e.stopPropagation()}
            >
                {title && (
                    <div className="px-4 md:px-6 py-4 border-b border-hairline font-semibold text-xl text-t1">
                        {title}
                    </div>
                )}
                <div className="p-4 md:p-6">{children}</div>
            </div>
        </div>
    );
}
