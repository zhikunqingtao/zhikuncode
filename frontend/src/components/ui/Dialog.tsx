import { forwardRef, useCallback, useEffect, useId, useRef } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';
import { cn } from './cn';

/**
 * Dialog 基元（§7.7 / §10.2 / §10.7-④）：
 * - 面板：bg-surfacev2 + rounded-panel + shadow-e4，入场 motion-safe:animate-scale-in
 * - 遮罩：bg-overlay2 + 2px 级 blur；Esc / 遮罩点击 / 关闭按钮走同一 requestClose
 * - 焦点：打开时移入首个可交互元素（无则面板本身）；Tab/Shift+Tab 陷阱
 * - 关闭后焦点归还触发器；触发器已卸载则归还打开时记录的父容器（tabindex=-1），
 *   永不归还 document.body（§10.7-④ 焦点归还链单点维护于此）
 */
export interface DialogProps
    extends Omit<React.HTMLAttributes<HTMLDivElement>, 'title' | 'onChange'> {
    open: boolean;
    onOpenChange?: (open: boolean) => void;
    onClose?: () => void;
    /** 显式指定焦点归还目标；缺省取打开瞬间的 document.activeElement */
    triggerRef?: React.RefObject<HTMLElement | null>;
    /** 传入时自动生成 aria-labelledby */
    title?: React.ReactNode;
    /** 右上角关闭按钮（默认显示） */
    showClose?: boolean;
    children?: React.ReactNode;
}

const FOCUSABLE_SELECTOR =
    'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

