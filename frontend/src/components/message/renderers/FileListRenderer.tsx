/**
 * FileListRenderer — GlobTool 专用渲染器
 * 功能: 树形文件列表 + 文件类型图标
 */

import React, { useMemo } from 'react';

interface FileNode {
    name: string;
    path: string;
    isDir: boolean;
    children: FileNode[];
}

function buildTree(paths: string[]): FileNode[] {
    const root: FileNode = { name: '', path: '', isDir: true, children: [] };
    for (const path of paths) {
        const parts = path.split('/');
        let current = root;
        for (let i = 0; i < parts.length; i++) {
            const isLast = i === parts.length - 1;
            let child = current.children.find(c => c.name === parts[i]);
            if (!child) {
                child = { name: parts[i], path: parts.slice(0, i + 1).join('/'),
                    isDir: !isLast, children: [] };
                current.children.push(child);
            }
            current = child;
        }
    }
    return root.children;
}

const fileIcons: Record<string, string> = {
    java: '☕', ts: '🔵', tsx: '⚛️', py: '🐍', json: '📄',
    md: '📝', yaml: '⚙️', yml: '⚙️', xml: '📄', sql: '🗃️',
};

const FileIcon: React.FC<{ isDir: boolean; name: string }> = ({ isDir, name }) => {
    if (isDir) return <span className="text-blue-400">📁</span>;
    const ext = name.split('.').pop()?.toLowerCase() || '';
    return <span>{fileIcons[ext] || '📄'}</span>;
};

const TreeNode: React.FC<{ node: FileNode; depth: number }> = ({ node, depth }) => (
    <div>
        <div className="flex items-center gap-1 py-0.5 hover:bg-gray-800/50 rounded px-1"
            style={{ paddingLeft: `${depth * 16}px` }}>
            <FileIcon isDir={node.isDir} name={node.name} />
            <span className={`text-sm font-mono ${node.isDir ? 'text-blue-300' : 'text-gray-300'}`}>
                {node.name}
            </span>
        </div>
        {node.children.map(child => (
            <TreeNode key={child.path} node={child} depth={depth + 1} />
        ))}
    </div>
);

export const FileListRenderer: React.FC<{ content: string }> = ({ content }) => {
    const paths = content.trim().split('\n').filter(Boolean);
    const tree = useMemo(() => buildTree(paths), [content]);

    return (
        <div className="text-sm">
            <div className="text-xs text-gray-400 mb-2">{paths.length} 个文件</div>
            <div className="bg-gray-900 rounded p-2">
                {tree.map(node => <TreeNode key={node.path} node={node} depth={0} />)}
            </div>
        </div>
    );
};
