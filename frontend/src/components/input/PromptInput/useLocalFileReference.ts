/**
 * useLocalFileReference — PromptInput 本地文件引用逻辑
 *
 * §8.3.1 PromptInput 拆分：从原 PromptInput.tsx 纯搬运（零行为变化）。
 * 职责：本地路径引用（native picker）与 OSS 上传引用（浏览器 file input），
 * 以及会话切换 / 任务运行期间 / 组件卸载时的清理与防护。
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import type {
    FileReferenceCapability,
    PublishedLocalFile,
} from '@/types';
import { useNotificationStore } from '@/store/notificationStore';
import { generateUUID } from '@/utils/uuid';

export interface PickedLocalFile {
    path: string;
    name: string;
    size: number;
}

export function formatFileSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export interface UseLocalFileReferenceParams {
    sessionId?: string | null;
    disabled: boolean;
    runActive: boolean;
    compacting: boolean;
    isSubmitting: boolean;
    isUploadingPaste: boolean;
    onPublishLocalFile: (file: File) => Promise<PublishedLocalFile>;
    fileReferenceCapability: FileReferenceCapability | null;
}

export function useLocalFileReference({
    sessionId,
    disabled,
    runActive,
    compacting,
    isSubmitting,
    isUploadingPaste,
    onPublishLocalFile,
    fileReferenceCapability,
}: UseLocalFileReferenceParams) {
    const [isPickingLocalFile, setIsPickingLocalFile] = useState(false);
    const [isUploadingLocalFile, setIsUploadingLocalFile] = useState(false);
    const [localFiles, setLocalFiles] = useState<PickedLocalFile[]>([]);
    const [publishedLocalFiles, setPublishedLocalFiles] = useState<PublishedLocalFile[]>([]);
    const browserFileInputRef = useRef<HTMLInputElement>(null);
    const pickerRequestIdRef = useRef(0);
    const previousSessionIdRef = useRef(sessionId);
    // 异步链路（读取/上传）的卸载防护：卸载后短路 setState/通知
    const isMountedRef = useRef(true);

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

    useEffect(() => {
        if (runActive || compacting) {
            pickerRequestIdRef.current += 1;
            setIsPickingLocalFile(false);
            setIsUploadingLocalFile(false);
        }
    }, [runActive, compacting]);

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

    return {
        isPickingLocalFile,
        isUploadingLocalFile,
        localFiles,
        setLocalFiles,
        publishedLocalFiles,
        setPublishedLocalFiles,
        browserFileInputRef,
        fileReferenceBusy,
        fileReferenceTitle,
        handleBrowserLocalFile,
        handleFileReferenceClick,
    };
}
