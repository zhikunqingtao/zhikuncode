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
            className={`rounded-[10px] border border-[var(--v2-border-hairline)] overflow-hidden ${className}`}
        >
            <div
                className={`flex items-center justify-between px-4 py-2 bg-[var(--v2-bg-sunken)] ${
                    collapsible ? 'cursor-pointer' : ''
                }`}
                onClick={collapsible ? () => setExpanded(prev => !prev) : undefined}
            >
                <div className="flex items-center gap-2">
                    {icon && (
                        <span className="text-[var(--v2-text-2)]">{icon}</span>
                    )}
                    <h3 className="text-[var(--v2-text-1)] text-base font-semibold">
                        {title}
                    </h3>
                </div>
                <div className="flex items-center gap-2">
                    {actions}
                    {collapsible && (
                        <ChevronDown
                            className={`w-4 h-4 text-[var(--v2-text-2)] transition-transform duration-base ${
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
