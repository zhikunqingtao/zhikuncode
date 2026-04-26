import React, { useState } from 'react';
import { ChevronDown } from 'lucide-react';

interface CommandPanelProps {
    title: string;
    icon?: React.ReactNode;
    children: React.ReactNode;
    className?: string;
    actions?: React.ReactNode;
    collapsible?: boolean;
    defaultExpanded?: boolean;
}

export function CommandPanel({
    title,
    icon,
    children,
    className = '',
    actions,
    collapsible = false,
    defaultExpanded = true,
}: CommandPanelProps) {
    const [expanded, setExpanded] = useState(defaultExpanded);

    return (
        <div
            className={`rounded-lg border border-[var(--border)] overflow-hidden ${className}`}
        >
            <div
                className={`flex items-center justify-between px-4 py-2 bg-[var(--bg-secondary)] ${
                    collapsible ? 'cursor-pointer' : ''
                }`}
                onClick={collapsible ? () => setExpanded(prev => !prev) : undefined}
            >
                <div className="flex items-center gap-2">
                    {icon && (
                        <span className="text-[var(--text-muted)]">{icon}</span>
                    )}
                    <h3 className="text-sm font-medium text-[var(--text-primary)]">
                        {title}
                    </h3>
                </div>
                <div className="flex items-center gap-2">
                    {actions}
                    {collapsible && (
                        <ChevronDown
                            className={`w-4 h-4 text-[var(--text-muted)] transition-transform duration-200 ${
                                expanded ? 'rotate-180' : ''
                            }`}
                        />
                    )}
                </div>
            </div>
            {(!collapsible || expanded) && (
                <div className="p-4">{children}</div>
            )}
        </div>
    );
}
