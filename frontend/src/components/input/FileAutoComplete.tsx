/**
 * FileAutoComplete — 文件自动补全下拉组件
 * SPEC: §4.3 @文件附件功能
 */

import React, { useState, useEffect, useRef } from 'react';

interface FileResult {
    path: string;
    name: string;
    type: 'file' | 'directory';
    score: number;
}

interface FileAutoCompleteProps {
    query: string;
    onSelect: (filePath: string) => void;
    onClose: () => void;
}

export const FileAutoComplete: React.FC<FileAutoCompleteProps> = ({
    query, onSelect, onClose,
}) => {
    const [results, setResults] = useState<FileResult[]>([]);
    const [selectedIndex, setSelectedIndex] = useState(0);
    const [loading, setLoading] = useState(false);
    const debounceRef = useRef<ReturnType<typeof setTimeout>>();

    // 防抖搜索
    useEffect(() => {
        if (!query) { setResults([]); return; }
        clearTimeout(debounceRef.current);
        debounceRef.current = setTimeout(async () => {
            setLoading(true);
            try {
                const res = await fetch(`/api/files/search?q=${encodeURIComponent(query)}&limit=15`);
                const data: FileResult[] = await res.json();
                setResults(data);
                setSelectedIndex(0);
            } catch {
                setResults([]);
            }
            setLoading(false);
        }, 150);
        return () => clearTimeout(debounceRef.current);
    }, [query]);

    // 键盘导航
    useEffect(() => {
        const handler = (e: KeyboardEvent) => {
            switch (e.key) {
                case 'ArrowDown': e.preventDefault();
                    setSelectedIndex(i => Math.min(i + 1, results.length - 1)); break;
                case 'ArrowUp': e.preventDefault();
                    setSelectedIndex(i => Math.max(i - 1, 0)); break;
                case 'Enter': case 'Tab': e.preventDefault();
                    if (results[selectedIndex]) onSelect(results[selectedIndex].path); break;
                case 'Escape': onClose(); break;
            }
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, [results, selectedIndex, onSelect, onClose]);

    if (!results.length && !loading) return null;

    return (
        <div className="absolute bottom-full mb-1 left-0 z-50 bg-white dark:bg-gray-800
            border rounded-lg shadow-xl max-h-64 overflow-y-auto w-80">
            {loading && <div className="p-2 text-xs text-gray-400">搜索中...</div>}
            {results.map((r, i) => (
                <button key={r.path}
                    className={`w-full text-left px-3 py-1.5 flex items-center gap-2 text-sm
                        ${i === selectedIndex ? 'bg-blue-600 text-white' : 'hover:bg-gray-100 dark:hover:bg-gray-700'}`}
                    onClick={() => onSelect(r.path)}
                    onMouseEnter={() => setSelectedIndex(i)}>
                    <span>{r.type === 'directory' ? '📁' : '📄'}</span>
                    <span className="flex-1 font-mono truncate">{r.path}</span>
                </button>
            ))}
        </div>
    );
};
