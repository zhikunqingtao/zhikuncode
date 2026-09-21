import { useCallback, useEffect, useMemo, useState } from 'react';
import { Brain, Check, FileText, LayoutGrid, Loader2, Plus, RefreshCw, Save, Trash2, X } from 'lucide-react';
import { useMemoryStore, type MemoryEntry } from '@/store/memoryStore';
import { usePageExitGuard } from '@/hooks/usePageExitGuard';
import { MemoryEditorPanel } from './MemoryEditorPanel';

interface MemoryPageProps {
  onClose: () => void;
}

type ViewMode = 'cards' | 'raw';

const MODE_OPTIONS: { id: ViewMode; label: string; icon: typeof LayoutGrid }[] = [
  { id: 'cards', label: '卡片', icon: LayoutGrid },
  { id: 'raw', label: '整篇', icon: FileText },
];

const SOURCE_BADGE: Record<MemoryEntry['source'], string> = {
  AUTO: 'bg-[var(--v2-bg-hover)] text-[var(--v2-text-2)]',
  USER: 'bg-accent2-soft text-accent2-ink',
  TOOL: 'bg-oksoft text-ok',
};

const formatSize = (bytes: number): string =>
  bytes < 1024 ? `${bytes} B` : `${(bytes / 1024).toFixed(1)} KB`;

const formatTime = (iso: string): string => {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
};

/** 内容自适应高度的 textarea：挂载与输入时按 scrollHeight 重算。模块级定义保证 ref 身份稳定。 */
const autoResize = (el: HTMLTextAreaElement | null) => {
  if (!el) return;
  el.style.height = 'auto';
  el.style.height = `${el.scrollHeight}px`;
};

/**
 * 浏览器刷新/关闭守卫桥：usePageExitGuard 无 enabled 参数、挂载即生效，
 * 故仅在 dirty 时条件挂载本组件（与 App 的 MobileKeyboardBridge 同一模式），
 * 有未保存修改时拦截浏览器刷新/关闭标签页。
 */
const DirtyPageExitGuard = () => {
  usePageExitGuard();
  return null;
};

