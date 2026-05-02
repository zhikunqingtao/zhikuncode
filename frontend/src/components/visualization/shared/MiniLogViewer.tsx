/**
 * MiniLogViewer — 迷你日志查看器
 *
 * 折叠式日志面板，显示工具执行过程中的历史进度消息。
 * 纯 HTML/CSS 实现，不依赖 xterm.js。
 */

import React, { useState, useRef, useEffect, useCallback } from 'react';
import { ChevronRight } from 'lucide-react';

interface MiniLogViewerProps {
    logs: string[];
    defaultCollapsed?: boolean;
}

/** 从 log 条目中提取或生成时间戳 */
function formatTimestamp(index: number): string {
    const now = new Date();
    // 简单回推：假设每条间隔 ~1s（仅做展示用）
    const d = new Date(now.getTime() - (index * 1000));
    return d.toTimeString().slice(0, 8); // HH:MM:SS
}

const MiniLogViewer: React.FC<MiniLogViewerProps> = ({ logs, defaultCollapsed = true }) => {
    const [collapsed, setCollapsed] = useState(defaultCollapsed);
    const scrollRef = useRef<HTMLDivElement>(null);

    const toggle = useCallback(() => setCollapsed(prev => !prev), []);

    // 自动滚动到底部
    useEffect(() => {
        if (!collapsed && scrollRef.current) {
            scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
        }
    }, [logs.length, collapsed]);

    return (
        <div className="mt-1">
            <button
                onClick={toggle}
                className="flex items-center gap-1 text-[10px] text-gray-500 hover:text-gray-300 transition-colors"
            >
                <ChevronRight
                    size={10}
                    className={`transition-transform duration-200 ${collapsed ? '' : 'rotate-90'}`}
                />
                查看详细日志 ({logs.length})
            </button>

            {!collapsed && (
                <div
                    ref={scrollRef}
                    className="mt-1 max-h-[150px] overflow-y-auto rounded bg-slate-900/50 dark:bg-slate-950/50 border border-gray-700/30 p-1.5"
                >
                    {logs.map((log, i) => (
                        <div key={i} className="font-mono text-[10px] leading-4 text-gray-400">
                            <span className="text-gray-600 mr-1.5">{formatTimestamp(logs.length - 1 - i)}</span>
                            {log}
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
};

export default React.memo(MiniLogViewer);
