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
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        fetch(`/api/skills/${skillName}`)
            .then(r => {
                if (!r.ok) {
                    if (r.status === 404) {
                        setError(`Skill "${skillName}" not found`);
                    } else {
                        setError(`Request failed: ${r.status}`);
                    }
                    return null;
                }
                return r.json();
            })
            .then(data => { if (data) setDetail(data); })
            .catch(() => setError('Network error'));
    }, [skillName]);

    if (error) return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-overlay2 backdrop-blur-[3px]" onClick={onClose}>
            <div className="bg-surfacev2 border border-hairline rounded-panel shadow-e4 motion-safe:animate-scale-in
                            w-full max-w-lg mx-4 p-6" onClick={e => e.stopPropagation()}>
                <p className="text-err text-sm">{error}</p>
                <button onClick={onClose} className="dialog-control mt-3 text-[13px] text-[var(--v2-text-2)] hover:text-[var(--v2-text-2)]">
                    Close
                </button>
            </div>
        </div>
    );
    if (!detail) return null;

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-overlay2 backdrop-blur-[3px]" onClick={onClose}>
            <div className="bg-surfacev2 border border-hairline rounded-panel shadow-e4 motion-safe:animate-scale-in
                            w-full max-w-lg mx-4 max-h-[70vh] overflow-hidden flex flex-col"
                 onClick={e => e.stopPropagation()}>
                {/* Header */}
                <div className="p-4 border-b border-[var(--v2-border-hairline)] flex items-center justify-between">
                    <div className="flex items-center gap-2">
                        <Zap size={18} className="text-warn" />
                        <h3 className="text-[var(--v2-text-1)] text-base font-semibold">{detail.name}</h3>
                        <span className="text-[13px] px-1.5 py-0.5 rounded bg-gray-700 text-t2">{detail.source}</span>
                    </div>
                    <button onClick={onClose} className="dialog-control p-1 hover:bg-[var(--bg-tertiary)] rounded">
                        <X size={16} />
                    </button>
                </div>
                {/* Body */}
                <div className="flex-1 overflow-y-auto p-4 space-y-3">
                    <p className="text-sm text-[var(--v2-text-2)]">{detail.description}</p>
                    <div className="text-[13px] text-[var(--v2-text-2)]">文件: {detail.filePath}</div>
                    <pre className="text-[13px] font-mono bg-[var(--bg-tertiary)] p-3 rounded-[10px] overflow-x-auto
                                    text-[var(--v2-text-2)]">
                        {detail.content}
                    </pre>
                </div>
                {/* User Input */}
                <div className="px-4 pb-3">
                    <label className="text-[13px] text-[var(--v2-text-2)] mb-1 block">补充说明（可选）</label>
                    <textarea
                        value={userInput}
                        onChange={e => setUserInput(e.target.value)}
                        placeholder="输入你希望 AI 处理的内容或补充说明..."
                        className="w-full h-20 text-sm bg-[var(--bg-tertiary)] border border-[var(--v2-border-hairline)] rounded-[10px] p-2
                                   text-[var(--v2-text-1)] placeholder-[var(--v2-text-2)] resize-none
                                   focus:outline-none focus:ring-1 focus:ring-accent2-ring"
                        autoFocus
                    />
                </div>
                {/* Footer */}
                <div className="p-3 border-t border-[var(--v2-border-hairline)] flex justify-end">
                    <button onClick={() => onExecute(detail.name, userInput.trim())}
                            className="dialog-control flex items-center gap-1 px-3 py-1.5 text-[13px] font-medium rounded-[10px]
                                       bg-accent2-strong hover:bg-accent2 text-white transition-colors">
                        <Play size={12} />
                        执行技能
                    </button>
                </div>
            </div>
        </div>
    );
};
