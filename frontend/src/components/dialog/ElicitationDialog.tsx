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
    inputType?: 'select' | 'text' | 'confirm' | 'multiselect' | 'number';
    allowFreeText?: boolean;
    timeout?: number;
    placeholder?: string;
    validation?: {
        format?: 'email' | 'uri' | 'date';
        minLength?: number;
        maxLength?: number;
        min?: number;
        max?: number;
    };
    onSubmit: (requestId: string, response: string | string[]) => void;
    onCancel: () => void;
}

export const ElicitationDialog: React.FC<ElicitationDialogProps> = ({
    requestId,
    question,
    options,
    inputType = 'select',
    allowFreeText = false,
    timeout = 120000,
    placeholder,
    validation,
    onSubmit,
    onCancel,
}) => {
    const [selectedOptions, setSelectedOptions] = useState<string[]>([]);
    const [freeText, setFreeText] = useState('');
    const [error, setError] = useState<string | null>(null);
    const [remaining, setRemaining] = useState(Math.ceil(timeout / 1000));
    const dialogRef = useRef<HTMLDivElement>(null);
    const timerRef = useRef<ReturnType<typeof setInterval>>();
    const onCancelRef = useRef(onCancel);
    onCancelRef.current = onCancel;

    // Focus trap, escape handler, and timeout countdown
    useEffect(() => {
        dialogRef.current?.focus();
        
        const handler = (e: KeyboardEvent) => {
            if (e.key === 'Escape') {
                onCancel();
            }
        };
        window.addEventListener('keydown', handler);

        // Timeout countdown
        timerRef.current = setInterval(() => {
            setRemaining(prev => {
                if (prev <= 1) {
                    clearInterval(timerRef.current);
                    onCancelRef.current();
                    return 0;
                }
                return prev - 1;
            });
        }, 1000);

        return () => {
            window.removeEventListener('keydown', handler);
            clearInterval(timerRef.current);
        };
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
        // Validation
        if (inputType === 'text' || (allowFreeText && (!options || options.length === 0))) {
            if (!freeText.trim()) { setError('请输入内容'); return; }
            if (validation?.minLength && freeText.length < validation.minLength) {
                setError(`最少 ${validation.minLength} 个字符`); return;
            }
            if (validation?.format === 'email' && !/^\S+@\S+\.\S+$/.test(freeText)) {
                setError('请输入有效的邮箱地址'); return;
            }
        }
        if (inputType === 'number') {
            const num = parseFloat(freeText);
            if (isNaN(num)) { setError('请输入有效数字'); return; }
            if (validation?.min !== undefined && num < validation.min) {
                setError(`最小值: ${validation.min}`); return;
            }
            if (validation?.max !== undefined && num > validation.max) {
                setError(`最大值: ${validation.max}`); return;
            }
        }
        if ((inputType === 'select' || inputType === 'multiselect') && selectedOptions.length === 0) {
            setError('请选择一个选项'); return;
        }
        setError(null);

        // Build response based on inputType
        if (inputType === 'confirm') {
            onSubmit(requestId, selectedOptions[0] || 'yes');
        } else if (inputType === 'multiselect') {
            onSubmit(requestId, selectedOptions);
        } else if (options && options.length > 0) {
            if (selectedOptions.length > 0) {
                onSubmit(requestId, selectedOptions.length === 1 ? selectedOptions[0] : selectedOptions);
            }
        } else if (allowFreeText && freeText.trim()) {
            onSubmit(requestId, freeText.trim());
        } else if (inputType === 'text' || inputType === 'number') {
            onSubmit(requestId, freeText.trim());
        }
    }, [requestId, options, selectedOptions, freeText, allowFreeText, onSubmit, inputType, validation]);

    const canSubmit = (inputType === 'select' || inputType === 'multiselect')
        ? selectedOptions.length > 0 
        : (inputType === 'confirm')
        ? true
        : (inputType === 'text' || inputType === 'number' || allowFreeText)
        ? freeText.trim().length > 0
        : options 
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
                        <p className="text-xs text-[var(--text-muted)] mt-0.5">⏱ {remaining}s 后自动取消</p>
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

                    {/* Options (select / multiselect) */}
                    {(inputType === 'select' || inputType === 'multiselect' || (options && options.length > 0)) && options && options.length > 0 && (
                        <div className="space-y-2 max-h-60 overflow-y-auto">
                            {options.map((option) => (
                                <button
                                    key={option.value}
                                    onClick={() => 
                                        inputType === 'multiselect'
                                            ? handleOptionToggle(option.value)
                                            : handleSingleSelect(option.value)
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

                    {/* Confirm type */}
                    {inputType === 'confirm' && (
                        <div className="flex gap-3">
                            <button
                                onClick={() => { setSelectedOptions(['yes']); onSubmit(requestId, 'yes'); }}
                                className="flex-1 px-4 py-3 rounded-lg border border-green-500 bg-green-500/10
                                    text-[var(--text-primary)] hover:bg-green-500/20 transition-colors"
                            >是</button>
                            <button
                                onClick={() => { setSelectedOptions(['no']); onSubmit(requestId, 'no'); }}
                                className="flex-1 px-4 py-3 rounded-lg border border-red-500 bg-red-500/10
                                    text-[var(--text-primary)] hover:bg-red-500/20 transition-colors"
                            >否</button>
                        </div>
                    )}

                    {/* Free text / number input */}
                    {(inputType === 'text' || inputType === 'number') && (
                        <input
                            type={inputType === 'number' ? 'number' : 'text'}
                            value={freeText}
                            onChange={(e) => { setFreeText(e.target.value); setError(null); }}
                            onKeyDown={(e) => e.key === 'Enter' && handleSubmit()}
                            placeholder={placeholder || '请输入...'}
                            className="w-full px-3 py-2 rounded-lg border border-[var(--border)]
                                bg-[var(--bg-secondary)] text-[var(--text-primary)]
                                focus:outline-none focus:ring-2 focus:ring-blue-500"
                            autoFocus
                        />
                    )}

                    {/* Free text (textarea) when no options and allowFreeText */}
                    {allowFreeText && inputType !== 'text' && inputType !== 'number' && (!options || options.length === 0) && (
                        <textarea
                            value={freeText}
                            onChange={(e) => { setFreeText(e.target.value); setError(null); }}
                            placeholder="请输入您的回答..."
                            className="w-full px-3 py-2 rounded-lg border border-[var(--border)]
                                bg-[var(--bg-secondary)] text-[var(--text-primary)]
                                focus:outline-none focus:ring-2 focus:ring-blue-500
                                resize-none"
                            rows={4}
                            autoFocus
                        />
                    )}
                    {error && <p className="text-sm text-red-500 mt-2">{error}</p>}
                </div>

                {/* Actions */}
                {inputType !== 'confirm' && (
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
                )}
            </div>
        </div>
    );
};

export default ElicitationDialog;
