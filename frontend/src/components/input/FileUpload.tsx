/**
 * FileUpload — 文件上传按钮
 *
 * SPEC: §8.2.6a.11 FileUpload
 * 隐藏 input[file] + 按钮触发，支持多文件选择。
 *
 * Task #22: 新增 disabled / title prop，支持按模型能力禁用图片上传。
 */

import React, { useRef, useCallback } from 'react';
import { Paperclip } from 'lucide-react';

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
                className={`shrink-0 p-2 rounded-lg transition-colors
                    ${disabled
                        ? 'text-gray-600 cursor-not-allowed opacity-50'
                        : 'text-gray-400 hover:text-gray-200 hover:bg-gray-800'}`}
                title={title ?? 'Attach file'}
                type="button"
                aria-disabled={disabled}
            >
                <Paperclip size={18} />
            </button>
        </>
    );
};

export default React.memo(FileUpload);
