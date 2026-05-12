import { useEffect, useCallback, useState, Suspense, lazy } from 'react';
import { createPortal } from 'react-dom';
import { X, Check, ChevronDown, ChevronRight, FileCode, Terminal } from 'lucide-react';
import type { ActivityData, RiskAssessment, FileChange } from '@/types/apos';
import { computeButtonDisabled } from '@/types/apos';
import { SignalBadge } from './SignalBadge';
import { VerificationIcon } from './VerificationIcon';
import { OperationIcon } from './OperationIcon';
import { useMessageStore } from '@/store/messageStore';

const MonacoDiffEditor = lazy(() =>
  import('@monaco-editor/react').then((mod) => ({ default: mod.DiffEditor }))
);

interface ActivityCardL3Props {
  activity: ActivityData;
  assessment?: RiskAssessment;
  onClose: () => void;
  onApprove: () => void;
  onReject: () => void;
}

interface ToolSectionProps {
  title: string;
  passed: boolean;
  details: string;
  defaultOpen?: boolean;
}

function ToolSection({ title, passed, details, defaultOpen = false }: ToolSectionProps) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className="border border-[var(--border)] rounded">
      <button
        onClick={() => setOpen(!open)}
        className="w-full flex items-center gap-2 px-3 py-2 text-xs hover:bg-[var(--bg-hover)] transition-colors"
      >
        {open ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
        <VerificationIcon status={passed ? 'all_pass' : 'has_error'} size={14} />
        <span className="text-[var(--text-primary)] font-medium">{title}</span>
      </button>
      {open && (
        <div className="px-3 pb-2">
          <pre className="text-xs text-[var(--text-secondary)] whitespace-pre-wrap font-mono bg-[var(--code-bg)] rounded p-2 max-h-[120px] overflow-y-auto">
            {details || 'No details available'}
          </pre>
        </div>
      )}
    </div>
  );
}

function DiffFallback({ original, modified }: { original: string; modified: string }) {
  return (
    <div className="grid grid-cols-2 gap-2">
      <div>
        <p className="text-xs text-[var(--text-muted)] mb-1">Original</p>
        <pre className="text-xs text-[var(--text-primary)] font-mono bg-[var(--code-bg)] rounded p-2 max-h-[300px] overflow-auto whitespace-pre-wrap">
          {original || '(empty)'}
        </pre>
      </div>
      <div>
        <p className="text-xs text-[var(--text-muted)] mb-1">Modified</p>
        <pre className="text-xs text-[var(--text-primary)] font-mono bg-[var(--code-bg)] rounded p-2 max-h-[300px] overflow-auto whitespace-pre-wrap">
          {modified || '(empty)'}
        </pre>
      </div>
    </div>
  );
}

/** 文件 diff 内容预览组件 */
function FileDiffPreview({ file }: { file: FileChange }) {
  if (!file.diffContent) {
    return (
      <div className="px-3 py-2 text-xs text-[var(--text-muted)] italic">
        无预览内容
      </div>
    );
  }

  const lines = file.diffContent.split('\n');
  // 限制显示行数避免 DOM 过大
  const maxLines = 200;
  const truncated = lines.length > maxLines;
  const displayLines = truncated ? lines.slice(0, maxLines) : lines;

  return (
    <div className="bg-[var(--code-bg)] rounded border border-[var(--border)] mt-1 overflow-hidden">
      <div className="max-h-[280px] overflow-y-auto overflow-x-auto">
        <pre className="text-[11px] font-mono leading-[1.6] p-2 m-0">
          {displayLines.map((line, i) => {
            let lineClass = 'text-[var(--text-secondary)]';
            if (line.startsWith('+ ')) lineClass = 'text-emerald-400 bg-emerald-500/10';
            else if (line.startsWith('- ')) lineClass = 'text-red-400 bg-red-500/10';
            return (
              <div key={i} className={`flex ${lineClass}`}>
                <span className="text-[var(--text-muted)] w-8 text-right mr-2 select-none flex-shrink-0 opacity-50">
                  {i + 1}
                </span>
                <span className="whitespace-pre">{line}</span>
              </div>
            );
          })}
        </pre>
      </div>
      {truncated && (
        <div className="px-3 py-1 text-[10px] text-[var(--text-muted)] border-t border-[var(--border)] bg-[var(--bg-secondary)]">
          … 剩余 {lines.length - maxLines} 行未显示
        </div>
      )}
    </div>
  );
}

