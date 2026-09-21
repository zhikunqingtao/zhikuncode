import React, { useState, useCallback } from 'react';
import { Eye, Edit3, FileText, Plus } from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import DOMPurify from 'dompurify';

/**
 * MemoryEditorPanel — 整篇 Markdown 编辑器（受控组件）。
 *
 * 仅负责编辑/预览/模板插入；保存由外层页面统一触发（MemoryPage 顶栏保存按钮）。
 */
interface MemoryEditorPanelProps {
    content: string;
    onChange: (value: string) => void;
    fileName?: string;
    dirty?: boolean;
}

const MEMORY_TEMPLATES: Record<string, string> = {
    '技术栈': '## 技术栈\n- 后端: \n- 前端: \n- 数据库: \n',
    '编码规范': '## 编码规范\n- \n',
    '常见问题': '## 常见问题\n- \n',
    '注意事项': '## 注意事项\n- \n',
};

export const MemoryEditorPanel: React.FC<MemoryEditorPanelProps> = ({
    content, onChange, fileName = 'memory.md', dirty = false,
}) => {
    const [isPreview, setIsPreview] = useState(false);
    const [showTemplateMenu, setShowTemplateMenu] = useState(false);

    const insertTemplate = useCallback((template: string) => {
        onChange(content + '\n\n' + template);
    }, [content, onChange]);

    return (
        <div className="flex flex-col h-full border border-[var(--v2-border-hairline)] rounded-[14px] overflow-hidden">
            {/* Toolbar */}
            <div className="flex items-center justify-between px-3 py-2 border-b border-[var(--v2-border-hairline)] bg-[var(--v2-bg-sunken)]">
                <div className="flex items-center gap-2">
                    <FileText size={14} className="text-[var(--v2-text-2)]" />
                    <span className="text-sm font-medium text-[var(--v2-text-1)]">{fileName}</span>
                    {dirty && <span className="text-[13px] text-warn">● 未保存</span>}
                </div>
                <div className="flex items-center gap-1">
                    <div className="relative">
                        <button className="panel-control p-1.5 min-h-10 min-w-10 rounded hover:bg-[var(--bg-tertiary)] flex items-center justify-center"
                            title="插入模板" aria-label="插入模板" onClick={() => setShowTemplateMenu(!showTemplateMenu)}>
                            <Plus size={14} />
                        </button>
                        {showTemplateMenu && (
                            <div className="absolute right-0 top-full mt-1 bg-[var(--v2-bg-surface)] border border-[var(--v2-border-hairline)] rounded-[10px] shadow-e3 z-10 min-w-[140px]">
                                {Object.entries(MEMORY_TEMPLATES).map(([name, tpl]) => (
                                    <button key={name}
                                        onClick={() => { insertTemplate(tpl); setShowTemplateMenu(false); }}
                                        className="panel-control block w-full text-left px-3 py-2 min-h-10 text-[13px] hover:bg-[var(--bg-tertiary)]">
                                        {name}
                                    </button>
                                ))}
                            </div>
                        )}
                    </div>
                    <button onClick={() => setIsPreview(!isPreview)}
                        className="panel-control p-1.5 min-h-10 min-w-10 rounded hover:bg-[var(--bg-tertiary)] flex items-center justify-center"
                        title={isPreview ? '编辑模式' : '预览模式'}
                        aria-label={isPreview ? '切换到编辑模式' : '切换到预览模式'}>
                        {isPreview ? <Edit3 size={14} /> : <Eye size={14} />}
                    </button>
                </div>
            </div>

            {/* Editor / Preview */}
            <div className="flex-1 overflow-auto">
                {isPreview ? (
                    <div className="p-4 prose prose-invert prose-sm max-w-none">
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>{DOMPurify.sanitize(content)}</ReactMarkdown>
                    </div>
                ) : (
                    <textarea
                        value={content}
                        onChange={e => onChange(e.target.value)}
                        className="w-full h-full p-4 bg-transparent text-sm font-mono text-[var(--v2-text-1)] resize-none focus:outline-none"
                        placeholder="# 项目记忆&#10;&#10;在此输入项目记忆内容..."
                        spellCheck={false}
                        aria-label="记忆文件内容"
                    />
                )}
            </div>
        </div>
    );
};
