import React, { useEffect, useState } from 'react';
import { X, Zap, Play } from 'lucide-react';

interface SkillDetail {
    name: string;
    description: string;
    source: string;
    content: string;
    filePath: string;
}

export const SkillDetailModal: React.FC<{
    skillName: string;
    onClose: () => void;
    onExecute: (name: string, userInput: string) => void;
}> = ({ skillName, onClose, onExecute }) => {
    const [detail, setDetail] = useState<SkillDetail | null>(null);
    const [userInput, setUserInput] = useState('');

    useEffect(() => {
        fetch(`/api/skills/${skillName}`)
            .then(r => r.json())
            .then(setDetail)
            .catch(() => {});
    }, [skillName]);

    if (!detail) return null;

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={onClose}>
            <div className="bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl shadow-2xl
                            w-full max-w-lg mx-4 max-h-[70vh] overflow-hidden flex flex-col"
                 onClick={e => e.stopPropagation()}>
                {/* Header */}
                <div className="p-4 border-b border-[var(--border)] flex items-center justify-between">
                    <div className="flex items-center gap-2">
                        <Zap size={18} className="text-yellow-400" />
                        <h3 className="text-sm font-semibold text-[var(--text-primary)]">{detail.name}</h3>
                        <span className="text-xs px-1.5 py-0.5 rounded bg-gray-700 text-gray-400">{detail.source}</span>
                    </div>
                    <button onClick={onClose} className="p-1 hover:bg-[var(--bg-tertiary)] rounded">
                        <X size={16} />
                    </button>
                </div>
                {/* Body */}
                <div className="flex-1 overflow-y-auto p-4 space-y-3">
                    <p className="text-sm text-[var(--text-secondary)]">{detail.description}</p>
                    <div className="text-xs text-[var(--text-muted)]">文件: {detail.filePath}</div>
                    <pre className="text-xs font-mono bg-[var(--bg-tertiary)] p-3 rounded-lg overflow-x-auto
                                    text-[var(--text-secondary)]">
                        {detail.content}
                    </pre>
                </div>
                {/* User Input */}
                <div className="px-4 pb-3">
                    <label className="text-xs text-[var(--text-muted)] mb-1 block">补充说明（可选）</label>
                    <textarea
                        value={userInput}
                        onChange={e => setUserInput(e.target.value)}
                        placeholder="输入你希望 AI 处理的内容或补充说明..."
                        className="w-full h-20 text-sm bg-[var(--bg-tertiary)] border border-[var(--border)] rounded-lg p-2
                                   text-[var(--text-primary)] placeholder-[var(--text-muted)] resize-none
                                   focus:outline-none focus:ring-1 focus:ring-blue-500"
                        autoFocus
                    />
                </div>
                {/* Footer */}
                <div className="p-3 border-t border-[var(--border)] flex justify-end">
                    <button onClick={() => onExecute(detail.name, userInput.trim())}
                            className="flex items-center gap-1 px-3 py-1.5 text-xs font-medium rounded-lg
                                       bg-blue-600 hover:bg-blue-500 text-white transition-colors">
                        <Play size={12} />
                        执行技能
                    </button>
                </div>
            </div>
        </div>
    );
};