export function MemoryPage({ onClose }: MemoryPageProps) {
  const {
    content, entries, updatedAt, size, maxSize,
    loading, saving, loaded, error, conflict,
    loadFile, saveRaw, saveEntries, setDirty,
  } = useMemoryStore();
  const [mode, setMode] = useState<ViewMode>('cards');
  const [editedEntries, setEditedEntries] = useState<MemoryEntry[]>(entries);
  const [rawContent, setRawContent] = useState(content);
  const [savedHint, setSavedHint] = useState(false);

  useEffect(() => { void loadFile(); }, [loadFile]);

  // 服务端数据变化（首次加载 / 冲突后刷新 / 保存后规范化）时同步本地编辑态
  useEffect(() => { setEditedEntries(entries); }, [entries]);
  useEffect(() => { setRawContent(content); }, [content]);

  // dirty 派生：当前模式的本地编辑态与已保存态的差异，同步进 store 供全局读取
  const cardsDirty = useMemo(
    () => JSON.stringify(editedEntries) !== JSON.stringify(entries),
    [editedEntries, entries],
  );
  const rawDirty = rawContent !== content;
  const dirtyNow = mode === 'cards' ? cardsDirty : rawDirty;
  useEffect(() => { setDirty(dirtyNow); }, [dirtyNow, setDirty]);

  // 保存成功提示（2 秒自动消失）
  useEffect(() => {
    if (!savedHint) return;
    const timer = window.setTimeout(() => setSavedHint(false), 2000);
    return () => window.clearTimeout(timer);
  }, [savedHint]);

  const confirmDiscard = useCallback(
    () => !dirtyNow || window.confirm('有未保存的修改，确定要离开吗？'),
    [dirtyNow],
  );

  const switchMode = useCallback((next: ViewMode) => {
    if (next === mode || !confirmDiscard()) return;
    // 丢弃本地未保存修改，回到已保存态
    setEditedEntries(entries);
    setRawContent(content);
    setMode(next);
  }, [mode, confirmDiscard, entries, content]);

  const handleClose = useCallback(() => {
    if (confirmDiscard()) onClose();
  }, [confirmDiscard, onClose]);

  const handleRefresh = useCallback(() => {
    if (confirmDiscard()) void loadFile();
  }, [confirmDiscard, loadFile]);

  const handleSave = useCallback(async () => {
    const ok = mode === 'cards' ? await saveEntries(editedEntries) : await saveRaw(rawContent);
    if (ok) setSavedHint(true);
  }, [mode, editedEntries, rawContent, saveEntries, saveRaw]);

  const updateEntry = useCallback((index: number, next: Partial<MemoryEntry>) => {
    setEditedEntries(prev => prev.map((entry, i) => (i === index ? { ...entry, ...next } : entry)));
  }, []);

  const removeEntry = useCallback((index: number) => {
    if (!window.confirm('确定删除这条记忆吗？')) return;
    setEditedEntries(prev => prev.filter((_, i) => i !== index));
  }, []);

  const addEntry = useCallback(() => {
    setEditedEntries(prev => [...prev, {
      source: 'USER',
      category: 'semantic',
      timestamp: new Date().toISOString(),
      content: '',
    }]);
  }, []);

  const usageRatio = maxSize > 0 ? size / maxSize : 0;
  const nearLimit = usageRatio > 0.8;
  const saveDisabled = saving || loading || conflict || !dirtyNow;
  const saveButton = (
    <button
      onClick={() => void handleSave()}
      disabled={saveDisabled}
      className={`panel-control inline-flex min-h-11 md:min-h-0 items-center gap-1.5 rounded-[10px] px-3 py-2 text-sm transition-interactive duration-fast
        focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring disabled:cursor-not-allowed disabled:opacity-50
        ${dirtyNow && !conflict ? 'bg-accent2-strong text-white hover:bg-accent2' : 'bg-[var(--v2-bg-hover)] text-[var(--v2-text-2)]'}`}
      aria-label="保存记忆"
    >
      {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
      保存
    </button>
  );

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-overlay2 backdrop-blur-[3px] max-md:p-0 md:p-3">
      {dirtyNow && <DirtyPageExitGuard />}
      <div className="flex h-full w-full flex-col overflow-hidden border border-hairline bg-surfacev2 shadow-e4 motion-safe:animate-scale-in md:h-[88vh] md:max-w-[900px] md:rounded-panel max-md:border-0">
        {/* 顶栏：标题 + 模式切换 + 保存 + 关闭 */}
        <header className="flex items-center justify-between gap-2 border-b border-[var(--v2-border-hairline)] px-4 md:px-6 py-4">
          <div className="flex min-w-0 items-center gap-2">
            <Brain className="h-5 w-5 shrink-0 text-accent2-ink" aria-hidden="true" />
            <h2 className="text-[var(--v2-text-1)] text-xl font-semibold whitespace-nowrap">记忆</h2>
            <div className="ml-1 flex rounded-[10px] border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-sunken)] p-0.5" role="tablist" aria-label="记忆视图模式">
              {MODE_OPTIONS.map(({ id, label, icon: Icon }) => (
                <button
                  key={id}
                  role="tab"
                  aria-selected={mode === id}
                  onClick={() => switchMode(id)}
                  className={`flex min-h-11 md:min-h-9 items-center gap-1 rounded-[8px] px-3 text-sm transition-colors
                    focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring
                    ${mode === id ? 'bg-surfacev2 text-[var(--v2-text-1)] shadow-e1' : 'text-[var(--v2-text-2)] hover:text-[var(--v2-text-1)]'}`}
                >
                  <Icon className="h-4 w-4" aria-hidden="true" />
                  {label}
                </button>
              ))}
            </div>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            {savedHint && (
              <span className="flex items-center gap-1 text-sm text-ok" role="status">
                <Check className="h-4 w-4" aria-hidden="true" />已保存
              </span>
            )}
            <span className="max-md:hidden">{saveButton}</span>
            <button
              onClick={handleRefresh}
              disabled={loading}
              className="dialog-control rounded-[10px] border border-[var(--v2-border-hairline)] p-2 max-md:min-h-11 max-md:min-w-11 text-[var(--v2-text-2)] hover:bg-[var(--v2-bg-hover)] disabled:opacity-50"
              aria-label="刷新记忆"
              title="刷新"
            >
              <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
            </button>
            <button
              onClick={handleClose}
              className="dialog-control rounded-[10px] p-2 max-md:min-h-11 max-md:min-w-11 text-[var(--v2-text-2)] hover:bg-[var(--v2-bg-hover)]"
              aria-label="关闭记忆页面"
            >
              <X className="h-5 w-5" />
            </button>
          </div>
        </header>

        {/* 冲突警示条 */}
        {conflict && (
          <div className="flex items-center justify-between gap-3 border-b border-warnsoft bg-warnsoft px-4 md:px-6 py-2.5 text-sm text-warnstrong dark:text-warn" role="alert">
            <span>内容已在别处被修改，请刷新后继续编辑</span>
            <button
              onClick={handleRefresh}
              className="panel-control shrink-0 rounded-[8px] border border-current px-3 py-1.5 min-h-11 md:min-h-0 text-[13px] hover:opacity-80"
            >
              刷新
            </button>
          </div>
        )}

        {/* 错误提示 */}
        {error && (
          <p className="border-b border-[var(--v2-border-hairline)] bg-errsoft px-4 md:px-6 py-2.5 text-sm text-err" role="alert">
            加载或保存失败：{error}
          </p>
        )}

        {/* 内容区 */}
        <main className="flex-1 overflow-y-auto px-4 md:px-6 py-4">
          {loading && !loaded ? (
            <div className="flex h-full flex-col items-center justify-center gap-3 py-16 text-sm text-[var(--v2-text-2)]">
              <Loader2 className="h-6 w-6 animate-spin" aria-hidden="true" />
              正在读取记忆文件…
            </div>
          ) : mode === 'raw' ? (
            <div className="h-full">
              <MemoryEditorPanel content={rawContent} onChange={setRawContent} fileName="MEMORY.md" dirty={rawDirty} />
            </div>
          ) : editedEntries.length === 0 ? (
            <div className="flex h-full flex-col items-center justify-center gap-3 py-16 text-center">
              <Brain className="h-10 w-10 text-[var(--v2-text-2)]" aria-hidden="true" />
              <p className="text-sm text-[var(--v2-text-2)]">暂无记忆，AI 会在对话中自动记录，你也可以手动添加</p>
              <button
                onClick={addEntry}
                className="panel-control inline-flex min-h-11 items-center gap-1.5 rounded-[10px] border border-[var(--v2-border-hairline)] px-4 py-2 text-sm text-[var(--v2-text-1)] hover:bg-[var(--v2-bg-hover)]"
              >
                <Plus className="h-4 w-4" aria-hidden="true" />新增条目
              </button>
            </div>
          ) : (
            <div className="space-y-3">
              {editedEntries.map((entry, index) => (
                <article key={index} className="rounded-[14px] border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-sunken)] p-4">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className={`rounded px-2 py-0.5 text-[13px] font-medium ${SOURCE_BADGE[entry.source] ?? SOURCE_BADGE.AUTO}`}>
                      {entry.source}
                    </span>
                    <span className="rounded bg-[var(--v2-bg-hover)] px-2 py-0.5 text-[13px] text-[var(--v2-text-2)]">{entry.category}</span>
                    <time className="text-[13px] text-[var(--v2-text-2)]">{formatTime(entry.timestamp)}</time>
                    <button
                      onClick={() => removeEntry(index)}
                      className="dialog-control ml-auto rounded-[8px] p-2 max-md:min-h-11 max-md:min-w-11 text-[var(--v2-text-2)] hover:bg-errsoft hover:text-err"
                      aria-label={`删除记忆条目 ${index + 1}`}
                      title="删除"
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  </div>
                  <textarea
                    ref={autoResize}
                    value={entry.content}
                    onChange={e => { updateEntry(index, { content: e.target.value }); autoResize(e.target); }}
                    rows={1}
                    className="mt-2 w-full resize-none overflow-hidden rounded-[10px] border border-transparent bg-transparent p-2 text-sm leading-relaxed text-[var(--v2-text-1)] outline-none focus:border-[var(--v2-border-hairline)] focus:bg-[var(--v2-bg-surface)]"
                    placeholder="记录一条记忆…"
                    aria-label={`记忆条目 ${index + 1} 内容`}
                  />
                </article>
              ))}
              <button
                onClick={addEntry}
                className="panel-control flex min-h-11 w-full items-center justify-center gap-2 rounded-[14px] border border-dashed border-[var(--v2-border-hairline)] py-3 text-sm text-[var(--v2-text-2)] hover:bg-[var(--v2-bg-hover)] hover:text-[var(--v2-text-1)]"
              >
                <Plus className="h-4 w-4" aria-hidden="true" />新增条目
              </button>
            </div>
          )}
        </main>

        {/* 底栏：文件用量（移动端含固定保存按钮） */}
        <footer className="flex items-center justify-between gap-3 border-t border-[var(--v2-border-hairline)] px-4 md:px-6 py-3">
          <span className={`text-[13px] ${nearLimit ? 'text-warnstrong dark:text-warn' : 'text-[var(--v2-text-2)]'}`}>
            文件大小 {formatSize(size)}{maxSize > 0 ? ` / ${formatSize(maxSize)}` : ''}
            {nearLimit && '（接近上限）'}
          </span>
          {updatedAt && (
            <span className="hidden md:inline text-[13px] text-[var(--v2-text-2)]">更新于 {formatTime(updatedAt)}</span>
          )}
          <span className="md:hidden ml-auto">{saveButton}</span>
        </footer>
      </div>
    </div>
  );
}

export default MemoryPage;
