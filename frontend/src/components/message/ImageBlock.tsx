/**
 * ImageBlock — 图片渲染组件
 *
 * SPEC: §8.2.2 ImageResult
 * 支持 base64 和 URL 两种图片源，响应式展示 + 点击放大。
 */

import React, { useState, useCallback, useRef, useEffect } from 'react';
import { ZoomIn, X, Copy, Check } from 'lucide-react';
import { copyImageToClipboard } from '@/utils/messageContent';

interface ImageBlockProps {
    /** base64 编码数据 (不含 data: 前缀) */
    base64Data?: string;
    /** 图片 URL */
    src?: string;
    /** MIME 类型，如 image/png */
    mediaType?: string;
    alt?: string;
}

const ImageBlock: React.FC<ImageBlockProps> = ({
    base64Data,
    src,
    mediaType = 'image/png',
    alt = 'Image',
}) => {
    const [zoomed, setZoomed] = useState(false);
    const [loadError, setLoadError] = useState(false);
    const [copyState, setCopyState] = useState<'idle' | 'copied' | 'failed'>('idle');
    const copyTimerRef = useRef<number | null>(null);

    // 卸载时清理复制状态重置定时器，避免组件销毁后 setState
    useEffect(() => () => {
        if (copyTimerRef.current !== null) window.clearTimeout(copyTimerRef.current);
    }, []);

    const imageSrc = base64Data
        ? `data:${mediaType};base64,${base64Data}`
        : src ?? '';

    // src 变化时在渲染阶段重置 error 状态（React 官方推荐模式），
    // 避免旧 src 加载失败后新 src 永久停留在失败兜底 UI
    const [lastSrc, setLastSrc] = useState(imageSrc);
    if (lastSrc !== imageSrc) {
        setLastSrc(imageSrc);
        setLoadError(false);
    }

    const toggleZoom = useCallback(() => setZoomed(prev => !prev), []);

    // 复制图片到剪贴板：base64 直接转 Blob，URL 由工具内部 fetch；
    // 剪贴板图片写入不支持/失败时工具内部会降级为复制 URL 文本——
    // base64 与 src 双有时必须把 src 一并传入，否则降级链断在"复制失败"
    const copyImage = useCallback(async (event: React.MouseEvent) => {
        event.stopPropagation();
        try {
            await copyImageToClipboard(
                base64Data
                    ? { base64Data, mediaType, url: src }
                    : { url: imageSrc, mediaType },
            );
            setCopyState('copied');
        } catch {
            setCopyState('failed');
        }
        if (copyTimerRef.current !== null) window.clearTimeout(copyTimerRef.current);
        copyTimerRef.current = window.setTimeout(() => setCopyState('idle'), 2000);
    }, [base64Data, imageSrc, mediaType, src]);

    if (!imageSrc) {
        return (
            <div className="flex items-center justify-center h-32 rounded-[14px] border border-hairline bg-surfacev2 text-t2 text-sm">
                No image data
            </div>
        );
    }

    if (loadError) {
        return (
            <div className="flex items-center justify-center h-32 rounded-[14px] border border-err bg-errsoft text-err text-sm">
                Failed to load image
            </div>
        );
    }

    return (
        <>
            {/* Inline preview */}
            <div className="image-block relative group my-2 inline-block">
                <img
                    src={imageSrc}
                    alt={alt}
                    className="max-w-full max-h-80 rounded-[10px] border border-hairline cursor-pointer"
                    onClick={toggleZoom}
                    onError={() => setLoadError(true)}
                    loading="lazy"
                />
                <button
                    onClick={toggleZoom}
                    className="panel-control absolute top-2 right-2 p-1 rounded bg-black/50 text-white opacity-0 group-hover:opacity-100 transition-opacity"
                    aria-label="Zoom image"
                >
                    <ZoomIn size={16} />
                </button>
            </div>

            {/* Full-screen overlay */}
            {zoomed && (
                <div
                    className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm"
                    onClick={toggleZoom}
                >
                    <div className="absolute top-4 right-4 flex items-center gap-2">
                        <button
                            onClick={copyImage}
                            className={`panel-control p-2 rounded-full bg-surfacev2 hover:bg-sunken2 ${
                                copyState === 'copied' ? 'text-ok' : copyState === 'failed' ? 'text-err' : 'text-t1'
                            }`}
                            aria-label={copyState === 'copied' ? 'Image copied' : copyState === 'failed' ? 'Copy image failed' : 'Copy image'}
                            title={copyState === 'copied' ? '已复制' : copyState === 'failed' ? '复制失败' : '复制图片'}
                            type="button"
                        >
                            {copyState === 'copied' ? <Check size={20} /> : <Copy size={20} />}
                        </button>
                        <button
                            onClick={(event) => {
                                event.stopPropagation();
                                setZoomed(false);
                            }}
                            className="panel-control p-2 rounded-full bg-surfacev2 text-t1 hover:bg-sunken2"
                            aria-label="Close zoom"
                            type="button"
                        >
                            <X size={20} />
                        </button>
                    </div>
                    <img
                        src={imageSrc}
                        alt={alt}
                        className="max-w-[90vw] max-h-[90vh] rounded-[10px]"
                        onClick={(e) => e.stopPropagation()}
                    />
                </div>
            )}
        </>
    );
};

export default React.memo(ImageBlock);
