/**
 * usePromptAttachments — PromptInput 图片附件逻辑
 *
 * §8.3.1 PromptInput 拆分：从原 PromptInput.tsx 纯搬运（零行为变化）。
 * 职责：图片附件的按钮 / 拖拽 / 粘贴上传（含 OSS 直传与 Base64 降级），
 * 附件移除与异步链路卸载时的 ObjectURL 回收防护。
 *
 * 草稿持久化（P1 修复）：附件列表按活动 sessionId 键控托管到
 * promptDraftStore（仅内存，理由同 usePromptState 的输入草稿），
 * 卸载/重挂载后附件可恢复，不同会话相互隔离。
 */

import {
    useCallback,
    useEffect,
    useMemo,
    useRef,
    useState,
    type ClipboardEvent,
    type Dispatch,
    type SetStateAction,
} from 'react';
import type {
    LocalAttachment,
    PastePublishResult,
} from '@/types';
import { useNotificationStore } from '@/store/notificationStore';
import {
    capturePromptDraftTarget,
    usePromptDraftStore,
} from '@/store/promptDraftStore';
import { generateUUID } from '@/utils/uuid';
import { usePromptDraftKey } from './usePromptDraftKey';

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

export interface UsePromptAttachmentsParams {
    runActive: boolean;
    compacting: boolean;
    onPasteImages: (files: File[]) => Promise<PastePublishResult>;
    /** 草稿归属会话：附件随 sessionId 键控存入 promptDraftStore；缺省回落稳定兜底键 */
    sessionId?: string | null;
}

/** 选择器兜底空数组（模块级常量保证引用稳定，避免无意义重渲染） */
const EMPTY_ATTACHMENTS: LocalAttachment[] = [];

export function usePromptAttachments({
    runActive,
    compacting,
    onPasteImages,
    sessionId,
}: UsePromptAttachmentsParams) {
    // 附件托管到 promptDraftStore（按活动 sessionId 键控，仅内存）：
    // 卸载/重挂载（移动端底部导航切换）后从 store 读回，ObjectURL 保持有效。
    const draftKey = usePromptDraftKey(sessionId);
    const attachments = usePromptDraftStore(
        s => s.drafts[draftKey]?.attachments ?? EMPTY_ATTACHMENTS,
    );
    const setAttachments = useCallback<Dispatch<SetStateAction<LocalAttachment[]>>>((value) => {
        usePromptDraftStore.getState().setAttachments(draftKey, value);
    }, [draftKey]);
    const [isUploadingPaste, setIsUploadingPaste] = useState(false);
    // 异步链路（粘贴上传/读取 base64）的卸载防护：卸载后短路 setState/通知并回收 ObjectURL
    const isMountedRef = useRef(true);

    useEffect(() => {
        isMountedRef.current = true;
        return () => {
            isMountedRef.current = false;
        };
    }, []);

    // 图片上传按钮始终可用：后端的智能视觉路由会处理模型适配，
    // 前端不再基于 supportsImages 进行前置禁用，仅保留通用数量上限。
    const maxImages = FRONTEND_MAX_IMAGES;

    const imageCount = useMemo(
        () => attachments.filter(a => a.type.startsWith('image/')).length,
        [attachments]
    );

    const handleFiles = useCallback(async (
        files: File[],
        resolveTarget = capturePromptDraftTarget(draftKey),
    ) => {
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
        const targetKey = resolveTarget();
        if (!isMountedRef.current || targetKey === undefined) {
            accepted.forEach(a => {
                if (a.previewUrl) URL.revokeObjectURL(a.previewUrl);
            });
            return;
        }

        if (accepted.length > 0) {
            usePromptDraftStore.getState().setAttachments(targetKey, prev => [...prev, ...accepted]);
        }
    }, [imageCount, maxImages, runActive, compacting, draftKey]);

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
        const resolveTarget = capturePromptDraftTarget(draftKey);
        void onPasteImages(accepted).then(async result => {
            if (result.mode === 'base64') {
                // OSS 未配置：降级复用按钮/拖拽上传的 Base64 直传路径
                // （readFileAsBase64 + 预览 + 数量/大小校验均由 handleFiles 统一处理）。
                await handleFiles(accepted, resolveTarget);
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
            const targetKey = resolveTarget();
            if (!isMountedRef.current || targetKey === undefined) {
                remoteAttachments.forEach(a => {
                    if (a.previewUrl) URL.revokeObjectURL(a.previewUrl);
                });
                return;
            }
            usePromptDraftStore.getState().setAttachments(targetKey, previous => [...previous, ...remoteAttachments]);
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
    }, [compacting, handleFiles, imageCount, isUploadingPaste, maxImages, onPasteImages,
        runActive, draftKey]);

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
    }, [setAttachments]);

    // 注意：附件随草稿在组件卸载后继续存活于 promptDraftStore（重挂载需原样恢复），
    // 因此不再于卸载时回收预览 ObjectURL。回收点收敛为：removeAttachment 移除、
    // usePromptState.handleSubmit 提交成功、以及上述异步链路的卸载防护（isMountedRef）。

    // 注：模型切换不再清理已选图片附件。
    // 后端的智能视觉路由会在请求时自动选择同厂商视觉模型处理图片，
    // 因此即便切换到 supportsImages=false 的模型也无需移除图片。

    return {
        attachments,
        setAttachments,
        isUploadingPaste,
        imageCount,
        maxImages,
        handleFiles,
        handlePaste,
        handleDrop,
        removeAttachment,
    };
}
