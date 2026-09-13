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
    if (isDir) return <span className="text-accent2">📁</span>;
    const ext = name.split('.').pop()?.toLowerCase() || '';
    return <span>{fileIcons[ext] || '📄'}</span>;
};

const TreeNode: React.FC<{ node: FileNode; depth: number }> = ({ node, depth }) => (
    <div>
        <div className="flex items-center gap-1 py-0.5 hover:bg-hover2 rounded px-1"
            style={{ paddingLeft: `${depth * 16}px` }}>
            <FileIcon isDir={node.isDir} name={node.name} />
            <span className={`text-sm font-mono ${node.isDir ? 'text-accent2' : 'text-t2'}`}>
                {node.name}
            </span>
        </div>
        {node.children.map(child => (
            <TreeNode key={child.path} node={child} depth={depth + 1} />
        ))}
    </div>
);

export const FileListRenderer: React.FC<{ content: string }> = ({ content }) => {
    const paths = useMemo(() => content.trim().split('\n').filter(Boolean), [content]);
    const tree = useMemo(() => buildTree(paths), [paths]);

    return (
        <div className="text-sm">
            <div className="text-xs text-t3 mb-2 tabular-nums">{paths.length} 个文件</div>
            <div className="bg-sunken2 border border-hairline rounded-xl p-2">
                {tree.map(node => <TreeNode key={node.path} node={node} depth={0} />)}
            </div>
        </div>
    );
};
