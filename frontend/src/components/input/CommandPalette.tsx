/**
 * CommandPalette — Slash 命令面板
 *
 * SPEC: §8.2.6a.11 CommandPalette
 * 功能:
 * - / 触发命令自动完成列表
 * - 模糊搜索过滤
 * - 键盘导航 (ArrowUp/Down/Enter/Escape)
 * - Ctrl+K 打开全局命令面板
 */

import React, { useState, useCallback, useEffect, useRef, useMemo } from 'react';
import type { Command } from '@/types';
import { Search } from 'lucide-react';
import { Kbd } from '@/components/ui';

interface CommandPaletteProps {
    commands: Command[];
    filter: string;
    onSelect: (command: string) => void;
    onClose: () => void;
    /** 是否为全局命令面板模式 (Ctrl+K) */
    isGlobal?: boolean;
}

const CommandPalette: React.FC<CommandPaletteProps> = ({
    commands,
    filter,
    onSelect,
    onClose,
    isGlobal = false,
}) => {
    const [selectedIndex, setSelectedIndex] = useState(0);
    const [searchInput, setSearchInput] = useState(filter);
    const listRef = useRef<HTMLDivElement>(null);
    const inputRef = useRef<HTMLInputElement>(null);

    const query = isGlobal ? searchInput : filter;

    const filtered = useMemo(() => {
        const q = query.toLowerCase();
        return commands
            .filter(c => !c.hidden)
            .filter(c =>
                c.name.toLowerCase().includes(q) ||
                c.description.toLowerCase().includes(q),
            );
    }, [commands, query]);

    // Reset index when filter changes
    useEffect(() => { setSelectedIndex(0); }, [query]);

    // Focus input in global mode
    useEffect(() => {
        if (isGlobal) inputRef.current?.focus();
    }, [isGlobal]);

    // Scroll selected item into view
    useEffect(() => {
        const el = listRef.current?.children[selectedIndex] as HTMLElement | undefined;
        el?.scrollIntoView({ block: 'nearest' });
    }, [selectedIndex]);

    const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
        switch (e.key) {
            case 'ArrowDown':
                e.preventDefault();
                setSelectedIndex(i => Math.min(i + 1, filtered.length - 1));
                break;
            case 'ArrowUp':
                e.preventDefault();
                setSelectedIndex(i => Math.max(i - 1, 0));
                break;
            case 'Enter':
                e.preventDefault();
                if (filtered[selectedIndex]) {
                    onSelect(filtered[selectedIndex].name);
                }
                break;
            case 'Escape':
                e.preventDefault();
                onClose();
                break;
        }
    }, [filtered, selectedIndex, onSelect, onClose]);

    // Group commands by group
    const grouped = useMemo(() => {
        const groups = new Map<string, Command[]>();
        for (const cmd of filtered) {
            const g = cmd.group ?? 'Commands';
            if (!groups.has(g)) groups.set(g, []);
            groups.get(g)!.push(cmd);
        }
        return groups;
    }, [filtered]);

    let flatIndex = 0;

    return (
        <div
            className={`${isGlobal
                ? 'fixed inset-0 z-50 flex items-start justify-center pt-[15vh] bg-overlay2 backdrop-blur-[3px]'
                : 'absolute bottom-full left-0 w-full mb-1'}`}
            onClick={isGlobal ? onClose : undefined}
            onKeyDown={handleKeyDown}
        >
            <div
                className={`bg-surfacev2 border border-hairline rounded-panel shadow-e4 overflow-hidden
                    ${isGlobal ? 'w-full max-w-lg mx-4' : 'w-full'}`}
                onClick={e => e.stopPropagation()}
            >
                {/* Search input (global mode) */}
                {isGlobal && (
                    <div className="flex items-center gap-2 px-3 py-2.5 border-b border-hairline">
                        <Search size={16} className="text-t3 flex-shrink-0" />
                        <input
                            ref={inputRef}
                            value={searchInput}
                            onChange={e => setSearchInput(e.target.value)}
                            onKeyDown={handleKeyDown}
                            placeholder="Type a command..."
                            role="combobox"
                            aria-expanded="true"
                            className="flex-1 bg-transparent text-sm text-t1 outline-none placeholder:text-t4"
                        />
                    </div>
                )}

                {/* Command list */}
                <div ref={listRef} className="max-h-64 overflow-y-auto py-1" role="listbox">
                    {filtered.length === 0 ? (
                        <div className="px-3 py-4 text-sm text-t3 text-center">
                            No commands found
                        </div>
                    ) : (
                        Array.from(grouped.entries()).map(([group, cmds]) => (
                            <div key={group}>
                                {grouped.size > 1 && (
                                    <div className="px-3 py-1 text-[11px] text-t3 font-semibold uppercase tracking-[0.08em]">
                                        {group}
                                    </div>
                                )}
                                {cmds.map(cmd => {
                                    const idx = flatIndex++;
                                    return (
                                        <button
                                            key={cmd.name}
                                            onClick={() => onSelect(cmd.name)}
                                            className={`w-full text-left px-3 py-2 flex items-center justify-between
                                                text-sm transition-colors
                                                ${idx === selectedIndex
                                                    ? 'bg-accent2-soft text-accent2'
                                                    : 'text-t2 hover:bg-hover2'}`}
                                            role="option"
                                            aria-selected={idx === selectedIndex}
                                        >
                                            <span className="font-mono text-xs">/{cmd.name}</span>
                                            <span className="text-xs text-t3 truncate ml-3 max-w-[60%]">
                                                {cmd.description}
                                            </span>
                                        </button>
                                    );
                                })}
                            </div>
                        ))
                    )}
                </div>

                {/* Footer hint */}
                <div data-testid="command-palette-footer" className="px-3 py-1.5 border-t border-hairline text-xs text-t3 flex items-center gap-3">
                    <span className="flex items-center gap-1"><Kbd>↑↓</Kbd> 选择</span>
                    <span className="flex items-center gap-1"><Kbd>↵</Kbd> 确认</span>
                    <span className="flex items-center gap-1"><Kbd>Esc</Kbd> 关闭</span>
                </div>
            </div>
        </div>
    );
};

export default React.memo(CommandPalette);
