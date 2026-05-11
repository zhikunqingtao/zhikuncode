import { useState, useMemo, useCallback } from 'react';
import { FileText, Folder, FolderOpen, ChevronDown, ChevronRight } from 'lucide-react';
import { useActivityStore } from '@store/activityStore';
import { useSessionStore } from '@/store/sessionStore';
import type { FileChange } from '@/types/apos';

// ═══ 树节点类型 ═══
interface FileTreeNode {
  name: string;
  path: string;
  type: 'file' | 'dir';
  changeType?: 'added' | 'modified' | 'deleted';
  additions?: number;
  deletions?: number;
  children?: FileTreeNode[];
}

// ═══ 构建树形结构 ═══
function buildFileTree(files: FileChange[]): FileTreeNode[] {
  const root: FileTreeNode[] = [];

  for (const file of files) {
    const parts = file.filePath.split('/').filter(Boolean);
    let current = root;

    for (let i = 0; i < parts.length; i++) {
      const part = parts[i];
      const isFile = i === parts.length - 1;
      const pathSoFar = parts.slice(0, i + 1).join('/');

      let existing = current.find(n => n.name === part && n.type === (isFile ? 'file' : 'dir'));

      if (!existing) {
        existing = {
          name: part,
          path: pathSoFar,
          type: isFile ? 'file' : 'dir',
          ...(isFile && {
            changeType: file.changeType,
            additions: file.additions,
            deletions: file.deletions,
          }),
          ...(! isFile && { children: [] }),
        };
        current.push(existing);
      }

      if (!isFile) {
        current = existing.children!;
      }
    }
  }

  // 排序：目录在前，文件在后，各自按字母序
  const sortNodes = (nodes: FileTreeNode[]): FileTreeNode[] => {
    return nodes
      .sort((a, b) => {
        if (a.type !== b.type) return a.type === 'dir' ? -1 : 1;
        return a.name.localeCompare(b.name);
      })
      .map(n => n.children ? { ...n, children: sortNodes(n.children) } : n);
  };

  return sortNodes(root);
}

// ═══ 获取默认展开目录（前两层） ═══
function getDefaultExpanded(nodes: FileTreeNode[], depth = 0): Set<string> {
  const set = new Set<string>();
  if (depth >= 2) return set;
  for (const node of nodes) {
    if (node.type === 'dir') {
      set.add(node.path);
      if (node.children) {
        for (const p of getDefaultExpanded(node.children, depth + 1)) {
          set.add(p);
        }
      }
    }
  }
  return set;
}

// ═══ 变更标记 Badge ═══
function ChangeBadge({ changeType }: { changeType?: string }) {
  if (!changeType) return null;
  const config: Record<string, { label: string; className: string }> = {
    added: { label: 'A', className: 'text-emerald-400' },
    modified: { label: 'M', className: 'text-amber-400' },
    deleted: { label: 'D', className: 'text-red-400' },
  };
  const c = config[changeType];
  if (!c) return null;
  return (
    <span className={`ml-auto text-[10px] font-bold ${c.className} flex-shrink-0`}>
      [{c.label}]
    </span>
  );
}

// ═══ 树节点渲染 ═══
function TreeNodeItem({
  node,
  depth,
  expandedDirs,
  selectedFilePath,
  onToggle,
  onFileClick,
}: {
  node: FileTreeNode;
  depth: number;
  expandedDirs: Set<string>;
  selectedFilePath: string | null;
  onToggle: (path: string) => void;
  onFileClick: (path: string) => void;
}) {
  const isExpanded = expandedDirs.has(node.path);
  const isSelected = node.type === 'file' && selectedFilePath === node.path;
  const paddingLeft = 8 + depth * 16;

  if (node.type === 'dir') {
    return (
      <>
        <button
          onClick={() => onToggle(node.path)}
          className="w-full flex items-center gap-1.5 py-[5px] pr-2 hover:bg-[var(--bg-hover)] transition-colors text-left"
          style={{ paddingLeft }}
        >
          {isExpanded
            ? <ChevronDown className="w-3 h-3 text-[var(--text-muted)] flex-shrink-0" />
            : <ChevronRight className="w-3 h-3 text-[var(--text-muted)] flex-shrink-0" />
          }
          {isExpanded
            ? <FolderOpen className="w-3.5 h-3.5 text-[var(--text-secondary)] flex-shrink-0" />
            : <Folder className="w-3.5 h-3.5 text-[var(--text-secondary)] flex-shrink-0" />
          }
          <span className="text-xs text-[var(--text-primary)] truncate">{node.name}</span>
        </button>
        {isExpanded && node.children?.map(child => (
          <TreeNodeItem
            key={child.path}
            node={child}
            depth={depth + 1}
            expandedDirs={expandedDirs}
            selectedFilePath={selectedFilePath}
            onToggle={onToggle}
            onFileClick={onFileClick}
          />
        ))}
      </>
    );
  }

  return (
    <div
      onClick={() => onFileClick(node.path)}
      className={`flex items-center gap-1.5 py-[5px] pr-2 transition-colors cursor-pointer group
        ${isSelected ? 'bg-blue-500/10 border-l-2 border-l-blue-500' : 'hover:bg-[var(--bg-hover)]'}`}
      style={{ paddingLeft: paddingLeft + 16 }}
      title={`点击查看: ${node.path}`}
    >
      <FileText className={`w-3.5 h-3.5 flex-shrink-0 ${isSelected ? 'text-blue-400' : 'text-[var(--text-muted)] group-hover:text-blue-400'}`} />
      <span className={`text-xs truncate flex-1 min-w-0 ${isSelected ? 'text-blue-300 font-medium' : 'text-[var(--text-primary)] group-hover:text-blue-300'}`}>{node.name}</span>
      <ChangeBadge changeType={node.changeType} />
    </div>
  );
}

