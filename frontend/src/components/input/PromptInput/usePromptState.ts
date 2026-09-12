/**
 * usePromptState — PromptInput 输入状态 / 草稿 / 快捷键 / 提交逻辑
 *
 * §8.3.1 PromptInput 拆分：从原 PromptInput.tsx 纯搬运（零行为变化）。
 * 图片附件与本地文件引用逻辑分别见 usePromptAttachments.ts /
 * useLocalFileReference.ts，本 Hook 组合其结果并对外统一暴露。
 */

import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from 'react';
import type {
    Attachment,
    FileReferenceCapability,
    PastePublishResult,
    PublishedLocalFile,
    SubmitEvent,
} from '@/types';
import { useNotificationStore } from '@/store/notificationStore';
import { useAsrAvailability } from '@/hooks/useAsrAvailability';
import { usePromptAttachments } from './usePromptAttachments';
import { useLocalFileReference } from './useLocalFileReference';

export interface UsePromptStateParams {
    sessionId?: string | null;
    onSubmit: (event: SubmitEvent) => Promise<boolean>;
    onSlashCommand: (command: string) => Promise<boolean>;
    onInterrupt: () => void;
    disabled: boolean;
    runActive: boolean;
    compacting: boolean;
    onPasteImages: (files: File[]) => Promise<PastePublishResult>;
    onPublishLocalFile: (file: File) => Promise<PublishedLocalFile>;
    fileReferenceCapability: FileReferenceCapability | null;
}

