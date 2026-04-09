/**
 * CommandAutoComplete — 命令自动补全下拉组件
 * SPEC: §4.4 命令自动补全
 */

import React, { useState, useEffect } from 'react';
import { useCommandStore } from '@/store/commandStore';
import { fuzzyMatch } from '@/utils/fuzzyMatch';

interface CommandAutoCompleteProps {
    query: string;
    onSelect: (command: string) => void;
    onClose: () => void;
}

export const CommandAutoComplete: React.FC<CommandAutoCompleteProps> = ({
    query, onSelect, onClose,
}) => {
    const { commands, loaded, loadCommands } = useCommandStore();
    const [selectedIndex, setSelectedIndex] = useState(0);

    // 首次渲染时加载命令列表
    useEffect(() => { if (!loaded) loadCommands(); }, [loaded, loadCommands]);

    const filtered = fuzzyMatch(query, commands);

    // 键盘导航
    useEffect(() => {
        const handler = (e: KeyboardEvent) => {
            switch (e.key) {
                case 'ArrowDown': e.preventDefault();
                    setSelectedIndex(i => Math.min(i + 1, filtered.length - 1)); break;
                case 'ArrowUp': e.preventDefault();
                    setSelectedIndex(i => Math.max(i - 1, 0)); break;
                case 'Enter': case 'Tab': e.preventDefault();
                    if (filtered[selectedIndex]) onSelect('/' + filtered[selectedIndex].name); break;
                case 'Escape': onClose(); break;
            }
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, [filtered, selectedIndex, onSelect, onClose]);

    // query 变化时重置选中
    useEffect(() => setSelectedIndex(0), [query]);

    if (!filtered.length) return null;

    return (
        <div className="absolute bottom-full mb-1 left-0 z-50 bg-white dark:bg-gray-800
            border rounded-lg shadow-xl max-h-64 overflow-y-auto w-72">
            {filtered.map((cmd, i) => (
                <button key={cmd.name}
                    className={`w-full text-left px-3 py-2 flex flex-col
                        ${i === selectedIndex ? 'bg-blue-600 text-white' : 'hover:bg-gray-100 dark:hover:bg-gray-700'}`}
                    onClick={() => onSelect('/' + cmd.name)}
                    onMouseEnter={() => setSelectedIndex(i)}>
                    <div className="flex items-center gap-2">
                        <span className="font-mono font-semibold">/{cmd.name}</span>
                        <span className={`text-xs px-1 rounded
                            ${cmd.category === 'builtin' ? 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200'
                            : cmd.category === 'skill' ? 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200'
                            : 'bg-gray-100 text-gray-600'}`}>
                            {cmd.category}
                        </span>
                    </div>
                    <span className={`text-xs mt-0.5 ${i === selectedIndex ? 'text-blue-100' : 'text-gray-500'}`}>
                        {cmd.description}
                    </span>
                </button>
            ))}
        </div>
    );
};