/** 终端风格命令输出查看器 */
interface CommandOutputViewerProps {
  output: string;
  isError: boolean;
  command?: string;
  metadata?: Record<string, unknown>;
}

function CommandOutputViewer({ output, isError, command, metadata }: CommandOutputViewerProps) {
  const lines = output.split('\n');
  const maxLines = 200;
  const truncated = lines.length > maxLines;
  const displayLines = truncated ? lines.slice(0, maxLines) : lines;
  const exitCode = metadata?.exitCode as number | undefined;

  return (
    <div className="rounded-lg overflow-hidden border border-[var(--border-primary)]">
      {/* 终端标题栏 */}
      <div className="flex items-center gap-2 px-3 py-2 bg-[var(--bg-tertiary)] border-b border-[var(--border-primary)]">
        <div className="flex gap-1.5">
          <span className="w-3 h-3 rounded-full bg-red-500/80"></span>
          <span className="w-3 h-3 rounded-full bg-yellow-500/80"></span>
          <span className="w-3 h-3 rounded-full bg-green-500/80"></span>
        </div>
        {command && (
          <span className="text-xs font-mono text-[var(--text-muted)] ml-2 truncate max-w-[60%]">
            $ {command}
          </span>
        )}
        {isError ? (
          <span className="ml-auto text-xs text-red-400 font-medium flex items-center gap-1">
            <Terminal size={12} />
            {exitCode !== undefined ? `EXIT ${exitCode}` : 'EXIT ERROR'}
          </span>
        ) : (
          <span className="ml-auto text-xs text-green-400 font-medium flex items-center gap-1">
            <Terminal size={12} />
            {exitCode !== undefined ? `EXIT ${exitCode}` : 'EXIT 0'}
          </span>
        )}
      </div>
      {/* 终端内容区 */}
      <div className="bg-[#1a1b26] p-3 overflow-x-auto max-h-[400px] overflow-y-auto">
        <pre className="text-xs font-mono leading-5 whitespace-pre-wrap m-0">
          {displayLines.map((line, i) => (
            <div key={i} className={isError ? 'text-red-300' : 'text-gray-200'}>
              {line || '\u00A0'}
            </div>
          ))}
        </pre>
        {truncated && (
          <div className="text-xs text-yellow-400 mt-2 pt-2 border-t border-gray-700">
            ... 输出已截断（共 {lines.length} 行，仅显示前 {maxLines} 行）
          </div>
        )}
      </div>
    </div>
  );
}

/** 文件列表（可展开显示 diff） */
function FileListWithDiff({ files }: { files: FileChange[] }) {
  const [expandedFile, setExpandedFile] = useState<string | null>(null);

  return (
    <div className="space-y-1">
      {files.map((file) => {
        const isExpanded = expandedFile === file.filePath;
        const hasDiff = !!file.diffContent;
        return (
          <div key={file.filePath}>
            <div
              onClick={() => setExpandedFile(isExpanded ? null : file.filePath)}
              className={`flex items-center gap-2 px-2 py-1.5 rounded text-xs cursor-pointer transition-colors ${
                isExpanded ? 'bg-[var(--bg-hover)]' : 'hover:bg-[var(--bg-hover)]'
              }`}
            >
              {hasDiff ? (
                isExpanded
                  ? <ChevronDown size={12} className="text-[var(--text-muted)] flex-shrink-0" />
                  : <ChevronRight size={12} className="text-[var(--text-muted)] flex-shrink-0" />
              ) : (
                <FileCode size={12} className="text-[var(--text-muted)] flex-shrink-0" />
              )}
              <span className="text-[var(--text-primary)] font-mono truncate flex-1">
                {file.filePath}
              </span>
              <span className="text-green-400">+{file.additions}</span>
              <span className="text-red-400">-{file.deletions}</span>
              <span className={`px-1.5 py-0.5 rounded text-[10px] font-medium ${
                file.changeType === 'added' ? 'bg-green-500/20 text-green-300' :
                file.changeType === 'deleted' ? 'bg-red-500/20 text-red-300' :
                'bg-blue-500/20 text-blue-300'
              }`}>
                {file.changeType ?? 'modified'}
              </span>
            </div>
            {isExpanded && <FileDiffPreview file={file} />}
          </div>
        );
      })}
    </div>
  );
}

