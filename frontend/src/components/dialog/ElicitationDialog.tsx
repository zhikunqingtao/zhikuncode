/**
 * ElicitationDialog — 反向提问对话框
 * SPEC: §8.2.6a.10 ElicitationDialog
 *
 * 用于 AI 向用户提问以澄清需求或获取更多信息
 * - 支持单选、多选、文本输入
 * - 键盘快捷键支持
 */

import React, { useState, useCallback, useEffect, useRef } from 'react';
import { HelpCircle, X } from 'lucide-react';

interface ElicitationOption {
    value: string;
    label: string;
    description?: string;
}

interface ElicitationDialogProps {
    requestId: string;
    question: string;
    options?: ElicitationOption[];
    allowFreeText?: boolean;
    onSubmit: (requestId: string, response: string | string[]) => void;
    onCancel: () => void;
}

export const ElicitationDialog: React.FC<ElicitationDialogProps> = ({
    requestId,
    question,
    options,
    allowFreeText = false,
    onSubmit,
    onCancel,
}) => {
    const [selectedOptions, setSelectedOptions] = useState<string[]>([]);
    const [freeText, setFreeText] = useState('');
    const dialogRef = useRef<HTMLDivElement>(null);

    // Focus trap and escape handler
    useEffect(() => {
        dialogRef.current?.focus();
        
        const handler = (e: KeyboardEvent) => {
            if (e.key === 'Escape') {
                onCancel();
            }
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, [onCancel]);

    const handleOptionToggle = useCallback((value: string) => {
        setSelectedOptions(prev => {
            if (prev.includes(value)) {
                return prev.filter(v => v !== value);
            }
            return [...prev, value];
        });
    }, []);

    const handleSingleSelect = useCallback((value: string) => {
        setSelectedOptions([value]);
    }, []);

    const handleSubmit = useCallback(() => {
        if (options && options.length > 0) {
            // Multi-select or single select
            if (selectedOptions.length > 0) {
                onSubmit(requestId, selectedOptions.length === 1 ? selectedOptions[0] : selectedOptions);
            }
        } else if (allowFreeText && freeText.trim()) {
            onSubmit(requestId, freeText.trim());
        }
    }, [requestId, options, selectedOptions, freeText, allowFreeText, onSubmit]);

    const canSubmit = options 
        ? selectedOptions.length > 0 
        : allowFreeText && freeText.trim().length > 0;

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
            <div
                ref={dialogRef}
                tabIndex={-1}
                role="dialog"
                aria-modal="true"
                className="w-full max-w-md mx-4 rounded-xl border border-[var(--border)] bg-[var(--bg-primary)]
                            shadow-2xl overflow-hidden outline-none"
            >
                {/* Header */}
                <div className="px-5 py-4 border-b border-[var(--border)] flex items-center gap-3">
                    <HelpCircle className="w-5 h-5 text-blue-500" />
                    <div className="flex-1">
                        <h3 className="font-semibold text-[var(--text-primary)]">
                            AI 需要更多信息
                        </h3>
                    </div>
                    <button
                        onClick={onCancel}
                        className="p-1 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)]"
                    >
                        <X className="w-4 h-4" />
                    </button>
                </div>

                {/* Content */}
                <div className="px-5 py-4 space-y-4">
                    <p className="text-[var(--text-secondary)]">{question}</p>

                    {/* Options */}
                    {options && options.length > 0 && (
                        <div className="space-y-2 max-h-60 overflow-y-auto">
                            {options.map((option) => (
                                <button
                                    key={option.value}
                                    onClick={() => 
                                        options.length === 1 || !allowFreeText
                                            ? handleSingleSelect(option.value)
                                            : handleOptionToggle(option.value)
                                    }
                                    className={`w-full px-4 py-3 rounded-lg border text-left transition-all
                                        ${selectedOptions.includes(option.value)
                                            ? 'border-blue-500 bg-blue-500/10'
                                            : 'border-[var(--border)] hover:border-blue-500/50 hover:bg-[var(--bg-hover)]'
                                        }`}
                                >
                                    <div className="font-medium text-[var(--text-primary)]">
                                        {option.label}
                                    </div>
                                    {option.description && (
                                        <div className="text-sm text-[var(--text-muted)] mt-1">
                                            {option.description}
                                        </div>
                                    )}
                                </button>
                            ))}
                        </div>
                    )}

                    {/* Free text input */}
                    {allowFreeText && (!options || options.length === 0) && (
                        <textarea
                            value={freeText}
                            onChange={(e) => setFreeText(e.target.value)}
                            placeholder="请输入您的回答..."
                            className="w-full px-3 py-2 rounded-lg border border-[var(--border)]
                                bg-[var(--bg-secondary)] text-[var(--text-primary)]
                                focus:outline-none focus:ring-2 focus:ring-blue-500
                                resize-none"
                            rows={4}
                            autoFocus
                        />
                    )}
                </div>

                {/* Actions */}
                <div className="px-5 py-4 border-t border-[var(--border)] flex justify-end gap-2">
                    <button
                        onClick={onCancel}
                        className="px-4 py-2 rounded-lg text-sm border border-[var(--border)]
                                    text-[var(--text-secondary)] hover:bg-[var(--bg-hover)] transition-colors"
                    >
                        取消
                    </button>
                    <button
                        onClick={handleSubmit}
                        disabled={!canSubmit}
                        className={`px-4 py-2 rounded-lg text-sm text-white transition-colors
                            ${canSubmit 
                                ? 'bg-blue-600 hover:bg-blue-700' 
                                : 'bg-gray-400 cursor-not-allowed'
                            }`}
                    >
                        确认
                    </button>
                </div>
            </div>
        </div>
    );
};

export default ElicitationDialog;
