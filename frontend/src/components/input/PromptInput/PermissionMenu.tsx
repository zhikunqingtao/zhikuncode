import { useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Check, ChevronDown, Shield } from 'lucide-react';
import { PERMISSION_MODES, type PermissionMode } from '@/types';
import { getPermissionModeDescription, getPermissionModeLabel } from '@/components/layout/StatusBar';
import { useModalBehavior } from '@/hooks/useModalBehavior';

export function PermissionMenu({ value, onChange, disabled = false }: { disabled?: boolean; value: PermissionMode; onChange: (mode: PermissionMode) => void }) {
    const [open, setOpen] = useState(false);
    const [position, setPosition] = useState({ left: 8, top: 8, maxHeight: 400 });
    const trigger = useRef<HTMLButtonElement>(null);
    const panel = useRef<HTMLDivElement>(null);
    useModalBehavior(open && !disabled, panel, () => setOpen(false));
    useLayoutEffect(() => {
        if (disabled) { setOpen(false); return; }
        if (!open) return;
        const place = () => {
            const rect = trigger.current?.getBoundingClientRect();
            if (!rect) return;
            const height = Math.min(panel.current?.scrollHeight ?? 400, window.innerHeight - 16);
            const top = rect.top >= height + 8 ? rect.top - height - 8 : Math.min(rect.bottom + 8, window.innerHeight - height - 8);
            setPosition({ left: Math.max(8, Math.min(rect.left, window.innerWidth - 328)), top: Math.max(8, top), maxHeight: window.innerHeight - 16 });
        };
        place();
        window.addEventListener('resize', place);
        window.addEventListener('scroll', place, true);
        return () => { window.removeEventListener('resize', place); window.removeEventListener('scroll', place, true); };
    }, [open, disabled]);
    return <>
        <button disabled={disabled} ref={trigger} type="button" aria-label="权限模式" aria-haspopup="dialog" aria-expanded={open && !disabled} onClick={() => setOpen(true)} className="inline-flex min-h-7 items-center gap-1.5 rounded-full border border-hairline bg-surface2 px-2.5 text-[13px] text-t2 hover:bg-hover2 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent2-ink">
            <Shield size={14} aria-hidden="true" />{getPermissionModeLabel(value)}<ChevronDown size={14} aria-hidden="true" />
        </button>
        {open && !disabled && createPortal(<div className="fixed inset-0 z-[200]" onPointerDown={event => { if (event.target === event.currentTarget) setOpen(false); }}>
            <div ref={panel} role="dialog" aria-modal="true" aria-label="选择权限" tabIndex={-1} style={{ ...position, width: 'min(320px, calc(100vw - 16px))' }} className="fixed overflow-y-auto rounded-[14px] border border-hairline bg-surface2 p-2 shadow-e4 focus:outline-none" onKeyDown={event => {
                if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
                event.preventDefault();
                const options = [...event.currentTarget.querySelectorAll<HTMLButtonElement>('button')];
                const index = options.indexOf(document.activeElement as HTMLButtonElement);
                const next = event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length;
                options[next]?.focus();
            }}>
                <h3 className="px-3 py-2 text-base font-semibold text-t1">选择权限</h3>
                {PERMISSION_MODES.map(mode => <button key={mode} type="button" aria-pressed={mode === value} onClick={() => { onChange(mode); setOpen(false); }} className={`flex min-h-11 w-full items-center gap-3 rounded-[10px] px-3 py-2 text-left hover:bg-hover2 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent2-ink ${mode === value ? 'bg-accent2-soft' : ''}`}>
                    <span className="flex-1"><span className="block text-sm font-medium text-t1">{getPermissionModeLabel(mode)}</span><span className="mt-1 block text-[13px] text-t2">{getPermissionModeDescription(mode)}</span></span>
                    {mode === value && <Check size={18} className="shrink-0 text-accent2-ink" aria-hidden="true" />}
                </button>)}
            </div>
        </div>, document.body)}
    </>;
}
