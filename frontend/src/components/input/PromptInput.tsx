/**
 * PromptInput — 用户输入组件
 *
 * SPEC: §8.2.6a.7 PromptInput 完整交互实现
 * 核心功能:
 * 1. 多行输入: Shift+Enter 换行, Enter 提交
 * 2. 命令面板: / 触发自动完成, Ctrl+K 打开全局命令面板
 * 3. 历史导航: ArrowUp/Down 历史命令
 * 4. 附件: 拖拽上传 + 按钮上传
 * 5. 中断: Ctrl+C 中断 (仅在无文本选中时)
 * 6. IME 保护: isComposing 状态下忽略快捷键 (CJK 输入法)
 */

import React, { useState, useRef, useCallback, useEffect, useMemo, type ClipboardEvent, type KeyboardEvent } from 'react';
import { CloudUpload, FileSymlink, Loader2, Send, Square, X } from 'lucide-react';
import type {
    Command,
    LocalAttachment,
    SubmitEvent,
    Message,
    Attachment,
    PastePublishResult,
    FileReferenceCapability,
    PublishedLocalFile,
} from '@/types';
import { useNotificationStore } from '@/store/notificationStore';
import CommandPalette from './CommandPalette';
import FileUpload from './FileUpload';
import VoiceInputButton from './VoiceInputButton';
import { useAsrAvailability } from '@/hooks/useAsrAvailability';
import { FileAutoComplete } from './FileAutoComplete';
import { generateUUID } from '@/utils/uuid';

/** 单张图片附件大小上限：5MB */
const MAX_IMAGE_SIZE = 5 * 1024 * 1024;

/**
 * 通用的前端图片数量上限（参考值，仅用于防御性约束）。
 *
 * 后端已实现智能视觉路由：当用户选择不支持图片的模型（如 glm-5.3）时，
 * 会自动路由到同厂商视觉模型，因此前端不再基于 modelInfo.supportsImages 前置禁用按钮。
 * 实际可处理的图片数量由路由后的视觉模型决定，前端仅保留一个合理上限。
 */
const FRONTEND_MAX_IMAGES = 20;

/**
 * 将 File 读取为纯 base64 字符串（去除 data:mime;base64, 前缀）。
 * 后端 Attachment.base64Data 期望接收不含前缀的 base64。
 */
function readFileAsBase64(file: File): Promise<string> {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => {
            const result = reader.result as string;
            const commaIdx = result.indexOf(',');
            resolve(commaIdx >= 0 ? result.slice(commaIdx + 1) : result);
        };
        reader.onerror = () => reject(reader.error ?? new Error('FileReader failed'));
        reader.readAsDataURL(file);
    });
}

interface PromptInputProps {
    sessionId?: string | null;
    onSubmit: (event: SubmitEvent) => Promise<boolean>;
    onSlashCommand: (command: string) => Promise<boolean>;
    onInterrupt: () => void;
    disabled: boolean;
    runActive: boolean;
    compacting: boolean;
    permissionMode: string;
    messages: Message[];
    commands: Command[];
    simpleMode?: boolean;
    onPasteImages: (files: File[]) => Promise<PastePublishResult>;
    onPublishLocalFile: (file: File) => Promise<PublishedLocalFile>;
    fileReferenceCapability: FileReferenceCapability | null;
}

interface PickedLocalFile {
    path: string;
    name: string;
    size: number;
}

