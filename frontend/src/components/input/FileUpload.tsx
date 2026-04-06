/**
 * FileUpload — 文件上传按钮
 *
 * SPEC: §8.2.6a.11 FileUpload
 * 隐藏 input[file] + 按钮触发，支持多文件选择。
 */

import React, { useRef, useCallback } from 'react';
import { Paperclip } from 'lucide-react';

interface FileUploadProps {
    onFiles: (files: File[]) => void;
    accept?: string;
    multiple?: boolean;
}

const FileUpload: React.FC<FileUploadProps> = ({
    onFiles,
    accept,
    multiple = true,
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
            />
            <button
                onClick={() => inputRef.current?.click()}
                className="shrink-0 p-2 rounded-lg text-gray-400 hover:text-gray-200 hover:bg-gray-800
                           transition-colors"
                title="Attach file"
                type="button"
            >
                <Paperclip size={18} />
            </button>
        </>
    );
};

export default React.memo(FileUpload);
