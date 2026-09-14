/**
 * usePromptState — PromptInput 输入状态 / 草稿 / 快捷键 / 提交逻辑
 *
 * §8.3.1 PromptInput 拆分：从原 PromptInput.tsx 纯搬运（零行为变化）。
 * 图片附件与本地文件引用逻辑分别见 usePromptAttachments.ts /
 * useLocalFileReference.ts，本 Hook 组合其结果并对外统一暴露。
 *
 * 草稿持久化（P1 修复）：输入文本与图片附件按活动 sessionId 键控托管到
 * promptDraftStore（仅内存）。移动端底部导航切换会卸载整个聊天树，
 * 组件内 useState 会随之销毁；store 化后卸载/重挂载草稿可恢复，
 * 且不同会话的草稿相互隔离。
 */

import {
    useCallback,
    useEffect,
    useRef,
    useState,
    type Dispatch,
    type KeyboardEvent,
    type SetStateAction,
} from 'react';
import type {
    Attachment,
    FileReferenceCapability,
    PastePublishResult,
    PublishedLocalFile,
    SubmitEvent,
} from '@/types';
import { useNotificationStore } from '@/store/notificationStore';
import {
    capturePromptDraftTarget,
    usePromptDraftStore,
} from '@/store/promptDraftStore';
import { useAsrAvailability } from '@/hooks/useAsrAvailability';
import { usePromptAttachments } from './usePromptAttachments';
import { useLocalFileReference } from './useLocalFileReference';
import { usePromptDraftKey } from './usePromptDraftKey';

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
    // 草稿文本托管到 promptDraftStore（按活动 sessionId 键控，仅内存）：
    // 卸载/重挂载（移动端底部导航切换）后从 store 读回；无会话时回落稳定兜底键。
    const draftKey = usePromptDraftKey(sessionId);
    const input = usePromptDraftStore(s => s.drafts[draftKey]?.input ?? '');
    const setInput = useCallback<Dispatch<SetStateAction<string>>>((value) => {
        usePromptDraftStore.getState().setInput(draftKey, value);
    }, [draftKey]);
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
        sessionId,
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
    const { attachments } = promptAttachments;
    const {
        isPickingLocalFile,
        isUploadingLocalFile,
        localFiles,
        publishedLocalFiles,
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
        const resolveTarget = capturePromptDraftTarget(draftKey);
        try {
            const accepted = await onSlashCommand(command);
            if (!accepted) return false;
            const targetKey = resolveTarget();
            if (clearDraft && targetKey !== undefined) {
                usePromptDraftStore.getState().setInput(targetKey, current => current === input ? '' : current);
            }
            setShowCommands(false);
            setShowGlobalPalette(false);
            return true;
        } catch {
            return false;
        } finally {
            submissionRef.current = false;
            setIsSubmitting(false);
        }
    }, [onSlashCommand, draftKey, input]);

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
        const resolveTarget = capturePromptDraftTarget(draftKey);
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
            const targetKey = resolveTarget();
            if (targetKey !== undefined) {
                const drafts = usePromptDraftStore.getState();
                // 从当前 store 快照按对象身份移除已提交项，保留重挂载后的编辑和新增引用。
                const current = drafts.drafts[targetKey];
                drafts.setInput(targetKey, current.input === input ? '' : current.input);
                drafts.setAttachments(targetKey, current.attachments.filter(item => !attachments.includes(item)));
                drafts.setLocalFiles(targetKey, current.localFiles.filter(item => !localFiles.includes(item)));
                drafts.setPublishedLocalFiles(targetKey, current.publishedLocalFiles.filter(item => !publishedLocalFiles.includes(item)));
            }
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
        draftKey,
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
        onInterrupt, handleSubmit, setInput]);

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
    }, [input, setInput]);

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
    }, [setInput]);

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