export function ActivityCardL3({
  activity,
  assessment,
  onClose,
  onApprove,
  onReject,
}: ActivityCardL3Props) {
  const handleEscape = useCallback(
    (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    },
    [onClose]
  );

  useEffect(() => {
    document.addEventListener('keydown', handleEscape);
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', handleEscape);
      document.body.style.overflow = '';
    };
  }, [handleEscape]);

  // 从 messageStore 获取 toolCall result（备用降级）
  const toolCallResult = useMessageStore((state) => {
    const tc = state.activeToolCalls.get(activity.id);
    return tc?.result;
  });

  // 优先使用 activity.toolResult，降级到 messageStore
  const resultData = activity.toolResult || (toolCallResult ? {
    content: toolCallResult.content,
    isError: toolCallResult.isError,
    metadata: toolCallResult.metadata,
  } : null);

  const signal = activity.insight?.signal
    ?? (activity.status === 'completed' || activity.status === 'error' ? 'unavailable' : 'loading');
  const hasMonaco = typeof window !== 'undefined';

  const content = (
    <div className="fixed inset-0 z-30 flex items-center justify-center">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/60 backdrop-blur-sm"
        onClick={onClose}
      />

      {/* Panel */}
      <div className="relative z-40 w-full max-w-[900px] max-h-[80vh] bg-[var(--bg-primary)] rounded-lg border border-[var(--border)] shadow-2xl flex flex-col mx-4">
        {/* Header */}
        <div className="flex items-center gap-3 px-5 py-4 border-b border-[var(--border)] flex-shrink-0">
          <OperationIcon type={activity.operationType} size={20} />
          <h2 className="text-sm font-medium text-[var(--text-primary)] flex-1 truncate">
            {activity.summary}
          </h2>
          <SignalBadge signal={signal} size="md" reason={assessment?.reason} />
          <button
            onClick={onClose}
            className="p-1.5 rounded hover:bg-[var(--bg-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
          >
            <X size={18} />
          </button>
        </div>

        {/* Scrollable Body */}
        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-5">
          {/* 命令执行类：显示命令输出 */}
          {activity.operationType === 'command_execute' && resultData && (
            <section>
              <h3 className="text-xs font-semibold text-[var(--text-muted)] uppercase tracking-wide mb-2">
                命令输出
              </h3>
              <CommandOutputViewer
                output={resultData.content}
                isError={resultData.isError}
                command={activity.summary.replace(/^执行\s*/, '')}
                metadata={resultData.metadata}
              />
            </section>
          )}

          {/* 命令执行但无结果（尚未完成） */}
          {activity.operationType === 'command_execute' && !resultData && (
            <section>
              <h3 className="text-xs font-semibold text-[var(--text-muted)] uppercase tracking-wide mb-2">
                命令输出
              </h3>
              <div className="flex items-center gap-2 text-sm text-[var(--text-muted)] py-4">
                <div className="w-4 h-4 border-2 border-[var(--text-muted)] border-t-transparent rounded-full animate-spin"></div>
                等待命令执行完成...
              </div>
            </section>
          )}

          {/* 文件操作类：显示变更文件列表 */}
          {activity.changedFiles.length > 0 && (
            <section>
              <h3 className="text-xs font-semibold text-[var(--text-muted)] uppercase tracking-wide mb-2">
                变更文件列表
              </h3>
              <FileListWithDiff files={activity.changedFiles} />
            </section>
          )}

          {/* 非命令执行且无文件变更时显示空状态 */}
          {activity.operationType !== 'command_execute' && activity.changedFiles.length === 0 && (
            <section>
              <h3 className="text-xs font-semibold text-[var(--text-muted)] uppercase tracking-wide mb-2">
                变更文件列表
              </h3>
              <div className="text-sm text-[var(--text-muted)] py-2">无文件变更</div>
            </section>
          )}

          {/* Verification Logs */}
          {assessment && (
            <section>
              <h3 className="text-xs font-semibold text-[var(--text-muted)] uppercase tracking-wide mb-2">
                验证日志
              </h3>
              <div className="space-y-1.5">
                <ToolSection
                  title={`TypeScript — ${assessment.deterministic.typeCheck.passed ? '通过' : `${assessment.deterministic.typeCheck.errorCount} 错误`}`}
                  passed={assessment.deterministic.typeCheck.passed}
                  details={assessment.deterministic.typeCheck.details ?? ''}
                />
                <ToolSection
                  title={`ESLint — ${assessment.deterministic.lint.passed ? '通过' : `${assessment.deterministic.lint.errorCount} 错误, ${assessment.deterministic.lint.warningCount} 警告`}`}
                  passed={assessment.deterministic.lint.passed}
                  details={`Errors: ${assessment.deterministic.lint.errorCount}, Warnings: ${assessment.deterministic.lint.warningCount}`}
                />
                <ToolSection
                  title={`Tests — ${assessment.deterministic.tests.passedCount} 通过 / ${assessment.deterministic.tests.failedCount} 失败`}
                  passed={assessment.deterministic.tests.passed}
                  details={`Passed: ${assessment.deterministic.tests.passedCount}, Failed: ${assessment.deterministic.tests.failedCount}${assessment.deterministic.tests.coveragePercent != null ? `, Coverage: ${assessment.deterministic.tests.coveragePercent}%` : ''}`}
                />
              </div>
            </section>
          )}

          {/* Diff Preview */}
          {activity.originalContent != null && activity.modifiedContent != null && (
            <section>
              <h3 className="text-xs font-semibold text-[var(--text-muted)] uppercase tracking-wide mb-2">
                Diff 预览
              </h3>
              {hasMonaco ? (
                <Suspense
                  fallback={
                    <DiffFallback
                      original={activity.originalContent ?? ''}
                      modified={activity.modifiedContent ?? ''}
                    />
                  }
                >
                  <div className="h-[300px] border border-[var(--border)] rounded overflow-hidden">
                    <MonacoDiffEditor
                      original={activity.originalContent}
                      modified={activity.modifiedContent}
                      language="typescript"
                      theme="vs-dark"
                      options={{
                        readOnly: true,
                        minimap: { enabled: false },
                        fontSize: 12,
                        scrollBeyondLastLine: false,
                        renderSideBySide: true,
                      }}
                    />
                  </div>
                </Suspense>
              ) : (
                <DiffFallback
                  original={activity.originalContent ?? ''}
                  modified={activity.modifiedContent ?? ''}
                />
              )}
            </section>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center gap-2 px-5 py-3 border-t border-[var(--border)] flex-shrink-0">
          {activity.decision ? (
            <span className={`inline-flex items-center gap-1.5 px-4 py-2 text-xs font-medium rounded ${
              activity.decision === 'approved'
                ? 'bg-green-600/10 text-green-400/70'
                : 'bg-red-600/10 text-red-400/70'
            }`}>
              {activity.decision === 'approved' ? (
                <><Check size={14} /> 已批准 ✓</>
              ) : (
                <><X size={14} /> 已拒绝 ✗</>
              )}
            </span>
          ) : activity.insight?.signal === 'auto_approve' ? (
            <span className="inline-flex items-center gap-1.5 px-4 py-2 text-sm font-medium rounded bg-gray-600/10 text-gray-400">
              <Check size={16} /> 已自动放行
            </span>
          ) : (
            (() => {
              // 统一三重禁用判定（与 L2 保持一致）
              const isDisabled = computeButtonDisabled(activity, !!resultData);
              const disabledClass = isDisabled
                ? 'opacity-40 cursor-not-allowed pointer-events-none'
                : '';
              return (
                <>
                  <button
                    onClick={onApprove}
                    disabled={isDisabled}
                    className={`inline-flex items-center gap-1.5 px-4 py-2 text-xs font-medium rounded bg-green-600/20 text-green-300 hover:bg-green-600/30 transition-colors ${disabledClass}`}
                    title={isDisabled ? '等待文件变更数据或验证完成' : '批准此操作'}
                  >
                    <Check size={14} /> 批准
                  </button>
                  <button
                    onClick={onReject}
                    disabled={isDisabled}
                    className={`inline-flex items-center gap-1.5 px-4 py-2 text-xs font-medium rounded bg-red-600/20 text-red-300 hover:bg-red-600/30 transition-colors ${disabledClass}`}
                    title={isDisabled ? '等待文件变更数据或验证完成' : '拒绝此操作'}
                  >
                    <X size={14} /> 拒绝
                  </button>
                  <button
                    disabled
                    title="Phase 2 功能"
                    className="px-3 py-1.5 text-xs rounded bg-zinc-700 text-zinc-500 cursor-not-allowed opacity-50"
                  >
                    应用建议
                  </button>
                </>
              );
            })()
          )}
          <button
            onClick={onClose}
            className="ml-auto inline-flex items-center gap-1.5 px-4 py-2 text-xs font-medium rounded bg-gray-600/20 text-[var(--text-secondary)] hover:bg-gray-600/30 transition-colors"
          >
            关闭
          </button>
        </div>
      </div>
    </div>
  );

  return createPortal(content, document.body);
}
