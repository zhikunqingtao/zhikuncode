/**
 * useLocalFileReference — PromptInput 本地文件引用逻辑
 *
 * §8.3.1 PromptInput 拆分：从原 PromptInput.tsx 纯搬运（零行为变化）。
 * 职责：本地路径引用（native picker）与 OSS 上传引用（浏览器 file input），
 * 以及会话切换 / 任务运行期间 / 组件卸载时的清理与防护。
 *
 * 草稿持久化（P2 修复）：引用列表按活动 sessionId 键控托管到
 * promptDraftStore（仅内存，同 usePromptState/usePromptAttachments 模式），
 * 移动端打开文件管理卸载输入条后引用可恢复，不同会话相互隔离。
 */

import { useCallback, useEffect, useRef, useState, type Dispatch, type SetStateAction } from 'react';
import type {
    FileReferenceCapability,
    PickedLocalFile,
    PublishedLocalFile,
} from '@/types';
import { useNotificationStore } from '@/store/notificationStore';
import {
    resolvePromptDraftKey,
    usePromptDraftStore,
} from '@/store/promptDraftStore';
import { generateUUID } from '@/utils/uuid';

// PickedLocalFile 已上移到 @/types（供 promptDraftStore 引用）；
// 此处保留再导出，既有消费方（PromptToolbar 等）无需改导入路径。
export type { PickedLocalFile } from '@/types';

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

/** 选择器兜底空数组（模块级常量保证引用稳定，避免无意义重渲染） */
const EMPTY_LOCAL_FILES: PickedLocalFile[] = [];
const EMPTY_PUBLISHED_LOCAL_FILES: PublishedLocalFile[] = [];

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
    // P2 修复：本地文件引用随草稿托管到 promptDraftStore（按活动 sessionId
    // 键控，仅内存，同图片附件模式）。移动端打开文件管理会卸载输入条，
    // 组件 useState 随之销毁；store 化后卸载/重挂载引用可恢复。
    const draftKey = resolvePromptDraftKey(sessionId);
    const localFiles = usePromptDraftStore(
        s => s.drafts[draftKey]?.localFiles ?? EMPTY_LOCAL_FILES,
    );
    const publishedLocalFiles = usePromptDraftStore(
        s => s.drafts[draftKey]?.publishedLocalFiles ?? EMPTY_PUBLISHED_LOCAL_FILES,
    );
    // P1 修复（stale draft-key writes）：OSS 上传引用会先 ensureSessionReady()
    // 创建首个会话（sessionId null → id），await 之后的写入若仍用渲染期捕获的
    // 兜底键，引用会落到已迁空的 '__none__' 上。所有写在调用时经
    // latestSessionIdRef 解析目标键，写到上传实际归属的会话。
    const latestSessionIdRef = useRef(sessionId);
    useEffect(() => {
        latestSessionIdRef.current = sessionId;
    }, [sessionId]);
    const setLocalFiles = useCallback<Dispatch<SetStateAction<PickedLocalFile[]>>>((value) => {
        const targetKey = resolvePromptDraftKey(latestSessionIdRef.current);
        usePromptDraftStore.getState().setLocalFiles(targetKey, value);
    }, []);
    const setPublishedLocalFiles = useCallback<Dispatch<SetStateAction<PublishedLocalFile[]>>>(
        (value) => {
            const targetKey = resolvePromptDraftKey(latestSessionIdRef.current);
            usePromptDraftStore.getState().setPublishedLocalFiles(targetKey, value);
        },
        [],
    );
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
        // invalidate the in-flight upload. Real switches still cancel it.
        if (previousSessionId == null && sessionId != null) return;
        // 引用列表不再随会话切换清空：store 化后各会话引用按键隔离，
        // 切回时原样恢复（与图片附件/文本草稿同语义）；切换仅取消
        // 进行中的读取/上传（requestId 失配使其续体空操作）并复位 busy。
        pickerRequestIdRef.current += 1;
        setIsPickingLocalFile(false);
        setIsUploadingLocalFile(false);
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
