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
                ? 'fixed inset-0 z-50 flex items-start justify-center pt-[15vh] bg-black/50'
                : 'absolute bottom-full left-0 w-full mb-1'}`}
            onClick={isGlobal ? onClose : undefined}
            onKeyDown={handleKeyDown}
        >
            <div
                className={`bg-gray-900 border border-gray-700 rounded-xl shadow-2xl overflow-hidden
                    ${isGlobal ? 'w-full max-w-lg mx-4' : 'w-full'}`}
                onClick={e => e.stopPropagation()}
            >
                {/* Search input (global mode) */}
                {isGlobal && (
                    <div className="flex items-center gap-2 px-3 py-2.5 border-b border-gray-700">
                        <Search size={16} className="text-gray-500 flex-shrink-0" />
                        <input
                            ref={inputRef}
                            value={searchInput}
                            onChange={e => setSearchInput(e.target.value)}
                            onKeyDown={handleKeyDown}
                            placeholder="Type a command..."
                            className="flex-1 bg-transparent text-sm text-gray-200 outline-none placeholder-gray-500"
                        />
                    </div>
                )}

                {/* Command list */}
                <div ref={listRef} className="max-h-64 overflow-y-auto py-1">
                    {filtered.length === 0 ? (
                        <div className="px-3 py-4 text-sm text-gray-500 text-center">
                            No commands found
                        </div>
                    ) : (
                        Array.from(grouped.entries()).map(([group, cmds]) => (
                            <div key={group}>
                                {grouped.size > 1 && (
                                    <div className="px-3 py-1 text-xs text-gray-600 font-medium uppercase tracking-wider">
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
                                                    ? 'bg-blue-600/20 text-blue-300'
                                                    : 'text-gray-300 hover:bg-gray-800'}`}
                                        >
                                            <span className="font-mono text-xs">/{cmd.name}</span>
                                            <span className="text-xs text-gray-500 truncate ml-3 max-w-[60%]">
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
                <div className="px-3 py-1.5 border-t border-gray-700/50 text-xs text-gray-600 flex gap-3">
                    <span>↑↓ Navigate</span>
                    <span>↵ Select</span>
                    <span>Esc Close</span>
                </div>
            </div>
        </div>
    );
};

export default React.memo(CommandPalette);