const PromptInput: React.FC<PromptInputProps> = ({
    sessionId,
    onSubmit,
    onSlashCommand,
    onInterrupt,
    disabled,
    runActive,
    compacting,
    commands,
    simpleMode = false,
    onPasteImages,
    onPublishLocalFile,
    fileReferenceCapability,
}) => {
    const [input, setInput] = useState('');
    const [attachments, setAttachments] = useState<LocalAttachment[]>([]);
    const [showCommands, setShowCommands] = useState(false);
    const [showGlobalPalette, setShowGlobalPalette] = useState(false);
    const [showFileComplete, setShowFileComplete] = useState(false);
    const [fileQuery, setFileQuery] = useState('');
    const [historyIndex, setHistoryIndex] = useState(-1);
    const [isSubmitting, setIsSubmitting] = useState(false);
    const [isUploadingPaste, setIsUploadingPaste] = useState(false);
    const [isPickingLocalFile, setIsPickingLocalFile] = useState(false);
    const [isUploadingLocalFile, setIsUploadingLocalFile] = useState(false);
    const [localFiles, setLocalFiles] = useState<PickedLocalFile[]>([]);
    const [publishedLocalFiles, setPublishedLocalFiles] = useState<PublishedLocalFile[]>([]);
    const textareaRef = useRef<HTMLTextAreaElement>(null);
    const browserFileInputRef = useRef<HTMLInputElement>(null);
    const historyRef = useRef<string[]>([]);
    const submissionRef = useRef(false);
    const pickerRequestIdRef = useRef(0);
    const previousSessionIdRef = useRef(sessionId);
    // 异步链路（粘贴上传/读取 base64）的卸载防护：卸载后短路 setState/通知并回收 ObjectURL
    const isMountedRef = useRef(true);
    // 追踪光标位置，用于语音识别结果插入光标处而非追加末尾
    const cursorPosRef = useRef<number | null>(null);

    useEffect(() => {
        isMountedRef.current = true;
        return () => {
            isMountedRef.current = false;
            pickerRequestIdRef.current += 1;
        };
    }, []);

    useEffect(() => {
        const previousSessionId = previousSessionIdRef.current;
        if (previousSessionId === sessionId) return;
        previousSessionIdRef.current = sessionId;
        // Selecting a remote file may create the first authorized Session.
        // That initial null -> id binding belongs to the same draft and must not
        // invalidate the in-flight upload. Real switches still clear references.
        if (previousSessionId == null && sessionId != null) return;
        pickerRequestIdRef.current += 1;
        setIsPickingLocalFile(false);
        setIsUploadingLocalFile(false);
        setLocalFiles([]);
        setPublishedLocalFiles([]);
    }, [sessionId]);

    // 图片上传按钮始终可用：后端的智能视觉路由会处理模型适配，
    // 前端不再基于 supportsImages 进行前置禁用，仅保留通用数量上限。
    const maxImages = FRONTEND_MAX_IMAGES;
    const asrAvailable = useAsrAvailability();

    const imageCount = useMemo(
        () => attachments.filter(a => a.type.startsWith('image/')).length,
        [attachments]
    );

    // Auto-resize textarea height
    useEffect(() => {
        const el = textareaRef.current;
        if (el) {
            el.style.height = 'auto';
            el.style.height = Math.min(el.scrollHeight, 200) + 'px';
        }
    }, [input]);

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
            pickerRequestIdRef.current += 1;
            setIsPickingLocalFile(false);
            setIsUploadingLocalFile(false);
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
    ]);

    const handlePickLocalFile = useCallback(async () => {
        if (disabled || runActive || compacting || isSubmitting
                || isUploadingPaste || isPickingLocalFile
                || isUploadingLocalFile) return;
        const requestId = ++pickerRequestIdRef.current;
        setIsPickingLocalFile(true);
        try {
            const response = await fetch('/api/files/pick', {
                method: 'POST',
                headers: { 'X-Zhikun-Native-Picker': '1' },
            });
            if (response.status === 204) return;
            if (!response.ok) {
                let message = '选择本地文件失败';
                try {
                    const error = await response.json() as { message?: unknown };
                    if (typeof error.message === 'string' && error.message) {
                        message = error.message;
                    }
                } catch {
                    // Keep the stable fallback for non-JSON proxy errors.
                }
                throw new Error(message);
            }
            const result = await response.json() as { files?: unknown };
            if (!Array.isArray(result.files)
                    || !result.files.every(file => typeof file === 'object'
                        && file !== null
                        && typeof (file as PickedLocalFile).path === 'string'
                        && typeof (file as PickedLocalFile).name === 'string'
                        && typeof (file as PickedLocalFile).size === 'number')) {
                throw new Error('服务端返回了无效的本地文件信息');
            }
            if (!isMountedRef.current
                    || requestId !== pickerRequestIdRef.current) return;
            const files = result.files as PickedLocalFile[];
            setLocalFiles(previous => {
                const byPath = new Map(previous.map(file => [file.path, file]));
                files.forEach(file => byPath.set(file.path, file));
                return [...byPath.values()];
            });
        } catch (error) {
            if (!isMountedRef.current
                    || requestId !== pickerRequestIdRef.current) return;
            useNotificationStore.getState().addNotification({
                key: `pick-local-file-${generateUUID()}`,
                level: 'error',
                message: error instanceof Error
                    ? error.message : '选择本地文件失败',
                timeout: 7000,
            });
        } finally {
            if (isMountedRef.current
                    && requestId === pickerRequestIdRef.current) {
                setIsPickingLocalFile(false);
            }
        }
    }, [compacting, disabled, isPickingLocalFile, isSubmitting,
        isUploadingLocalFile, isUploadingPaste, runActive]);

    const handleBrowserLocalFile = useCallback(async (file: File) => {
        if (disabled || runActive || compacting || isSubmitting
                || isUploadingPaste || isPickingLocalFile
                || isUploadingLocalFile) return;
        const notify = useNotificationStore.getState().addNotification;
        const maxBytes = fileReferenceCapability?.mode === 'oss_upload'
            ? fileReferenceCapability.maxFileBytes : undefined;
        if (file.size === 0) {
            notify({
                key: `upload-local-file-empty-${generateUUID()}`,
                level: 'warning',
                message: `文件 “${file.name}” 为空，未上传`,
            });
            return;
        }
        if (typeof maxBytes === 'number' && file.size > maxBytes) {
            notify({
                key: `upload-local-file-large-${generateUUID()}`,
                level: 'warning',
                message: `文件 “${file.name}” 超出 ${formatFileSize(maxBytes)} 上限`,
            });
            return;
        }

        const requestId = ++pickerRequestIdRef.current;
        setIsUploadingLocalFile(true);
        try {
            const published = await onPublishLocalFile(file);
            if (!isMountedRef.current
                    || requestId !== pickerRequestIdRef.current) return;
            setPublishedLocalFiles(previous => {
                const key = `${published.sha256}\0${published.name}`;
                const byContent = new Map(previous.map(item => [
                    `${item.sha256}\0${item.name}`, item,
                ]));
                byContent.set(key, published);
                return [...byContent.values()];
            });
            notify({
                key: `upload-local-file-ok-${generateUUID()}`,
                level: 'success',
                message: `“${published.name}” 已上传为 OSS 公开文件`,
                timeout: 3000,
            });
        } catch (error) {
            if (!isMountedRef.current
                    || requestId !== pickerRequestIdRef.current) return;
            notify({
                key: `upload-local-file-failed-${generateUUID()}`,
                level: 'error',
                message: error instanceof Error
                    ? error.message : '本地文件上传 OSS 失败',
                timeout: 7000,
            });
        } finally {
            if (isMountedRef.current
                    && requestId === pickerRequestIdRef.current) {
                setIsUploadingLocalFile(false);
            }
        }
    }, [compacting, disabled, fileReferenceCapability,
        isPickingLocalFile, isSubmitting, isUploadingLocalFile,
        isUploadingPaste, onPublishLocalFile, runActive]);

    const handleFileReferenceClick = useCallback(() => {
        if (disabled || runActive || compacting || isSubmitting
                || isUploadingPaste || isPickingLocalFile
                || isUploadingLocalFile) return;
        if (fileReferenceCapability?.mode === 'native_path') {
            void handlePickLocalFile();
            return;
        }
        if (fileReferenceCapability?.mode === 'oss_upload') {
            browserFileInputRef.current?.click();
            return;
        }
        const error = fileReferenceCapability?.error;
        useNotificationStore.getState().addNotification({
            key: `file-reference-unavailable-${generateUUID()}`,
            level: 'warning',
            message: error === 'OSS_PUBLISHING_DISABLED'
                ? '远程文件引用需要先配置 OSS'
                : '本地文件引用能力当前不可用',
            timeout: 5000,
        });
    }, [compacting, disabled, fileReferenceCapability,
        handlePickLocalFile, isPickingLocalFile, isSubmitting,
        isUploadingLocalFile, isUploadingPaste, runActive]);

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

    const handleFiles = useCallback(async (files: File[]) => {
        if (runActive || compacting) {
            useNotificationStore.getState().addNotification({
                key: 'run-input-attachments',
                level: 'warning',
                message: '任务运行或压缩期间不能添加附件',
                timeout: 5000,
            });
            return;
        }
        const accepted: LocalAttachment[] = [];
        const notify = useNotificationStore.getState().addNotification;
        // 使用独立计数器避免同一批多张图片同时越限
        let currentImageCount = imageCount;

        // 仅接受图片类型文件；非图片文件统一过滤并提示一次
        const nonImages = files.filter(f => !f.type.startsWith('image/'));
        const imageFiles = files.filter(f => f.type.startsWith('image/'));
        if (nonImages.length > 0) {
            notify({
                key: `attach-nonimage-ignored-${generateUUID()}`,
                level: 'warning',
                message: `仅支持上传图片文件，已忽略 ${nonImages.length} 个非图片文件`,
            });
        }

        for (const f of imageFiles) {
            // 组件卸载后停止处理后续图片，避免卸载后继续读文件/发通知
            if (!isMountedRef.current) break;
            const isImage = f.type.startsWith('image/');

            // 超出通用图片数量上限：静默丢弃剩余图片，仅提示一次
            if (isImage && currentImageCount >= maxImages) {
                notify({
                    key: `attach-img-limit-${generateUUID()}`,
                    level: 'warning',
                    message: `已达图片数量上限 (${currentImageCount}/${maxImages})`,
                });
                continue;
            }

            // 图片单独校验大小上限
            if (isImage && f.size > MAX_IMAGE_SIZE) {
                notify({
                    key: `attach-too-large-${generateUUID()}`,
                    level: 'warning',
                    message: `图片 “${f.name}” 超出 5MB 上限，已跳过`,
                });
                continue;
            }

            const base: LocalAttachment = {
                id: generateUUID(),
                name: f.name,
                size: f.size,
                type: f.type,
                file: f,
            };

            if (isImage) {
                try {
                    base.base64Content = await readFileAsBase64(f);
                    base.previewUrl = URL.createObjectURL(f);
                    currentImageCount += 1;
                } catch (err) {
                    if (!isMountedRef.current) break;
                    notify({
                        key: `attach-read-fail-${generateUUID()}`,
                        level: 'error',
                        message: `读取图片 “${f.name}” 失败：${(err as Error).message}`,
                    });
                    continue;
                }
            }

            accepted.push(base);
        }

        // 卸载后不再 setState；已创建的预览 URL 未进入 attachmentsRef，需就地回收
        if (!isMountedRef.current) {
            accepted.forEach(a => {
                if (a.previewUrl) URL.revokeObjectURL(a.previewUrl);
            });
            return;
        }

        if (accepted.length > 0) {
            setAttachments(prev => [...prev, ...accepted]);
        }
    }, [imageCount, maxImages, runActive, compacting]);

    const handlePaste = useCallback((event: ClipboardEvent<HTMLTextAreaElement>) => {
        const itemFiles = Array.from(event.clipboardData.items)
            .filter(item => item.kind === 'file' && item.type.startsWith('image/'))
            .map(item => item.getAsFile())
            .filter((file): file is File => file !== null);
        const imageFiles = itemFiles.length > 0
            ? itemFiles
            : Array.from(event.clipboardData.files).filter(file => file.type.startsWith('image/'));
        if (imageFiles.length === 0) return;
        event.preventDefault();

        if (runActive || compacting || isUploadingPaste) {
            useNotificationStore.getState().addNotification({
                key: `paste-image-busy-${generateUUID()}`,
                level: 'warning',
                message: '当前任务运行、压缩或图片上传期间不能粘贴图片',
            });
            return;
        }

        const remaining = Math.max(0, maxImages - imageCount);
        const accepted = imageFiles.slice(0, remaining).filter(file => file.size <= MAX_IMAGE_SIZE);
        const notify = useNotificationStore.getState().addNotification;
        if (imageFiles.some(file => file.size > MAX_IMAGE_SIZE)) {
            notify({
                key: `paste-image-size-${generateUUID()}`,
                level: 'warning',
                message: '部分粘贴图片超过 5MB，已跳过',
            });
        }
        if (imageFiles.length > remaining) {
            notify({
                key: `paste-image-limit-${generateUUID()}`,
                level: 'warning',
                message: `图片数量最多为 ${maxImages} 张，超出部分已跳过`,
            });
        }
        if (accepted.length === 0) return;

        setIsUploadingPaste(true);
        void onPasteImages(accepted).then(async result => {
            if (result.mode === 'base64') {
                // OSS 未配置：降级复用按钮/拖拽上传的 Base64 直传路径
                // （readFileAsBase64 + 预览 + 数量/大小校验均由 handleFiles 统一处理）。
                await handleFiles(accepted);
                if (!isMountedRef.current) return;
                notify({
                    key: `paste-image-inline-${generateUUID()}`,
                    level: 'info',
                    message: `${accepted.length} 张粘贴图片将随消息直传（OSS 未配置）`,
                    timeout: 2500,
                });
                return;
            }
            const remoteAttachments: LocalAttachment[] = result.items.map((item, index) => ({
                id: generateUUID(),
                name: item.name,
                size: item.size,
                type: item.mediaType,
                file: accepted[index],
                previewUrl: URL.createObjectURL(accepted[index]),
                remoteUrl: item.url,
            }));
            // await 期间组件可能已卸载：新建的预览 URL 不会进入 attachmentsRef，
            // 需就地回收，且不再 setState/发通知。
            if (!isMountedRef.current) {
                remoteAttachments.forEach(a => {
                    if (a.previewUrl) URL.revokeObjectURL(a.previewUrl);
                });
                return;
            }
            setAttachments(previous => [...previous, ...remoteAttachments]);
            notify({
                key: `paste-image-uploaded-${generateUUID()}`,
                level: 'success',
                message: `${remoteAttachments.length} 张粘贴图片已自动上传 OSS`,
                timeout: 2500,
            });
        }).catch(error => {
            if (!isMountedRef.current) return;
            notify({
                key: `paste-image-failed-${generateUUID()}`,
                level: 'error',
                message: error instanceof Error ? error.message : '粘贴图片上传 OSS 失败',
                timeout: 7000,
            });
        }).finally(() => {
            if (isMountedRef.current) setIsUploadingPaste(false);
        });
    }, [compacting, handleFiles, imageCount, isUploadingPaste, maxImages, onPasteImages, runActive]);

    // Drag & drop file upload
    const handleDrop = useCallback((e: React.DragEvent) => {
        e.preventDefault();
        const dropped = Array.from(e.dataTransfer.files);
        // 仅接受图片文件；非图片文件直接过滤掉
        const imagesOnly = dropped.filter(f => f.type.startsWith('image/'));
        const nonImageCount = dropped.length - imagesOnly.length;
        if (nonImageCount > 0) {
            useNotificationStore.getState().addNotification({
                key: `drop-nonimage-ignored-${generateUUID()}`,
                level: 'warning',
                message: `仅支持上传图片文件，已忽略 ${nonImageCount} 个非图片文件`,
            });
        }
        if (imagesOnly.length === 0) return;
        void handleFiles(imagesOnly);
    }, [handleFiles]);

    const removeAttachment = useCallback((id: string) => {
        setAttachments(prev => {
            const target = prev.find(a => a.id === id);
            if (target?.previewUrl) {
                URL.revokeObjectURL(target.previewUrl);
            }
            return prev.filter(a => a.id !== id);
        });
    }, []);

    // 用 ref 追踪最新 attachments，确保卸载时能释放所有预览 URL
    const attachmentsRef = useRef(attachments);
    attachmentsRef.current = attachments;

    // 组件卸载时释放所有预览 URL，防止内存泄露
    useEffect(() => {
        return () => {
            attachmentsRef.current.forEach(a => {
                if (a.previewUrl) URL.revokeObjectURL(a.previewUrl);
            });
        };
    }, []);

    // 注：模型切换不再清理已选图片附件。
    // 后端的智能视觉路由会在请求时自动选择同厂商视觉模型处理图片，
    // 因此即便切换到 supportsImages=false 的模型也无需移除图片。

    const fileReferenceBusy = isPickingLocalFile || isUploadingLocalFile;
    const fileReferenceTitle = fileReferenceCapability === null
        ? '正在获取本地文件引用能力'
        : fileReferenceCapability.mode === 'native_path'
        ? '选择一个本地文件路径（不上传文件内容）'
        : fileReferenceCapability.mode === 'oss_upload'
        ? `选择文件并立即上传为永久公开 OSS 对象（上限 ${formatFileSize(fileReferenceCapability.maxFileBytes ?? 0)}）`
        : fileReferenceCapability.error === 'OSS_PUBLISHING_DISABLED'
        ? '远程文件引用需要先配置 OSS'
        : '本地文件引用能力当前不可用';

    return (
        <div
            className="relative"
            onDrop={handleDrop}
            onDragOver={e => e.preventDefault()}
        >
            {/* File auto-complete (@trigger) */}
            {showFileComplete && (
                <FileAutoComplete
                    query={fileQuery}
                    onSelect={(filePath) => {
                        const cursor = textareaRef.current?.selectionStart || 0;
                        const textBeforeCursor = input.slice(0, cursor);
                        const atStart = textBeforeCursor.lastIndexOf('@');
                        if (atStart >= 0) {
                            const newText = input.slice(0, atStart) + '@' + filePath + ' ' + input.slice(cursor);
                            setInput(newText);
                        }
                        setShowFileComplete(false);
                    }}
                    onClose={() => setShowFileComplete(false)}
                />
            )}

            {/* Slash command palette */}
            {showCommands && !runActive && !compacting && (
                <CommandPalette
                    commands={commands}
                    filter={input.slice(1)}
                    onSelect={(cmd) => {
                        void submitSlashCommand('/' + cmd);
                    }}
                    onClose={() => setShowCommands(false)}
                />
            )}

            {/* Global command palette (Ctrl+K) */}
            {showGlobalPalette && !runActive && !compacting && (
                <CommandPalette
                    commands={commands}
                    filter=""
                    onSelect={(cmd) => {
                        void submitSlashCommand('/' + cmd, false);
                    }}
                    onClose={() => setShowGlobalPalette(false)}
                    isGlobal
                />
            )}

            {/* Attachment preview bar */}
            {attachments.length > 0 && (
                <div className="mb-2">
                    {/* 图片计数 badge：仅在能力已加载且存在图片附件时展示 */}
                    {imageCount > 0 && maxImages > 0 && (
                        <div className="flex items-center gap-1 mb-1.5">
                            <span
                                className={`text-xs px-1.5 py-0.5 rounded border
                                    ${imageCount >= maxImages
                                        ? 'text-amber-300 border-amber-700 bg-amber-900/30'
                                        : 'text-gray-400 border-gray-700 bg-gray-800/60'}`}
                                title={imageCount >= maxImages ? '已达当前模型图片上限' : undefined}
                            >
                                {imageCount}/{maxImages} 张图片
                            </span>
                        </div>
                    )}
                    <div className="flex gap-2 flex-wrap">
                    {attachments.map(a => (
                        a.previewUrl ? (
                            // 图片缩略图预览 (60x60)
                            <div
                                key={a.id}
                                className="relative group rounded border border-gray-700 overflow-hidden
                                           bg-gray-800"
                                style={{ width: 60, height: 60 }}
                                title={`${a.name} (${formatFileSize(a.size)})`}
                            >
                                <img
                                    src={a.previewUrl}
                                    alt={a.name}
                                    className="w-full h-full object-cover"
                                />
                                <button
                                    onClick={() => removeAttachment(a.id)}
                                    type="button"
                                    aria-label={`移除 ${a.name}`}
                                    className="absolute top-0.5 right-0.5 p-0.5 rounded-full
                                               bg-black/70 text-gray-200 hover:bg-black hover:text-white
                                               opacity-80 group-hover:opacity-100 transition-opacity"
                                >
                                    <X size={12} />
                                </button>
                            </div>
                        ) : (
                            <span
                                key={a.id}
                                className="flex items-center gap-1 px-2 py-1 bg-gray-800 rounded text-xs text-gray-300
                                           border border-gray-700"
                            >
                                📎 {a.name}
                                <span className="text-gray-500">
                                    ({formatFileSize(a.size)})
                                </span>
                                <button
                                    onClick={() => removeAttachment(a.id)}
                                    className="ml-1 text-gray-500 hover:text-gray-300"
                                    type="button"
                                    aria-label={`移除 ${a.name}`}
                                >
                                    ×
                                </button>
                            </span>
                        )
                    ))}
                    </div>
                </div>
            )}

            {localFiles.length > 0 && (
                <div className="mb-2 flex gap-2 flex-wrap">
                    {localFiles.map(file => (
                        <span
                            key={file.path}
                            title={`${file.path}\n该路径会发送给模型服务商；选择路径不授予读取权限。`}
                            className="flex max-w-full items-center gap-1 rounded border border-amber-800/70 bg-amber-950/30 px-2 py-1 text-xs text-amber-200"
                        >
                            <FileSymlink size={13} className="shrink-0" />
                            <span className="truncate">{file.name}</span>
                            <span className="shrink-0 text-amber-500">({formatFileSize(file.size)})</span>
                            <button
                                type="button"
                                aria-label={`移除本地路径 ${file.name}`}
                                onClick={() => setLocalFiles(previous =>
                                    previous.filter(item => item.path !== file.path))}
                                className="ml-1 shrink-0 text-amber-500 hover:text-amber-200"
                            >
                                <X size={12} />
                            </button>
                        </span>
                    ))}
                    <span className="self-center text-xs text-gray-500">
                        路径会发送给模型服务商；读取项目外文件仍需授权
                    </span>
                </div>
            )}

            {publishedLocalFiles.length > 0 && (
                <div className="mb-2 flex gap-2 flex-wrap">
                    {publishedLocalFiles.map(file => (
                        <span
                            key={`${file.sha256}\0${file.name}`}
                            title={`${file.url}\n文件已上传为永久公开 OSS 对象；移除引用不会删除对象。`}
                            className="flex max-w-full items-center gap-1 rounded border border-blue-800/70 bg-blue-950/30 px-2 py-1 text-xs text-blue-200"
                        >
                            <CloudUpload size={13} className="shrink-0" />
                            <span className="truncate">{file.name}</span>
                            <span className="shrink-0 text-blue-500">
                                ({formatFileSize(file.size)})
                            </span>
                            <button
                                type="button"
                                aria-label={`移除 OSS 文件引用 ${file.name}`}
                                onClick={() => setPublishedLocalFiles(previous =>
                                    previous.filter(item => !(item.sha256 === file.sha256
                                        && item.name === file.name)))}
                                className="ml-1 shrink-0 text-blue-500 hover:text-blue-200"
                            >
                                <X size={12} />
                            </button>
                        </span>
                    ))}
                    <span className="self-center text-xs text-gray-500">
                        已上传为永久公开 OSS 对象；移除引用不会删除文件
                    </span>
                </div>
            )}

            {/* Input area */}
            <div className="flex items-end gap-2">
                <textarea
                    ref={textareaRef}
                    value={input}
                    onChange={e => {
                        const text = e.target.value;
                        const cursor = e.target.selectionStart || 0;
                        cursorPosRef.current = e.target.selectionStart ?? null;
                        setInput(text);

                        // 检测 @ 触发
                        const textBeforeCursor = text.slice(0, cursor);
                        const atMatch = textBeforeCursor.match(/@([\w./\-]*)$/);
                        if (atMatch) {
                            setFileQuery(atMatch[1]);
                            setShowFileComplete(true);
                        } else {
                            setShowFileComplete(false);
                        }

                        // 检测 / 命令触发
                        if (!runActive && !compacting && text.startsWith('/')) setShowCommands(true);
                        else setShowCommands(false);
                    }}
                    onKeyDown={handleKeyDown}
                    onPaste={handlePaste}
                    onSelect={() => {
                        cursorPosRef.current = textareaRef.current?.selectionStart ?? null;
                    }}
                    onBlur={() => {
                        cursorPosRef.current = textareaRef.current?.selectionStart ?? null;
                    }}
                    placeholder={
                        compacting
                            ? '正在压缩上下文，请稍候…'
                            : runActive
                            ? '输入对当前任务的新指令，将在当前操作完成后应用'
                            : simpleMode
                            ? '描述你希望完成或继续修改的事情…'
                            : `输入消息…（/ 查看命令，${navigator.platform.includes('Mac') ? '⌘' : 'Ctrl+'}K 打开命令面板）`
                    }
                    disabled={disabled || compacting || isSubmitting
                        || isUploadingPaste || isUploadingLocalFile}
                    aria-label="输入消息"
                    aria-multiline="true"
                    className="flex-1 resize-none rounded-lg border border-gray-700 bg-gray-900
                               px-3 py-2 text-sm text-gray-100
                               focus:outline-none focus:ring-2 focus:ring-blue-500/50
                               disabled:opacity-50 placeholder-gray-500"
                    rows={1}
                    autoFocus
                />

                {/* Toolbar */}
                <input
                    ref={browserFileInputRef}
                    data-local-file-reference-input
                    type="file"
                    multiple={false}
                    className="hidden"
                    onChange={event => {
                        const file = event.currentTarget.files?.[0];
                        event.currentTarget.value = '';
                        if (file) void handleBrowserLocalFile(file);
                    }}
                    disabled={disabled || runActive || compacting
                        || isSubmitting || isUploadingPaste || fileReferenceBusy}
                />
                {!runActive && !compacting && (
                    <button
                        type="button"
                        onClick={handleFileReferenceClick}
                        disabled={disabled || isSubmitting || isUploadingPaste
                            || fileReferenceBusy}
                        aria-label={fileReferenceCapability?.mode === 'oss_upload'
                            ? '上传本地文件到 OSS' : '引用本地文件路径'}
                        title={fileReferenceTitle}
                        className="shrink-0 rounded-lg p-2.5 text-gray-300 transition-colors hover:bg-gray-800 hover:text-white disabled:opacity-50"
                    >
                        {fileReferenceBusy
                            ? <Loader2 size={16} className="animate-spin" />
                            : fileReferenceCapability?.mode === 'oss_upload'
                            ? <CloudUpload size={16} />
                            : <FileSymlink size={16} />}
                    </button>
                )}
                {!runActive && !compacting && (
                    <FileUpload
                        onFiles={handleFiles}
                        accept="image/*"
                        disabled={disabled || isSubmitting || isUploadingPaste
                            || isUploadingLocalFile}
                        title={`上传图片（不支持图片的模型将由视觉模型自动处理，上限 ${maxImages} 张）`}
                    />
                )}
                {asrAvailable && !runActive && !compacting && (
                    <VoiceInputButton
                        onTranscript={(text) => {
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
                        }}
                        disabled={disabled || isSubmitting || isUploadingPaste
                            || isUploadingLocalFile}
                    />
                )}
                <button
                    onClick={() => { void handleSubmit(); }}
                    disabled={disabled || compacting || isSubmitting
                        || isUploadingPaste || fileReferenceBusy
                        || (!input.trim() && attachments.length === 0
                            && localFiles.length === 0
                            && publishedLocalFiles.length === 0)}
                    aria-label={runActive ? '发送运行中干预' : '发送消息'}
                    title={runActive ? '发送运行中干预' : '发送消息'}
                    className="shrink-0 p-2.5 rounded-lg text-white transition-colors
                               bg-blue-600 hover:bg-blue-700 disabled:opacity-50 disabled:hover:bg-blue-600"
                    type="button"
                >
                    <Send size={16} />
                </button>
                {runActive && (
                    <button
                        onClick={onInterrupt}
                        disabled={disabled || isSubmitting}
                        aria-label="停止当前任务"
                        title="停止当前任务"
                        className="shrink-0 p-2.5 rounded-lg text-white transition-colors
                                   bg-red-500 hover:bg-red-600 disabled:opacity-50"
                        type="button"
                    >
                        <Square size={16} />
                    </button>
                )}
            </div>
        </div>
    );
};

function formatFileSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export default React.memo(PromptInput);
