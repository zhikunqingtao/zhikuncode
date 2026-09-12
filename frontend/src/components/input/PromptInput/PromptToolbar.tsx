/**
 * PromptToolbar — 附件预览条（PromptAttachmentBar）+ 附件/语音操作行（PromptToolbar）
 *
 * §8.3.1 PromptInput 拆分：从原 PromptInput.tsx 纯搬运（零行为变化）。
 * 预览条与操作行均为附件相关 UI，内聚于同一模块；
 * 预览条渲染在输入行上方，操作行渲染在输入行内（与原始 DOM 结构一致）。
 */

import React from 'react';
import { CloudUpload, FileSymlink, Loader2, Paperclip, X } from 'lucide-react';
import type {
    FileReferenceCapability,
    LocalAttachment,
    PublishedLocalFile,
} from '@/types';
import FileUpload from '../FileUpload';
import VoiceInputButton from '../VoiceInputButton';
import { formatFileSize, type PickedLocalFile } from './useLocalFileReference';

interface PromptAttachmentBarProps {
    attachments: LocalAttachment[];
    imageCount: number;
    maxImages: number;
    localFiles: PickedLocalFile[];
    publishedLocalFiles: PublishedLocalFile[];
    onRemoveAttachment: (id: string) => void;
    setLocalFiles: React.Dispatch<React.SetStateAction<PickedLocalFile[]>>;
    setPublishedLocalFiles: React.Dispatch<React.SetStateAction<PublishedLocalFile[]>>;
}

export const PromptAttachmentBar: React.FC<PromptAttachmentBarProps> = ({
    attachments,
    imageCount,
    maxImages,
    localFiles,
    publishedLocalFiles,
    onRemoveAttachment,
    setLocalFiles,
    setPublishedLocalFiles,
}) => (
    <>
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
                                onClick={() => onRemoveAttachment(a.id)}
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
                                onClick={() => onRemoveAttachment(a.id)}
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
    </>
);

interface PromptToolbarProps {
    runActive: boolean;
    compacting: boolean;
    disabled: boolean;
    isSubmitting: boolean;
    isUploadingPaste: boolean;
    isUploadingLocalFile: boolean;
    fileReferenceBusy: boolean;
    fileReferenceTitle: string;
    fileReferenceCapability: FileReferenceCapability | null;
    asrAvailable: boolean;
    maxImages: number;
    browserFileInputRef: React.RefObject<HTMLInputElement>;
    onFileReferenceClick: () => void;
    onBrowserLocalFile: (file: File) => void;
    onFiles: (files: File[]) => void;
    onVoiceTranscript: (text: string) => void;
}

export const PromptToolbar: React.FC<PromptToolbarProps> = ({
    runActive,
    compacting,
    disabled,
    isSubmitting,
    isUploadingPaste,
    isUploadingLocalFile,
    fileReferenceBusy,
    fileReferenceTitle,
    fileReferenceCapability,
    asrAvailable,
    maxImages,
    browserFileInputRef,
    onFileReferenceClick,
    onBrowserLocalFile,
    onFiles,
    onVoiceTranscript,
}) => (
    <>
        <input
            ref={browserFileInputRef}
            data-local-file-reference-input
            type="file"
            multiple={false}
            className="hidden"
            onChange={event => {
                const file = event.currentTarget.files?.[0];
                event.currentTarget.value = '';
                if (file) void onBrowserLocalFile(file);
            }}
            disabled={disabled || runActive || compacting
                || isSubmitting || isUploadingPaste || fileReferenceBusy}
        />
        {!runActive && !compacting && (
            <button
                type="button"
                onClick={onFileReferenceClick}
                disabled={disabled || isSubmitting || isUploadingPaste
                    || fileReferenceBusy}
                aria-label={fileReferenceCapability?.mode === 'oss_upload'
                    ? '上传本地文件到 OSS' : '引用本地文件路径'}
                title={fileReferenceTitle}
                className="shrink-0 rounded-lg p-2.5 text-gray-300 transition-colors hover:bg-gray-800 hover:text-white disabled:opacity-50"
            >
                {fileReferenceBusy
                    ? <Loader2 size={16} className="animate-spin" />
                    : <Paperclip size={16} />}
            </button>
        )}
        {!runActive && !compacting && (
            <FileUpload
                onFiles={onFiles}
                accept="image/*"
                disabled={disabled || isSubmitting || isUploadingPaste
                    || isUploadingLocalFile}
                title={`上传图片（不支持图片的模型将由视觉模型自动处理，上限 ${maxImages} 张）`}
            />
        )}
        {asrAvailable && !runActive && !compacting && (
            <VoiceInputButton
                onTranscript={onVoiceTranscript}
                disabled={disabled || isSubmitting || isUploadingPaste
                    || isUploadingLocalFile}
            />
        )}
    </>
);

export default PromptToolbar;
