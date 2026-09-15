/**
 * FileUpload — 文件上传按钮
 *
 * SPEC: §8.2.6a.11 FileUpload
 * 隐藏 input[file] + 按钮触发，支持多文件选择。
 *
 * Task #22: 新增 disabled / title prop，支持按模型能力禁用图片上传。
 */

import React, { useRef, useCallback } from 'react';
import { ImagePlus } from 'lucide-react';

interface FileUploadProps {
    onFiles: (files: File[]) => void;
    accept?: string;
    multiple?: boolean;
    /** 禁用上传按钮（如当前模型不支持图片输入） */
    disabled?: boolean;
    /** 自定义按钮 tooltip（disabled 状态下建议说明禁用原因） */
    title?: string;
}

const FileUpload: React.FC<FileUploadProps> = ({
    onFiles,
    accept = 'image/*',
    multiple = true,
    disabled = false,
    title,
}) => {
    const inputRef = useRef<HTMLInputElement>(null);

    const handleChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
        if (e.target.files && e.target.files.length > 0) {
            onFiles(Array.from(e.target.files));
            e.target.value = ''; // Reset to allow re-selecting same file
        }
    }, [onFiles]);

    return (
        <>
            <input
                ref={inputRef}
                type="file"
                multiple={multiple}
                accept={accept}
                className="hidden"
                onChange={handleChange}
                disabled={disabled}
            />
            <button
                onClick={() => {
                    if (disabled) return;
                    inputRef.current?.click();
                }}
                disabled={disabled}
                className={`panel-control flex h-10 w-10 items-center justify-center shrink-0 rounded-[10px] transition-interactive duration-fast
                    focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring
                    ${disabled
                        ? 'text-t4 cursor-not-allowed opacity-50'
                        : 'text-t3 hover:text-t1 hover:bg-hover2'}`}
                title={title ?? '上传图片'}
                aria-label="上传图片"
                type="button"
                aria-disabled={disabled}
            >
                <ImagePlus size={18} />
            </button>
        </>
    );
};

export default React.memo(FileUpload);