export const Dialog = forwardRef<HTMLDivElement, DialogProps>(
    (
        {
            className,
            open,
            onOpenChange,
            onClose,
            triggerRef,
            title,
            showClose = true,
            children,
            id,
            'aria-labelledby': ariaLabelledby,
            ...props
        },
        ref,
    ) => {
        const autoId = useId();
        const titleId = `${autoId}-title`;
        const panelRef = useRef<HTMLDivElement | null>(null);
        const backdropRef = useRef<HTMLDivElement | null>(null);
        /** 遮罩关闭“武装”标记：仅 pointerdown 落在遮罩本身时置位，click 时再次校验目标仍为遮罩才关闭
           （防“面板内按下、遮罩上松开”误关，也防点击穿透到遮罩下方元素） */
        const backdropCloseArmedRef = useRef(false);
        /** 打开时捕获：触发器 + 其当时的父容器（供触发器卸载后归还） */
        const returnFocusRef = useRef<{ el: HTMLElement | null; parent: HTMLElement | null }>({
            el: null,
            parent: null,
        });
        const wasOpenRef = useRef(false);

        /** 唯一关闭入口：Esc / 遮罩 / 关闭按钮全部走这里（§10.7-④） */
        const requestClose = useCallback(() => {
            onOpenChange?.(false);
            onClose?.();
        }, [onOpenChange, onClose]);

        const returnFocus = useCallback(() => {
            const { el, parent } = returnFocusRef.current;
            if (el && el.isConnected) {
                el.focus();
                return;
            }
            if (parent && parent.isConnected) {
                if (!parent.hasAttribute('tabindex')) parent.setAttribute('tabindex', '-1');
                parent.focus();
            }
            /* 两者都不可用：保持现状，禁止归还 document.body */
        }, []);

        /* 持续跟踪显式 triggerRef：记录其最近挂载元素与当时的父容器。
           覆盖“触发器与 Dialog 同帧卸载”场景——打开 effect 运行时 ref 已置 null，
           此处保留的记录仍能归还到父容器。须声明在打开/关闭生命周期 effect 之前。 */
        useEffect(() => {
            const el = triggerRef?.current;
            if (el) returnFocusRef.current = { el, parent: el.parentElement };
        });

        /* 打开/关闭生命周期：捕获触发器、移入焦点、归还焦点、锁滚动 */
        useEffect(() => {
            if (open && !wasOpenRef.current) {
                if (triggerRef) {
                    /* ref 仍挂载：刷新记录；已卸载：沿用持续跟踪的记录 */
                    if (triggerRef.current) {
                        returnFocusRef.current = {
                            el: triggerRef.current,
                            parent: triggerRef.current.parentElement,
                        };
                    }
                } else {
                    const active =
                        document.activeElement instanceof HTMLElement &&
                        document.activeElement !== document.body
                            ? document.activeElement
                            : null;
                    returnFocusRef.current = { el: active, parent: active?.parentElement ?? null };
                }

                const panel = panelRef.current;
                if (panel) {
                    const first = panel.querySelector<HTMLElement>(FOCUSABLE_SELECTOR);
                    (first ?? panel).focus();
                }
                document.body.style.overflow = 'hidden';
            } else if (!open && wasOpenRef.current) {
                document.body.style.overflow = '';
                returnFocus();
            }
            wasOpenRef.current = open;
            return () => {
                if (wasOpenRef.current) {
                    document.body.style.overflow = '';
                }
            };
        }, [open, triggerRef, returnFocus]);

        /* Esc 关闭 + Tab 焦点陷阱（仅打开时挂载） */
        useEffect(() => {
            if (!open) return;
            const handleKeyDown = (e: KeyboardEvent) => {
                if (e.key === 'Escape') {
                    e.preventDefault();
                    requestClose();
                    return;
                }
                if (e.key !== 'Tab') return;
                const panel = panelRef.current;
                if (!panel) return;
                const focusables = Array.from(
                    panel.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR),
                ).filter((el) => el.offsetParent !== null || el === document.activeElement);
                if (focusables.length === 0) {
                    e.preventDefault();
                    panel.focus();
                    return;
                }
                const first = focusables[0];
                const last = focusables[focusables.length - 1];
                const active = document.activeElement;
                if (e.shiftKey) {
                    if (active === first || !panel.contains(active)) {
                        e.preventDefault();
                        last.focus();
                    }
                } else if (active === last || !panel.contains(active)) {
                    e.preventDefault();
                    first.focus();
                }
            };
            document.addEventListener('keydown', handleKeyDown);
            return () => document.removeEventListener('keydown', handleKeyDown);
        }, [open, requestClose]);

        if (!open) return null;

        return createPortal(
            <div
                className="fixed inset-0 z-50 flex items-center justify-center p-4"
                onPointerDown={(e) => {
                    backdropCloseArmedRef.current = e.target === backdropRef.current;
                }}
                onClick={(e) => {
                    if (backdropCloseArmedRef.current && e.target === backdropRef.current) {
                        requestClose();
                    }
                    backdropCloseArmedRef.current = false;
                }}
            >
                <div
                    ref={backdropRef}
                    aria-hidden="true"
                    className="absolute inset-0 bg-overlay2 backdrop-blur-[2px]"
                />
                <div
                    ref={(el) => {
                        panelRef.current = el;
                        if (typeof ref === 'function') ref(el);
                        else if (ref) ref.current = el;
                    }}
                    role="dialog"
                    aria-modal="true"
                    aria-labelledby={ariaLabelledby ?? (title !== undefined ? titleId : undefined)}
                    id={id}
                    tabIndex={-1}
                    className={cn(
                        'relative w-full max-w-md bg-surfacev2 rounded-panel shadow-e4 outline-none',
                        'motion-safe:animate-scale-in',
                        className,
                    )}
                    {...props}
                >
                    {(title !== undefined || showClose) && (
                        <div className="flex items-start justify-between gap-4 px-5 pt-4">
                            {title !== undefined ? (
                                <h2 id={titleId} className="text-base font-semibold text-t1">
                                    {title}
                                </h2>
                            ) : (
                                <span />
                            )}
                            {showClose && (
                                <button
                                    type="button"
                                    aria-label="关闭"
                                    onClick={requestClose}
                                    className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg text-t2 transition-interactive duration-fast hover:bg-hover2 hover:text-t1 focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring"
                                >
                                    <X className="h-4 w-4" aria-hidden="true" />
                                </button>
                            )}
                        </div>
                    )}
                    {children}
                </div>
            </div>,
            document.body,
        );
    },
);
Dialog.displayName = 'Dialog';