// ═══ 文件详情面板（含 diff 内容） ═══
function FileDetailPanel({ file, onViewActivity }: { file: FileChange; onViewActivity?: () => void }) {
  const diffLines = file.diffContent ? file.diffContent.split('\n') : null;
  const maxLines = 100;
  const truncated = diffLines && diffLines.length > maxLines;
  const displayLines = diffLines ? (truncated ? diffLines.slice(0, maxLines) : diffLines) : null;

  return (
    <div className="px-3 py-2 border-t border-[var(--border)] bg-[var(--bg-secondary)]">
      <p className="text-[11px] font-mono text-[var(--text-primary)] truncate mb-1" title={file.filePath}>
        {file.filePath}
      </p>
      <div className="flex items-center gap-3 text-[10px] mb-2">
        <span className="text-green-500">+{file.additions}</span>
        <span className="text-red-500">-{file.deletions}</span>
        <span className={`px-1 py-0.5 rounded font-medium ${
          file.changeType === 'added' ? 'bg-green-500/15 text-green-600 dark:text-green-400' :
          file.changeType === 'deleted' ? 'bg-red-500/15 text-red-600 dark:text-red-400' :
          'bg-blue-500/15 text-blue-600 dark:text-blue-400'
        }`}>
          {file.changeType ?? 'modified'}
        </span>
        {onViewActivity && (
          <button
            onClick={onViewActivity}
            className="ml-auto text-[10px] text-blue-500 hover:text-blue-400 hover:underline transition-colors"
          >
            查看详情 →
          </button>
        )}
      </div>
      {/* Diff 内容展示 */}
      {displayLines ? (
        <div className="bg-[var(--code-bg)] rounded border border-[var(--border)] overflow-hidden">
          <div className="max-h-[160px] overflow-y-auto overflow-x-auto">
            <pre className="text-[10px] font-mono leading-[1.5] p-1.5 m-0">
              {displayLines.map((line, i) => {
                let lineClass = 'text-[var(--text-secondary)]';
                if (line.startsWith('+ ')) lineClass = 'text-emerald-400 bg-emerald-500/10';
                else if (line.startsWith('- ')) lineClass = 'text-red-400 bg-red-500/10';
                return (
                  <div key={i} className={lineClass}>
                    <span className="whitespace-pre">{line}</span>
                  </div>
                );
              })}
            </pre>
          </div>
          {truncated && (
            <div className="px-2 py-0.5 text-[9px] text-[var(--text-muted)] border-t border-[var(--border)]">
              … 剩余 {diffLines!.length - maxLines} 行
            </div>
          )}
        </div>
      ) : (
        <div className="text-[10px] text-[var(--text-muted)] italic">
          无预览内容
        </div>
      )}
    </div>
  );
}

