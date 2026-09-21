import React, { useEffect, useRef, useState } from 'react';
import { ArrowUpToLine, ArrowDownToLine, ChevronDown, X } from 'lucide-react';
import { SheetShell } from '@/components/apos/MobileBottomSheet';
import { cn } from '@/components/ui/cn';
import type { TurnNavigationEntry } from './turnNavigation';

interface Props {
    entries: TurnNavigationEntry[];
    activeKey: string | null;
    isMobile: boolean;
    onSelect: (entry: TurnNavigationEntry) => void;
    onLatest: () => void;
}
const buttonClass = 'inline-flex min-h-11 min-w-11 shrink-0 items-center justify-center gap-1 rounded-[10px] px-3 text-[13px] text-t2 hover:bg-hover2 focus-visible:ring-2 focus-visible:ring-accent2';

export default function DetailNavigation({ entries, activeKey, isMobile, onSelect, onLatest }: Props) {
    const [open, setOpen] = useState(false);
    const pills = useRef<HTMLDivElement>(null);
    const current = Math.max(0, entries.findIndex(entry => entry.key === activeKey));
    const tasks = entries.some(entry => entry.expandKey !== undefined);
    const label = tasks ? '任务' : '轮次';
    useEffect(() => {
        const container = pills.current;
        const active = container?.querySelector<HTMLElement>('[aria-current="location"]');
        if (!container || !active) return;
        // 只移动横向导航，避免 scrollIntoView 连带滚动消息区。
        if (active.offsetLeft < container.scrollLeft) container.scrollLeft = active.offsetLeft;
        else if (active.offsetLeft + active.offsetWidth > container.scrollLeft + container.clientWidth) {
            container.scrollLeft = active.offsetLeft + active.offsetWidth - container.clientWidth;
        }
    }, [activeKey, isMobile]);
    useEffect(() => { setOpen(false); }, [isMobile]);
    const close = React.useCallback(() => setOpen(false), []);
    return (
        <nav aria-label="详细视图导航" data-testid="detail-navigation" className="z-20 flex shrink-0 items-center gap-1 border-b border-hairline bg-surfacev2 px-2">
            <button className={buttonClass} disabled={!entries.length} aria-label={`第一个${label}`} onClick={() => entries[0] && onSelect(entries[0])}>
                <ArrowUpToLine size={16} />{!isMobile && `第一个${label}`}
            </button>
            {isMobile ? (
                <button className={cn(buttonClass, 'min-w-0 flex-1')} aria-label={`选择${label}`} aria-expanded={open} onClick={() => setOpen(true)}>
                    {label} {entries.length ? current + 1 : 0}/{entries.length}<ChevronDown size={14} />
                </button>
            ) : (
                <div ref={pills} className="relative flex min-w-0 flex-1 gap-1 overflow-x-auto" data-testid="detail-navigation-pills">
                    {entries.map(entry => (
                        <button key={entry.key} className={cn(buttonClass, 'max-w-48', entry.key === activeKey && 'bg-accent2-soft text-accent2-ink')}
                            aria-current={entry.key === activeKey ? 'location' : undefined} title={entry.title} onClick={() => onSelect(entry)}>
                            <span className="truncate">{entry.title}</span>
                        </button>
                    ))}
                </div>
            )}
            <button className={buttonClass} aria-label="最新进展" onClick={onLatest}><ArrowDownToLine size={16} />{!isMobile && '最新进展'}</button>
            {isMobile && <SheetShell isOpen={open} onClose={close} ariaLabel={`选择${label}`} header={
                <div className="flex items-center justify-between px-4"><h3>{label}导航</h3><button className={buttonClass} aria-label="关闭任务导航" onClick={close}><X size={18} /></button></div>
            }>
                <div className="flex flex-col px-3 pb-4">{entries.map(entry => (
                    <button key={entry.key} className={cn(buttonClass, 'justify-start text-left', entry.key === activeKey && 'bg-hover2 text-accent2-ink')}
                        aria-current={entry.key === activeKey ? 'location' : undefined}
                        onClick={() => { close(); onSelect(entry); }}>{entry.title}</button>
                ))}</div>
            </SheetShell>}
        </nav>
    );
}
