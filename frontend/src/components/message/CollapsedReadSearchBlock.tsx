/**
 * CollapsedReadSearchBlock — FileRead/GrepTool 结果可折叠展示。
 *
 * SPEC: §8.2.2 结果渲染器
 * 长文件读取和搜索结果默认折叠，显示摘要信息:
 * - FileRead: 文件路径 + 行数
 * - Grep/Glob: 匹配数量 + 文件列表
 */

import React, { useState, useCallback, useMemo } from 'react';
import { ChevronRight, FileText, Search } from 'lucide-react';
import CodeBlock from './CodeBlock';

interface CollapsedReadSearchBlockProps {
    toolName: string;
    content: string;
    /** 是否默认展开 */
    defaultExpanded?: boolean;
    /** 最大折叠预览行数 */
    previewLines?: number;
}

const CollapsedReadSearchBlock: React.FC<CollapsedReadSearchBlockProps> = ({
    toolName,
    content,
    defaultExpanded = false,
    previewLines = 5,
}) => {
    const [expanded, setExpanded] = useState(defaultExpanded);
    const toggle = useCallback(() => setExpanded(prev => !prev), []);

    const lines = useMemo(() => content.split('\n'), [content]);
    const isLong = lines.length > previewLines * 2;

    const Icon = toolName.includes('Grep') || toolName.includes('Glob') ? Search : FileText;
    const label = toolName.includes('Grep') || toolName.includes('Glob')
        ? `${lines.length} lines of search results`
        : `${lines.length} lines`;

    // 短内容直接展示
    if (!isLong) {
        return <CodeBlock code={content} language="text" showLineNumbers={false} maxHeight={400} />;
    }

    const preview = lines.slice(0, previewLines).join('\n');

    return (
        <div className="collapsed-read-search rounded-[14px] border border-hairline bg-sunken2 overflow-hidden">
            {/* Header */}
            <button
                onClick={toggle}
                className="panel-control flex items-center gap-2 w-full px-3 py-1.5 hover:bg-sunken2 transition-colors text-left"
            >
                <ChevronRight
                    size={12}
                    className={`text-t2 transition-transform duration-base ${expanded ? 'rotate-90' : ''}`}
                />
                <Icon size={14} className="text-t2" />
                <span className="text-[13px] text-t2">{label}</span>
                {!expanded && (
                    <span className="text-[13px] text-t2 ml-auto">click to expand</span>
                )}
            </button>

            {/* Content */}
            <div className="px-3 pb-2">
                {expanded ? (
                    <CodeBlock code={content} language="text" showLineNumbers={false} maxHeight={600} />
                ) : (
                    <div className="relative">
                        <CodeBlock code={preview + '\n...'} language="text" showLineNumbers={false} maxHeight={200} />
                        <div className="absolute bottom-0 left-0 right-0 h-8 bg-gradient-to-t from-overlay2 to-transparent pointer-events-none" />
                    </div>
                )}
            </div>
        </div>
    );
};

export default React.memo(CollapsedReadSearchBlock);
