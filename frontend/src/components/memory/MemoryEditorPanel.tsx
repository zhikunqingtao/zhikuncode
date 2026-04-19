import React, { useState, useCallback, useEffect } from 'react';
import { Save, Eye, Edit3, FileText, Plus, RefreshCw } from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import DOMPurify from 'dompurify';

interface MemoryEditorProps {
    workingDir: string;
    initialContent: string;
    fileName: 'zhikun.md' | 'zhikun.local.md';
    onSave: (content: string) => Promise<void>;
}

const MEMORY_TEMPLATES: Record<string, string> = {
    '技术栈': '## 技术栈\n- 后端: \n- 前端: \n- 数据库: \n',
    '编码规范': '## 编码规范\n- \n',
    '常见问题': '## 常见问题\n- \n',
    '注意事项': '## 注意事项\n- \n',
};

export const MemoryEditorPanel: React.FC<MemoryEditorProps> = ({
    workingDir: _workingDir, initialContent, fileName, onSave,
}) => {
    const [content, setContent] = useState(initialContent);
    const [isPreview, setIsPreview] = useState(false);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [showTemplateMenu, setShowTemplateMenu] = useState(false);

    useEffect(() => {
        setContent(initialContent);
        setDirty(false);
    }, [initialContent]);

    const handleChange = useCallback((value: string) => {
        setContent(value);
        setDirty(true);
    }, []);

    const handleSave = useCallback(async () => {
        setSaving(true);
        try {
            await onSave(content);
            setDirty(false);
        } finally {
            setSaving(false);
        }
    }, [content, onSave]);

    const insertTemplate = useCallback((template: string) => {
        setContent(prev => prev + '\n\n' + template);
        setDirty(true);
    }, []);

    return (
        <div className="flex flex-col h-full border border-[var(--border)] rounded-lg overflow-hidden">
            {/* Toolbar */}
            <div className="flex items-center justify-between px-3 py-2 border-b border-[var(--border)] bg-[var(--bg-secondary)]">
                <div className="flex items-center gap-2">
                    <FileText size={14} className="text-[var(--text-muted)]" />
                    <span className="text-sm font-medium text-[var(--text-primary)]">{fileName}</span>
                    {dirty && <span className="text-xs text-yellow-400">● 未保存</span>}
                </div>
                <div className="flex items-center gap-1">
                    <div className="relative">
                        <button className="p-1.5 min-h-10 min-w-10 rounded hover:bg-[var(--bg-tertiary)] flex items-center justify-center"
                            title="插入模板" onClick={() => setShowTemplateMenu(!showTemplateMenu)}>
                            <Plus size={14} />
                        </button>
                        {showTemplateMenu && (
                            <div className="absolute right-0 top-full mt-1 bg-[var(--bg-primary)] border border-[var(--border)] rounded-lg shadow-lg z-10 min-w-[140px]">
                                {Object.entries(MEMORY_TEMPLATES).map(([name, tpl]) => (
                                    <button key={name}
                                        onClick={() => { insertTemplate(tpl); setShowTemplateMenu(false); }}
                                        className="block w-full text-left px-3 py-2 min-h-10 text-xs hover:bg-[var(--bg-tertiary)]">
                                        {name}
                                    </button>
                                ))}
                            </div>
                        )}
                    </div>
                    <button onClick={() => setIsPreview(!isPreview)}
                        className="p-1.5 min-h-10 min-w-10 rounded hover:bg-[var(--bg-tertiary)] flex items-center justify-center"
                        title={isPreview ? '编辑模式' : '预览模式'}>
                        {isPreview ? <Edit3 size={14} /> : <Eye size={14} />}
                    </button>
                    <button onClick={handleSave} disabled={!dirty || saving}
                        className="flex items-center gap-1 px-2 py-1 min-h-10 rounded text-xs bg-blue-600 hover:bg-blue-700 disabled:opacity-50">
                        {saving ? <RefreshCw size={12} className="animate-spin" /> : <Save size={12} />}
                        保存
                    </button>
                </div>
            </div>

            {/* Editor / Preview */}
            <div className="flex-1 overflow-auto">
                {isPreview ? (
                    <div className="p-4 prose prose-invert prose-sm max-w-none">
                        <ReactMarkdown>{DOMPurify.sanitize(content)}</ReactMarkdown>
                    </div>
                ) : (
                    <textarea
                        value={content}
                        onChange={e => handleChange(e.target.value)}
                        className="w-full h-full p-4 bg-transparent text-sm font-mono text-[var(--text-primary)] resize-none focus:outline-none"
                        placeholder="# 项目记忆&#10;&#10;在此输入项目记忆内容..."
                        spellCheck={false}
                    />
                )}
            </div>
        </div>
    );
};
