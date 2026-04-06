/**
 * ImageBlock — 图片渲染组件
 *
 * SPEC: §8.2.2 ImageResult
 * 支持 base64 和 URL 两种图片源，响应式展示 + 点击放大。
 */

import React, { useState, useCallback } from 'react';
import { ZoomIn, X } from 'lucide-react';

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

    const imageSrc = base64Data
        ? `data:${mediaType};base64,${base64Data}`
        : src ?? '';

    const toggleZoom = useCallback(() => setZoomed(prev => !prev), []);

    if (!imageSrc) {
        return (
            <div className="flex items-center justify-center h-32 rounded-lg border border-gray-700 bg-gray-800 text-gray-500 text-sm">
                No image data
            </div>
        );
    }

    if (loadError) {
        return (
            <div className="flex items-center justify-center h-32 rounded-lg border border-red-700/50 bg-red-900/20 text-red-400 text-sm">
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
                    className="max-w-full max-h-80 rounded-lg border border-gray-700 cursor-pointer"
                    onClick={toggleZoom}
                    onError={() => setLoadError(true)}
                    loading="lazy"
                />
                <button
                    onClick={toggleZoom}
                    className="absolute top-2 right-2 p-1 rounded bg-black/50 text-white opacity-0 group-hover:opacity-100 transition-opacity"
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
                    <button
                        onClick={toggleZoom}
                        className="absolute top-4 right-4 p-2 rounded-full bg-gray-800 text-white hover:bg-gray-700"
                        aria-label="Close zoom"
                    >
                        <X size={20} />
                    </button>
                    <img
                        src={imageSrc}
                        alt={alt}
                        className="max-w-[90vw] max-h-[90vh] rounded-lg"
                        onClick={(e) => e.stopPropagation()}
                    />
                </div>
            )}
        </>
    );
};

export default React.memo(ImageBlock);