export function usePromptState({
    sessionId,
    onSubmit,
    onSlashCommand,
    onInterrupt,
    disabled,
    runActive,
    compacting,
    onPasteImages,
    onPublishLocalFile,
    fileReferenceCapability,
}: UsePromptStateParams) {
    const [input, setInput] = useState('');
    const [showCommands, setShowCommands] = useState(false);
    const [showGlobalPalette, setShowGlobalPalette] = useState(false);
    const [showFileComplete, setShowFileComplete] = useState(false);
    const [fileQuery, setFileQuery] = useState('');
    const [historyIndex, setHistoryIndex] = useState(-1);
    const [isSubmitting, setIsSubmitting] = useState(false);
    const textareaRef = useRef<HTMLTextAreaElement>(null);
    const historyRef = useRef<string[]>([]);
    const submissionRef = useRef(false);
    // 追踪光标位置，用于语音识别结果插入光标处而非追加末尾
    const cursorPosRef = useRef<number | null>(null);

    const promptAttachments = usePromptAttachments({
        runActive,
        compacting,
        onPasteImages,
    });
    const localFileReference = useLocalFileReference({
        sessionId,
        disabled,
        runActive,
        compacting,
        isSubmitting,
        isUploadingPaste: promptAttachments.isUploadingPaste,
        onPublishLocalFile,
        fileReferenceCapability,
    });
    const { attachments, setAttachments } = promptAttachments;
    const {
        isPickingLocalFile,
        isUploadingLocalFile,
        localFiles,
        setLocalFiles,
        publishedLocalFiles,
        setPublishedLocalFiles,
    } = localFileReference;

    // Global Ctrl+K listener
    useEffect(() => {
        const handler = (e: globalThis.KeyboardEvent) => {
            if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
                e.preventDefault();
                if (runActive || compacting) {
                    setShowGlobalPalette(false);
                    return;
                }
                setShowGlobalPalette(prev => !prev);
            }
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, [runActive, compacting]);

    useEffect(() => {
        if (runActive || compacting) {
            setShowCommands(false);
            setShowGlobalPalette(false);
        }
    }, [runActive, compacting]);

    const submitSlashCommand = useCallback(async (
        command: string,
        clearDraft = true,
    ) => {
        if (submissionRef.current) return false;
        submissionRef.current = true;
        setIsSubmitting(true);
        try {
            const accepted = await onSlashCommand(command);
            if (!accepted) return false;
            if (clearDraft) setInput('');
            setShowCommands(false);
            setShowGlobalPalette(false);
            return true;
        } catch {
            return false;
        } finally {
            submissionRef.current = false;
            setIsSubmitting(false);
        }
    }, [onSlashCommand]);

    const handleSubmit = useCallback(async () => {
        const trimmed = input.trim();
        if ((!trimmed && attachments.length === 0 && localFiles.length === 0
                && publishedLocalFiles.length === 0)
                || submissionRef.current || isPickingLocalFile
                || isUploadingLocalFile) return;

        if (compacting) return;

        if (runActive && (attachments.length > 0 || localFiles.length > 0
                || publishedLocalFiles.length > 0)) {
            useNotificationStore.getState().addNotification({
                key: 'run-input-attachments',
                level: 'warning',
                message: '运行中干预暂不支持附件，请先移除附件',
                timeout: 5000,
            });
            return;
        }
        if (runActive && !trimmed) return;

        if (!runActive && localFiles.length === 0
                && publishedLocalFiles.length === 0
                && trimmed.startsWith('/')) {
            await submitSlashCommand(trimmed);
            return;
        }
        const submitAttachments: Attachment[] = attachments.map(a => ({
            type: a.type.startsWith('image/') ? 'image' as const : 'file' as const,
            name: a.name,
            base64Data: a.base64Content ?? '',
            mediaType: a.type,
            url: a.remoteUrl,
        }));
        const localPathText = localFiles
            .map(file => `本地文件路径：${JSON.stringify(file.path)}`)
            .join('\n');
        const publishedFileText = publishedLocalFiles
            .map(file => `本地文件 OSS 引用：${JSON.stringify({
                name: file.name,
                url: file.url,
            })}`)
            .join('\n');
        const submittedText = [trimmed, localPathText, publishedFileText]
            .filter(Boolean)
            .join('\n');
        submissionRef.current = true;
        setIsSubmitting(true);
        try {
            const sent = await onSubmit({
                text: submittedText,
                attachments: submitAttachments,
                references: new Map(),
                isFastMode: false,
            });
            if (!sent) return;

            historyRef.current.push(trimmed);
            setHistoryIndex(-1);
            attachments.forEach(a => {
                if (a.previewUrl) URL.revokeObjectURL(a.previewUrl);
            });
            setInput('');
            setAttachments([]);
            setLocalFiles([]);
            setPublishedLocalFiles([]);
        } finally {
            submissionRef.current = false;
            setIsSubmitting(false);
        }
    }, [
        input,
        attachments,
        localFiles,
        publishedLocalFiles,
        isPickingLocalFile,
        isUploadingLocalFile,
        onSubmit,
        submitSlashCommand,
        runActive,
        compacting,
        setAttachments,
        setLocalFiles,
        setPublishedLocalFiles,
    ]);

    // Keyboard event handling
    const handleKeyDown = useCallback((e: KeyboardEvent<HTMLTextAreaElement>) => {
        // IME composition protection (v1.49.0 F4-03)
        if (e.nativeEvent.isComposing || e.keyCode === 229) {
            return;
        }

        // Ctrl+C → interrupt (only when no text selected, v1.44.0)
        if (e.ctrlKey && e.key === 'c' && runActive && !window.getSelection()?.toString()) {
            onInterrupt();
            e.preventDefault();
            return;
        }

        // Enter (no Shift) → submit
        if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey) {
            e.preventDefault();
            if (input.trim() || attachments.length > 0 || localFiles.length > 0
                    || publishedLocalFiles.length > 0) {
                void handleSubmit();
            }
            return;
        }

        // / at empty input → show command palette
        if (e.key === '/' && input === '' && !runActive && !compacting) {
            setShowCommands(true);
        }

        // Escape → close palette / clear input
        if (e.key === 'Escape') {
            if (showCommands) setShowCommands(false);
            else if (showGlobalPalette) setShowGlobalPalette(false);
            else setInput('');
        }

        // ArrowUp (cursor at start) → history navigation
        if (e.key === 'ArrowUp' && textareaRef.current?.selectionStart === 0) {
            e.preventDefault();
            const history = historyRef.current;
            if (history.length > 0 && historyIndex < history.length - 1) {
                const newIdx = historyIndex + 1;
                setHistoryIndex(newIdx);
                setInput(history[history.length - 1 - newIdx]);
            }
        }

        // ArrowDown → reverse history navigation
        if (e.key === 'ArrowDown' && historyIndex >= 0) {
            e.preventDefault();
            const newIdx = historyIndex - 1;
            setHistoryIndex(newIdx);
            const history = historyRef.current;
            setInput(newIdx >= 0 ? history[history.length - 1 - newIdx] : '');
        }

        // Tab → auto-complete (when command palette is open)
        if (e.key === 'Tab' && showCommands) {
            e.preventDefault();
        }
    }, [input, attachments.length, localFiles.length, publishedLocalFiles.length,
        runActive, compacting, showCommands, showGlobalPalette, historyIndex,
        onInterrupt, handleSubmit]);

    const asrAvailable = useAsrAvailability();

    // @ 文件补全选择（原 FileAutoComplete onSelect 内联逻辑搬运）
    const handleFileCompleteSelect = useCallback((filePath: string) => {
        const cursor = textareaRef.current?.selectionStart || 0;
        const textBeforeCursor = input.slice(0, cursor);
        const atStart = textBeforeCursor.lastIndexOf('@');
        if (atStart >= 0) {
            const newText = input.slice(0, atStart) + '@' + filePath + ' ' + input.slice(cursor);
            setInput(newText);
        }
        setShowFileComplete(false);
    }, [input]);

    // 语音识别结果插入光标处（原 VoiceInputButton onTranscript 内联逻辑搬运）
    const handleVoiceTranscript = useCallback((text: string) => {
        setInput(prev => {
            const pos = cursorPosRef.current;
            if (pos !== null && pos >= 0 && pos <= prev.length) {
                const newText = prev.slice(0, pos) + text + prev.slice(pos);
                const newCursorPos = pos + text.length;
                cursorPosRef.current = newCursorPos;
                requestAnimationFrame(() => {
                    if (textareaRef.current) {
                        textareaRef.current.selectionStart = newCursorPos;
                        textareaRef.current.selectionEnd = newCursorPos;
                        textareaRef.current.focus();
                    }
                });
                return newText;
            }
            cursorPosRef.current = prev.length + text.length;
            return prev + text;
        });
    }, []);

    // textarea 光标位置同步（onChange/onSelect/onBlur 共用）
    const syncCursorPos = useCallback((pos: number | null) => {
        cursorPosRef.current = pos;
    }, []);

    // @ 文件补全触发（PromptTextarea onChange 内调用）
    const handleAtQueryChange = useCallback((query: string | null) => {
        if (query === null) {
            setShowFileComplete(false);
        } else {
            setFileQuery(query);
            setShowFileComplete(true);
        }
    }, []);

    // / 命令触发（PromptTextarea onChange 内调用）
    const handleSlashIntent = useCallback((startsWithSlash: boolean) => {
        if (!runActive && !compacting && startsWithSlash) setShowCommands(true);
        else setShowCommands(false);
    }, [runActive, compacting]);

    return {
        input,
        setInput,
        showCommands,
        setShowCommands,
        showGlobalPalette,
        setShowGlobalPalette,
        showFileComplete,
        setShowFileComplete,
        fileQuery,
        isSubmitting,
        textareaRef,
        asrAvailable,
        submitSlashCommand,
        handleSubmit,
        handleKeyDown,
        handleFileCompleteSelect,
        handleVoiceTranscript,
        syncCursorPos,
        handleAtQueryChange,
        handleSlashIntent,
        promptAttachments,
        localFileReference,
    };
}
