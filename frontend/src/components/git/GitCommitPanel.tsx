import React, { useState } from 'react';
import { GitCommit, Send, FileText, RefreshCw, Sparkles } from 'lucide-react';

interface GitCommitData {
    status: string;
    stagedDiff: string;
    detailedDiff: string;
    changedFiles: string[];
    fileCount: number;
}

export const GitCommitPanel: React.FC<{
    data: GitCommitData;
    onCommit: (message: string) => void;
    onGenerateMessage?: () => Promise<string>;
}> = ({ data, onCommit, onGenerateMessage }) => {
    const [message, setMessage] = useState('');
    const [generating, setGenerating] = useState(false);

    const handleGenerate = async () => {
        if (!onGenerateMessage) return;
        setGenerating(true);
        try {
            const generated = await onGenerateMessage();
            setMessage(generated);
        } finally {
            setGenerating(false);
        }
    };

    return (
        <div className="rounded-[14px] border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-sunken)] p-4 space-y-3">
            <div className="flex items-center gap-2">
                <GitCommit size={16} className="text-warnstrong dark:text-warn" />
                <span className="font-semibold text-base text-[var(--v2-text-1)]">Git 提交</span>
                <span className="text-[13px] text-[var(--v2-text-2)]">{data.fileCount} 个文件变更</span>
            </div>

            <div className="max-h-32 overflow-y-auto space-y-0.5">
                {data.changedFiles.map(file => (
                    <div key={file} className="flex items-center gap-2 text-[13px] text-[var(--v2-text-2)]">
                        <FileText size={10} />
                        <span className="font-mono">{file}</span>
                    </div>
                ))}
            </div>

            {data.stagedDiff && (
                <pre className="panel-code text-[13px] font-mono text-[var(--v2-text-2)] bg-sunken2 rounded p-2 max-h-24 overflow-auto">
                    {data.stagedDiff}
                </pre>
            )}

            <div className="space-y-2">
                <textarea
                    value={message}
                    onChange={e => setMessage(e.target.value)}
                    placeholder="输入 commit message（或点击 AI 生成）..."
                    className="w-full h-20 p-2 text-sm bg-[var(--v2-bg-surface)] border border-[var(--v2-border-hairline)] rounded-md text-[var(--v2-text-1)] resize-none focus:outline-none focus:border-accent2"
                />
                <div className="flex items-center gap-2">
                    {onGenerateMessage && (
                        <button
                            onClick={handleGenerate}
                            disabled={generating}
                            className="panel-control flex items-center gap-1 px-3 py-1.5 rounded-md text-[13px] bg-accent2-strong hover:bg-accent2-hover disabled:opacity-50 text-white"
                        >
                            {generating ? <RefreshCw size={12} className="animate-spin" /> : <Sparkles size={12} />}
                            AI 生成 Message
                        </button>
                    )}
                    <button
                        onClick={() => onCommit(message)}
                        disabled={!message.trim()}
                        className="panel-control flex items-center gap-1 px-3 py-1.5 rounded-md text-[13px] bg-accent2-strong hover:bg-accent2-hover disabled:opacity-50 text-white"
                    >
                        <Send size={12} />
                        提交
                    </button>
                </div>
            </div>
        </div>
    );
};