// ═══ 主组件 ═══
export function SessionFileExplorer() {
  const activities = useActivityStore((s) => s.activities);
  const setL3ActivityId = useActivityStore((s) => s.setL3ActivityId);
  const currentSessionId = useSessionStore((s) => s.sessionId);

  // 聚合当前会话的 changedFiles（同路径取最新）
  const allChangedFiles = useMemo(() => {
    const fileMap = new Map<string, FileChange>();
    activities.forEach(activity => {
      if (activity.sessionId !== currentSessionId) return;
      activity.changedFiles?.forEach(file => {
        fileMap.set(file.filePath, file);
      });
    });
    return Array.from(fileMap.values());
  }, [activities, currentSessionId]);

  // 构建树形结构
  const fileTree = useMemo(() => buildFileTree(allChangedFiles), [allChangedFiles]);

  // 默认展开前两层目录
  const [expandedDirs, setExpandedDirs] = useState<Set<string>>(() => getDefaultExpanded(fileTree));

  // 文件选中状态
  const [selectedFilePath, setSelectedFilePath] = useState<string | null>(null);

  // 面板折叠状态
  const [collapsed, setCollapsed] = useState(false);

  const toggleDir = useCallback((path: string) => {
    setExpandedDirs(prev => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  }, []);

  // 文件点击处理
  const handleFileClick = useCallback((path: string) => {
    setSelectedFilePath(prev => prev === path ? null : path);
  }, []);

  // 获取选中文件的详细信息
  const selectedFileData = useMemo(() => {
    if (!selectedFilePath) return null;
    return allChangedFiles.find(f => f.filePath === selectedFilePath) ?? null;
  }, [selectedFilePath, allChangedFiles]);

  // 查找包含该文件的 Activity（以便导航到 L3 详情）
  const selectedFileActivityId = useMemo(() => {
    if (!selectedFilePath) return null;
    // 找到最新的包含该文件的 activity
    let found: string | null = null;
    let latestTime = 0;
    activities.forEach(activity => {
      if (activity.sessionId !== currentSessionId) return;
      if (activity.changedFiles?.some(f => f.filePath === selectedFilePath)) {
        if (activity.timestamp > latestTime) {
          latestTime = activity.timestamp;
          found = activity.id;
        }
      }
    });
    return found;
  }, [selectedFilePath, activities, currentSessionId]);

  // 统计摘要
  const summary = useMemo(() => {
    let added = 0, modified = 0, deleted = 0;
    for (const f of allChangedFiles) {
      if (f.changeType === 'added') added++;
      else if (f.changeType === 'deleted') deleted++;
      else modified++;
    }
    const parts: string[] = [];
    if (modified > 0) parts.push(`${modified} modified`);
    if (added > 0) parts.push(`${added} added`);
    if (deleted > 0) parts.push(`${deleted} deleted`);
    return parts.length > 0
      ? `${allChangedFiles.length} files — ${parts.join(', ')}`
      : '';
  }, [allChangedFiles]);

  return (
    <div className="flex flex-col border-b border-[var(--border)] flex-shrink-0">
      {/* Header */}
      <button
        onClick={() => setCollapsed(!collapsed)}
        className="flex items-center gap-1.5 px-3 py-2 hover:bg-[var(--bg-hover)] transition-colors w-full text-left"
      >
        {collapsed
          ? <ChevronRight className="w-3 h-3 text-[var(--text-muted)]" />
          : <ChevronDown className="w-3 h-3 text-[var(--text-muted)]" />
        }
        <span className="text-[11px] font-semibold text-[var(--text-secondary)] uppercase tracking-wider">
          受影响文件
        </span>
        {allChangedFiles.length > 0 && (
          <span className="text-[10px] bg-[var(--bg-hover)] text-[var(--text-primary)] px-1.5 py-0.5 rounded-sm ml-auto">
            {allChangedFiles.length}
          </span>
        )}
      </button>

      {/* Content */}
      {!collapsed && (
        <>
          <div className="max-h-[200px] overflow-y-auto">
            {fileTree.length === 0 ? (
              <p className="text-[var(--text-muted)] text-xs text-center py-4">暂无文件变更</p>
            ) : (
              fileTree.map(node => (
                <TreeNodeItem
                  key={node.path}
                  node={node}
                  depth={0}
                  expandedDirs={expandedDirs}
                  selectedFilePath={selectedFilePath}
                  onToggle={toggleDir}
                  onFileClick={handleFileClick}
                />
              ))
            )}
          </div>
          {/* Selected File Detail Panel */}
          {selectedFileData && (
            <FileDetailPanel
              file={selectedFileData}
              onViewActivity={selectedFileActivityId ? () => setL3ActivityId(selectedFileActivityId) : undefined}
            />
          )}
          {/* Footer summary */}
          {summary && (
            <div className="px-3 py-1.5 text-[10px] text-[var(--text-muted)]">
              {summary}
            </div>
          )}
        </>
      )}
    </div>
  );
}
