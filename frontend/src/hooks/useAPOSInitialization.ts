/**
 * useAPOSInitialization — APOS 数据流转链路初始化 Hook
 *
 * 职责:
 * 1. 监听 messageStore 工具调用完成 → 聚合为 ActivityData
 * 2. 关联 insightStore assessment → activityStore insight
 */

import { useEffect, useRef } from 'react';
import { useActivityStore } from '@/store/activityStore';
import { useMessageStore } from '@/store/messageStore';
import { useInsightStore } from '@/store/insightStore';
import { useSessionStore } from '@/store/sessionStore';
import { saveActivity, updateActivityInsight } from '@/api/activityApi';
import type { ActivityData, FileChange, OperationType, Signal, RiskLevel, RunChecksRequest, RunChecksResponse } from '@/types/apos';
import { NEEDS_VERIFICATION_OPS } from '@/types/apos';

/**
 * 从工具名称推断 OperationType
 */
function inferOperationType(toolName: string, input: unknown): OperationType {
  const name = toolName.toLowerCase();
  // 精确匹配常见工具名
  if (name === 'edit' || name === 'multiedit' || name === 'multi_edit') return 'file_edit';
  if (name === 'write' || name === 'create') return 'file_create';
  if (name === 'bash' || name === 'terminal' || name === 'execute' || name === 'run') return 'command_execute';
  if (name === 'delete' || name === 'rm') return 'delete';
  if (name === 'read' || name === 'search' || name === 'grep' || name === 'find') return 'unknown';
  if (name === 'test' || name === 'vitest' || name === 'jest') return 'test_run';
  if (name === 'git' || name === 'commit') return 'git_commit';
  // 模糊匹配
  if (name.includes('edit') || name.includes('write') || name.includes('patch')) return 'file_edit';
  if (name.includes('create') || name.includes('new') || name.includes('touch')) return 'file_create';
  if (name.includes('delete') || name.includes('remove')) return 'delete';
  if (name.includes('bash') || name.includes('exec') || name.includes('command') || name.includes('terminal') || name.includes('shell')) return 'command_execute';
  if (name.includes('test')) return 'test_run';
  if (name.includes('git') || name.includes('commit')) return 'git_commit';
  if (name.includes('install') || name.includes('dep') || name.includes('npm') || name.includes('yarn') || name.includes('pnpm')) return 'dependency';
  if (name.includes('config') || name.includes('env') || name.includes('setting')) return 'config_change';
  if (name.includes('refactor') || name.includes('rename') || name.includes('move')) return 'refactor';
  // 根据 input 内容进一步推断
  if (input && typeof input === 'object') {
    const inp = input as Record<string, unknown>;
    if (inp.command && typeof inp.command === 'string') return 'command_execute';
    if (inp.file_path || inp.filePath) return 'file_edit';
  }
  return 'unknown';
}

/**
 * 从工具调用的 input 生成有意义的摘要
 */
function generateActivitySummary(toolName: string, input: unknown, operationType: OperationType): string {
  if (!input || typeof input !== 'object') return toolName;
  const inp = input as Record<string, unknown>;

  // 提取文件路径的短名
  const shortenPath = (p: string): string => {
    const parts = p.split('/');
    return parts.length > 2 ? `.../${parts.slice(-2).join('/')}` : p;
  };

  const name = toolName.toLowerCase();

  // Edit / MultiEdit / Write
  if (name === 'edit' || name === 'multiedit' || name === 'multi_edit' || name === 'write' || name === 'create') {
    const filePath = (inp.file_path || inp.filePath || inp.path) as string | undefined;
    if (filePath) {
      const verb = name === 'create' || name === 'write' ? '创建' : '编辑';
      return `${verb} ${shortenPath(filePath)}`;
    }
  }

  // Delete
  if (name === 'delete' || name === 'rm') {
    const filePath = (inp.file_path || inp.filePath || inp.path) as string | undefined;
    if (filePath) return `删除 ${shortenPath(filePath)}`;
  }

  // Read
  if (name === 'read') {
    const filePath = (inp.file_path || inp.filePath || inp.path) as string | undefined;
    if (filePath) return `读取 ${shortenPath(filePath)}`;
  }

  // Bash / Terminal
  if (name === 'bash' || name === 'terminal' || name === 'execute' || name === 'run') {
    const cmd = (inp.command || inp.cmd) as string | undefined;
    if (cmd) {
      const shortCmd = cmd.length > 60 ? cmd.slice(0, 57) + '...' : cmd;
      return `执行 ${shortCmd}`;
    }
  }

  // Search / Grep
  if (name === 'search' || name === 'grep' || name === 'find') {
    const pattern = (inp.pattern || inp.query || inp.regex) as string | undefined;
    const dir = (inp.directory || inp.path || inp.dir) as string | undefined;
    if (pattern) {
      return dir ? `搜索 "${pattern}" in ${shortenPath(dir)}` : `搜索 "${pattern}"`;
    }
  }

  // Git
  if (name === 'git' || name === 'commit') {
    const msg = (inp.message || inp.msg) as string | undefined;
    if (msg) return `Git: ${msg.length > 50 ? msg.slice(0, 47) + '...' : msg}`;
  }

  // Fallback: 用 operationType 生成中文描述
  const typeLabels: Record<OperationType, string> = {
    file_edit: '文件编辑',
    file_create: '文件创建',
    command_execute: '命令执行',
    test_run: '测试运行',
    git_commit: 'Git 提交',
    refactor: '代码重构',
    dependency: '依赖管理',
    config_change: '配置变更',
    delete: '文件删除',
    unknown: toolName,
  };
  return typeLabels[operationType] || toolName;
}

/**
 * 从工具调用的 input 中提取文件变更信息
 * 支持的工具名: Edit, Write, Read, Bash, Grep, Glob, Git, MultiEdit
 * 以及 str_replace_editor, write_to_file, create_file, edit_file 等外部格式
 */
function extractChangedFiles(toolName: string, input: unknown): FileChange[] {
  const files: FileChange[] = [];

  // 防御: input 为 null/undefined
  if (!input) {
    console.log('[APOS-DEBUG] extractChangedFiles: input is null/undefined for tool:', toolName);
    return files;
  }

  // 防御: input 可能是 JSON 字符串（后端序列化异常时）
  let parsedInput = input;
  if (typeof input === 'string') {
    try {
      parsedInput = JSON.parse(input);
      console.log('[APOS-DEBUG] extractChangedFiles: parsed string input for tool:', toolName);
    } catch {
      console.log('[APOS-DEBUG] extractChangedFiles: input is non-JSON string for tool:', toolName);
      return files;
    }
  }

  if (typeof parsedInput !== 'object' || Array.isArray(parsedInput)) {
    console.log('[APOS-DEBUG] extractChangedFiles: input is not an object for tool:', toolName, typeof parsedInput);
    return files;
  }

  const inp = parsedInput as Record<string, unknown>;
  const toolLower = toolName.toLowerCase();

  // 单文件操作: file_path / filePath / path / filename
  const filePath = (inp.file_path || inp.filePath || inp.path || inp.filename) as string | undefined;
  if (filePath && typeof filePath === 'string') {
    let changeType: 'added' | 'modified' | 'deleted' = 'modified';
    // Write 工具 = 创建/覆盖文件
    if (toolLower === 'write' || toolLower.includes('create') || toolLower.includes('write_to') || toolLower.includes('new') || toolLower.includes('touch')) {
      changeType = 'added';
    } else if (toolLower.includes('delete') || toolLower.includes('remove') || toolLower === 'rm') {
      changeType = 'deleted';
    }
    // str_replace_editor with command='create' → 'added'
    if (toolLower === 'str_replace_editor' || toolLower === 'str_replace') {
      const cmd = inp.command as string | undefined;
      if (cmd === 'create') changeType = 'added';
    }

    // 从 input 内容中估算行数统计
    let additions = 0;
    let deletions = 0;

    if (changeType === 'added') {
      // 创建文件：整体内容为 additions
      const content = (inp.content || inp.file_text || inp.new_str || inp.text) as string | undefined;
      if (typeof content === 'string' && content.length > 0) {
        additions = content.split('\n').length;
      }
    } else if (changeType === 'modified') {
      // 编辑文件：从 new_str/old_str 或 replacements 中估算
      const newStr = (inp.new_str || inp.new_string || inp.insert_text) as string | undefined;
      const oldStr = (inp.old_str || inp.old_string) as string | undefined;
      if (typeof newStr === 'string') {
        additions = newStr.split('\n').length;
      }
      if (typeof oldStr === 'string') {
        deletions = oldStr.split('\n').length;
      }
      // replacements 数组的情况
      if (inp.replacements && Array.isArray(inp.replacements)) {
        let totalAdd = 0, totalDel = 0;
        (inp.replacements as Record<string, unknown>[]).forEach((r) => {
          const ns = (r.new_text || r.new_str) as string | undefined;
          const os = (r.original_text || r.old_str || r.old_text) as string | undefined;
          if (typeof ns === 'string') totalAdd += ns.split('\n').length;
          if (typeof os === 'string') totalDel += os.split('\n').length;
        });
        if (totalAdd > 0 || totalDel > 0) {
          additions = totalAdd;
          deletions = totalDel;
        }
      }
    } else if (changeType === 'deleted') {
      // 删除文件：无法知道原文件行数，保留 0
      deletions = 0;
    }

    // 提取 diff 内容用于 L3 展示
    let diffContent: string | undefined;
    if (changeType === 'added') {
      const content = (inp.content || inp.file_text || inp.new_str || inp.text) as string | undefined;
      if (typeof content === 'string' && content.length > 0) {
        // 新增文件：全部行标记为 +
        diffContent = content.split('\n').map(line => `+ ${line}`).join('\n');
      }
    } else if (changeType === 'modified') {
      const newStr = (inp.new_str || inp.new_string || inp.insert_text) as string | undefined;
      const oldStr = (inp.old_str || inp.old_string) as string | undefined;
      if (typeof oldStr === 'string' && typeof newStr === 'string') {
        // 修改文件：显示 old → new diff
        const oldLines = oldStr.split('\n').map(line => `- ${line}`);
        const newLines = newStr.split('\n').map(line => `+ ${line}`);
        diffContent = [...oldLines, ...newLines].join('\n');
      } else if (typeof newStr === 'string') {
        diffContent = newStr.split('\n').map(line => `+ ${line}`).join('\n');
      }
      // replacements 数组
      if (!diffContent && inp.replacements && Array.isArray(inp.replacements)) {
        const diffLines: string[] = [];
        (inp.replacements as Record<string, unknown>[]).forEach((r) => {
          const os = (r.original_text || r.old_str || r.old_text) as string | undefined;
          const ns = (r.new_text || r.new_str) as string | undefined;
          if (typeof os === 'string') {
            os.split('\n').forEach(line => diffLines.push(`- ${line}`));
          }
          if (typeof ns === 'string') {
            ns.split('\n').forEach(line => diffLines.push(`+ ${line}`));
          }
        });
        if (diffLines.length > 0) diffContent = diffLines.join('\n');
      }
    }

    files.push({ filePath, additions, deletions, changeType, diffContent });
  }

  // MultiEdit / batch 操作
  if (inp.files && Array.isArray(inp.files)) {
    (inp.files as unknown[]).forEach((f) => {
      const fp = typeof f === 'string' ? f : ((f as Record<string, unknown>)?.file_path || (f as Record<string, unknown>)?.filePath || (f as Record<string, unknown>)?.path) as string | undefined;
      if (fp && typeof fp === 'string') {
        files.push({ filePath: fp, additions: 0, deletions: 0, changeType: 'modified' });
      }
    });
  }

  // replacements 数组（search_replace 类工具）
  if (inp.replacements && Array.isArray(inp.replacements) && filePath) {
    // 文件已通过 filePath 添加，不重复
  }

  // Bash / 命令执行: 尝试从 command 中提取文件路径
  if (files.length === 0 && toolLower === 'bash') {
    const cmd = (inp.command || inp.cmd) as string | undefined;
    if (cmd && typeof cmd === 'string') {
      // 匹配常见的文件操作命令中的路径
      const writePatterns = /(?:>|>>|tee|cp|mv|mkdir)\s+([^\s;|&]+)/g;
      let match;
      while ((match = writePatterns.exec(cmd)) !== null) {
        const p = match[1];
        if (p && p.startsWith('/') && !p.includes('*')) {
          files.push({ filePath: p, additions: 0, deletions: 0, changeType: 'modified' });
        }
      }
    }
  }

  console.log('[APOS-DEBUG] extractChangedFiles result:', {
    toolName,
    toolLower,
    inputKeys: Object.keys(inp),
    filePathFound: filePath ?? 'none',
    filesExtracted: files.length,
    files: files.map(f => f.filePath),
  });

  return files;
}

/**
 * 从 Signal 推断 RiskLevel
 */
function signalToRiskLevel(signal: Signal): RiskLevel {
  switch (signal) {
    case 'auto_approve': return 'safe';
    case 'review_recommended': return 'review';
    case 'manual_required': return 'warning';
    case 'blocked': return 'danger';
  }
}

/**
 * 根据操作类型推断默认 Signal
 * 只读操作 → auto_approve（安全放行）
 * 写入/执行操作 → review_recommended（建议审查）
 */
function inferDefaultSignal(operationType: OperationType): Signal {
  switch (operationType) {
    case 'unknown': // read, search, grep, find 等只读操作
    case 'test_run': // 测试运行不修改代码
      return 'auto_approve';
    case 'file_edit':
    case 'file_create':
    case 'delete':
    case 'command_execute':
    case 'git_commit':
    case 'refactor':
    case 'dependency':
    case 'config_change':
      return 'review_recommended';
    default:
      return 'review_recommended';
  }
}

/** 写入类操作 — 触发后端验证 */
const WRITE_OPS: OperationType[] = [
  'file_edit', 'file_create', 'command_execute', 'git_commit',
  'refactor', 'dependency', 'config_change', 'delete',
];

/** 根据验证结果计算 Signal */
function computeSignalFromVerification(result: RunChecksResponse): Signal {
  if (result.status === 'all_pass') return 'auto_approve';
  if (result.status === 'has_warning') return 'review_recommended';
  return 'manual_required'; // has_error
}

/** 异步触发后端验证并更新 Activity insight（带超时降级） */
async function triggerVerification(activity: ActivityData): Promise<void> {
  const filePaths = activity.changedFiles.map(f => f.filePath).filter(Boolean);

  // 无文件路径时跳过验证，降级
  if (filePaths.length === 0) {
    // 命令执行类且无文件变更 → 只读命令，安全放行
    const isReadOnlyCommand = activity.operationType === 'command_execute';
    const signal: Signal = isReadOnlyCommand ? 'auto_approve' : 'review_recommended';
    const insightData: NonNullable<ActivityData['insight']> = {
      signal,
      riskLevel: signalToRiskLevel(signal),
      summary: isReadOnlyCommand
        ? '命令未检测到文件写入，自动批准'
        : '无可验证文件，建议人工审查',
      factors: [],
      suggestions: [],
      verificationStatus: 'skipped',
    };

    useActivityStore.getState().updateActivity(activity.id, { insight: insightData });
    // 同步 insight 到后端
    updateActivityInsight(activity.id, insightData);

    // 只读命令 + auto_approve + 开关开启 → 自动写入 decision
    if (isReadOnlyCommand && useInsightStore.getState().autoApproveEnabled) {
      const current = useActivityStore.getState().activities.get(activity.id);
      if (current && current.decision === undefined) {
        useActivityStore.getState().approveActivity(activity.id);
        console.log('[APOS] Auto-approved read-only command (no file changes):', activity.id);
      }
    }

    return;
  }

  const sessionId = useSessionStore.getState().sessionId || 'default';
  const request: RunChecksRequest = {
    sessionId,
    operationId: activity.id,
    checks: ['typescript', 'eslint'],
    filePaths,
    timeout: 10000,
  };

  // 超时控制：10秒后自动 abort，避免请求永远 hang
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 10000);

  try {
    const response = await fetch('/api/verify/run-checks', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
      signal: controller.signal,
    });

    clearTimeout(timeoutId);

    if (!response.ok) {
      useActivityStore.getState().updateActivity(activity.id, {
        insight: {
          signal: 'manual_required',
          riskLevel: 'warning',
          summary: `验证服务异常 (HTTP ${response.status})，需手动验证`,
          factors: [],
          suggestions: [],
          verificationStatus: 'failed',
        },
      });
      return;
    }

    const rawResult = await response.json();

    // Phase 2 响应判别：包含 signal 字段则为 VerifyCheckResponse
    let signal: Signal;
    let verificationStatus: string;
    let summary: string;

    if ('signal' in rawResult && 'overallStatus' in rawResult) {
      // Phase 2: VerifyCheckResponse
      signal = rawResult.signal as Signal;
      verificationStatus = rawResult.overallStatus === 'pass' ? 'all_pass'
        : rawResult.overallStatus === 'fail' ? 'has_error' : 'has_warning';
      summary = rawResult.signalReason || (rawResult.overallStatus === 'pass' ? '验证通过' : '验证发现问题');
    } else {
      // Phase 1 fallback: RunChecksResponse
      const result = rawResult as RunChecksResponse;
      signal = computeSignalFromVerification(result);
      verificationStatus = result.status;
      summary = result.status === 'all_pass' ? '验证通过' : '验证发现问题';
    }

    useActivityStore.getState().updateActivity(activity.id, {
      insight: {
        signal,
        riskLevel: signalToRiskLevel(signal),
        summary,
        factors: [],
        suggestions: [],
        verificationStatus,
      },
    });
    // 同步 insight 到后端
    updateActivityInsight(activity.id, {
      signal,
      riskLevel: signalToRiskLevel(signal),
      summary,
      factors: [],
      suggestions: [],
      verificationStatus,
    });

    // 自动审批：signal 为 auto_approve 且 feature flag 开启时，自动写入决策
    if (signal === 'auto_approve' && useInsightStore.getState().autoApproveEnabled) {
      const current = useActivityStore.getState().activities.get(activity.id);
      if (current && current.decision === undefined) {
        useActivityStore.getState().approveActivity(activity.id);
        console.log('[APOS] Auto-approved activity:', activity.id);
      }
    }
  } catch (err) {
    clearTimeout(timeoutId);
    const isTimeout = err instanceof DOMException && err.name === 'AbortError';
    console.warn('[APOS] Verification failed:', isTimeout ? 'timeout (10s)' : err);

    // 降级：设置 verificationStatus 为 'failed'，停止 spinner
    useActivityStore.getState().updateActivity(activity.id, {
      insight: {
        signal: 'manual_required',
        riskLevel: 'warning',
        summary: isTimeout ? '验证超时，需手动审查' : '验证失败，需手动审查',
        factors: [],
        suggestions: [],
        verificationStatus: 'failed',
      },
    });
    // 同步 insight 到后端
    updateActivityInsight(activity.id, {
      signal: 'manual_required',
      riskLevel: 'warning',
      summary: isTimeout ? '验证超时，需手动审查' : '验证失败，需手动审查',
      factors: [],
      suggestions: [],
      verificationStatus: 'failed',
    });
  }
}

export function useAPOSInitialization(): void {
  const processedToolCallsRef = useRef<Set<string>>(new Set());
  const processedAssessmentsRef = useRef<Set<string>>(new Set());
  const verifiedActivitiesRef = useRef<Set<string>>(new Set());

  // 职责 1: 监听 messageStore 工具调用完成
  useEffect(() => {
    const unsubscribe = useMessageStore.subscribe(
      (state) => state.activeToolCalls,
      (activeToolCalls) => {
        const { addActivity } = useActivityStore.getState();

        activeToolCalls.forEach((toolCall, toolUseId) => {
          // 只处理已完成的工具调用，且未被处理过
          if (
            (toolCall.status === 'completed' || toolCall.status === 'error') &&
            !processedToolCallsRef.current.has(toolUseId)
          ) {
            processedToolCallsRef.current.add(toolUseId);

            console.log('[APOS-DEBUG] Processing completed toolCall:', {
              toolUseId,
              toolName: toolCall.toolName,
              status: toolCall.status,
              inputType: typeof toolCall.input,
              inputIsNull: toolCall.input === null || toolCall.input === undefined,
              inputIsEmptyObj: toolCall.input && typeof toolCall.input === 'object' && Object.keys(toolCall.input as object).length === 0,
              inputKeys: toolCall.input && typeof toolCall.input === 'object' && !Array.isArray(toolCall.input)
                ? Object.keys(toolCall.input as object)
                : [],
              inputSample: JSON.stringify(toolCall.input)?.substring(0, 300),
              timestamp: Date.now(),
            });

            const operationType = inferOperationType(toolCall.toolName, toolCall.input);
            const summary = generateActivitySummary(toolCall.toolName, toolCall.input, operationType);

            // 从 input 中提取文件变更信息
            // 权限被拒绝的工具不应显示 changedFiles（文件实际未被修改）
            const isDenied = useActivityStore.getState().isToolUseDenied(toolUseId);
            const isApproved = useActivityStore.getState().isToolUseApproved(toolUseId);
            const changedFiles = isDenied ? [] : extractChangedFiles(toolCall.toolName, toolCall.input);

            // 根据操作类型决定初始 verificationStatus
            // 需要验证的操作（文件编辑/创建/删除等）→ 'pending'，显示 spinner
            // 不需要验证的操作（command_execute/test_run/unknown）→ 'skipped'，不显示 spinner
            const needsVerification = NEEDS_VERIFICATION_OPS.includes(operationType);

            // 决定初始 decision:
            // - 已被用户批准（在 PermissionDialog 中点击“允许”）→ 'approved'
            // - 工具成功执行且未被拒绝，属于写入操作 → 'approved'（成功执行意味着已被批准）
            // - 其他情况 → undefined（由 UI 根据 signal 显示“已自动放行”）
            let initialDecision: 'approved' | 'rejected' | undefined;
            if (isDenied) {
              initialDecision = 'rejected';
            } else if (isApproved) {
              initialDecision = 'approved';
            } else if (
              toolCall.status === 'completed' &&
              WRITE_OPS.includes(operationType)
            ) {
              // 写入操作成功完成，必然已被批准（用户批准或系统自动放行）
              initialDecision = 'approved';
            }

            const activity: ActivityData = {
              id: toolUseId,
              sessionId: useSessionStore.getState().sessionId || undefined,
              operationType,
              summary,
              status: toolCall.status === 'error' ? 'error' : 'completed',
              timestamp: toolCall.startTime,
              duration: toolCall.duration,
              fileCount: changedFiles.length,
              changedFiles,
              decision: initialDecision,
              ...(toolCall.result && {
                toolResult: {
                  content: toolCall.result.content,
                  isError: toolCall.result.isError,
                  metadata: toolCall.result.metadata,
                },
              }),
              insight: {
                signal: inferDefaultSignal(operationType),
                riskLevel: signalToRiskLevel(inferDefaultSignal(operationType)),
                summary: !needsVerification
                  ? '只读/命令操作，无需验证'
                  : '等待验证或人工审查',
                factors: [],
                suggestions: [],
                verificationStatus: needsVerification ? 'pending' : 'skipped',
              },
            };

            addActivity(activity);

            // 同步到后端持久化
            saveActivity(activity);

            // 只读/无害操作直接自动审批（signal 已为 auto_approve 且 feature flag 开启）
            if (
              activity.insight?.signal === 'auto_approve' &&
              useInsightStore.getState().autoApproveEnabled
            ) {
              const current = useActivityStore.getState().activities.get(activity.id);
              if (current && current.decision === undefined) {
                useActivityStore.getState().approveActivity(activity.id);
                console.log('[APOS] Auto-approved read-only activity:', activity.id);
              }
            }

            console.log('[APOS-DEBUG] Activity created:', {
              id: activity.id,
              toolName: toolCall.toolName,
              operationType,
              signal: activity.insight?.signal,
              changedFiles: activity.changedFiles.length,
              changedFilePaths: activity.changedFiles.map(f => f.filePath),
            });

            // 写入类操作 → 异步触发后端验证（不阻塞 UI）
            if (
              WRITE_OPS.includes(operationType) &&
              !verifiedActivitiesRef.current.has(toolUseId)
            ) {
              verifiedActivitiesRef.current.add(toolUseId);
              triggerVerification(activity).catch(err => {
                console.warn('[APOS] Verification request failed:', err);
              });
            }
          }
        });
      }
    );

    return () => { unsubscribe(); };
  }, []);

  // 职责 2: 关联 insightStore assessment → activityStore insight
  useEffect(() => {
    const unsubscribe = useInsightStore.subscribe(
      (state) => state.assessments,
      (assessments) => {
        const { attachInsight } = useActivityStore.getState();

        assessments.forEach((assessment, operationId) => {
          if (processedAssessmentsRef.current.has(operationId)) return;
          processedAssessmentsRef.current.add(operationId);

          // 将 RiskAssessment 转换为 ActivityData['insight'] 格式
          const insight: ActivityData['insight'] = {
            signal: assessment.signal,
            riskLevel: signalToRiskLevel(assessment.signal),
            summary: assessment.reason,
            factors: [],
            suggestions: [],
            verificationStatus: assessment.deterministic.typeCheck.passed &&
              assessment.deterministic.lint.passed &&
              assessment.deterministic.tests.passed
              ? 'all_pass'
              : assessment.deterministic.typeCheck.errorCount > 0 ||
                assessment.deterministic.lint.errorCount > 0 ||
                assessment.deterministic.tests.failedCount > 0
                ? 'has_error'
                : 'has_warning',
          };

          attachInsight(operationId, insight);

          // 自动审批
          if (insight.signal === 'auto_approve' && useInsightStore.getState().autoApproveEnabled) {
            const current = useActivityStore.getState().activities.get(operationId);
            if (current && current.decision === undefined) {
              useActivityStore.getState().approveActivity(operationId);
              console.log('[APOS] Auto-approved via assessment:', operationId);
            }
          }
        });
      }
    );

    return () => { unsubscribe(); };
  }, []);

  // 职责 3: 订阅 tool_use_input 回溯更新已创建的 Activity
  // 当 dispatch 收到 tool_use_input 消息时，messageStore.activeToolCalls 中对应条目的 input 会被更新。
  // 如果此时该 toolUseId 已经有对应的 Activity（但 changedFiles 为空——说明创建时 input 尚未就绪），
  // 则用新 input 回溯更新该 Activity 的 operationType/summary/changedFiles。
  useEffect(() => {
    const unsubscribe = useMessageStore.subscribe(
      (state) => state.activeToolCalls,
      (activeToolCalls) => {
        const { activities, updateActivity } = useActivityStore.getState();

        activeToolCalls.forEach((toolCall, toolUseId) => {
          const existing = activities.get(toolUseId);

          // 回溯条件放宽：已有 Activity + (changedFiles 为空 OR summary 为工具名原始值) + input 非空对象
          const inputObj = toolCall.input;
          const hasValidInput = inputObj &&
            typeof inputObj === 'object' &&
            !Array.isArray(inputObj) &&
            Object.keys(inputObj as object).length > 0;
          // 也处理 input 是有效 JSON 字符串的情况
          const hasStringInput = typeof inputObj === 'string' && inputObj.length > 2;

          if (
            existing &&
            existing.changedFiles.length === 0 &&
            (hasValidInput || hasStringInput)
          ) {
            // 权限被拒绝的工具跳过回填（文件实际未被修改）
            if (useActivityStore.getState().isToolUseDenied(toolUseId)) {
              return;
            }
            // 回填更新
            const operationType = inferOperationType(toolCall.toolName, toolCall.input);
            const summary = generateActivitySummary(toolCall.toolName, toolCall.input, operationType);
            const changedFiles = extractChangedFiles(toolCall.toolName, toolCall.input);

            console.log('[APOS-DEBUG] Backfill check:', {
              toolUseId,
              toolName: toolCall.toolName,
              existingSummary: existing.summary,
              existingChangedFiles: existing.changedFiles.length,
              newChangedFiles: changedFiles.length,
              newSummary: summary,
              hasValidInput,
              hasStringInput,
            });

            if (changedFiles.length > 0 || summary !== existing.summary) {
              updateActivity(toolUseId, {
                operationType,
                summary,
                changedFiles,
                fileCount: changedFiles.length,
                // 回溯时也保存 toolResult
                ...(toolCall.result && {
                  toolResult: {
                    content: toolCall.result.content,
                    isError: toolCall.result.isError,
                    metadata: toolCall.result.metadata,
                  },
                }),
              });
              console.log('[APOS-DEBUG] Activity backfill-updated:', {
                id: toolUseId,
                toolName: toolCall.toolName,
                newSummary: summary,
                newFileCount: changedFiles.length,
                newFilePaths: changedFiles.map(f => f.filePath),
              });

              // 回溯后如果是写入操作且还没触发验证，补充触发
              if (
                WRITE_OPS.includes(operationType) &&
                changedFiles.length > 0 &&
                !verifiedActivitiesRef.current.has(toolUseId)
              ) {
                verifiedActivitiesRef.current.add(toolUseId);
                const updatedActivity: ActivityData = { ...existing, operationType, summary, changedFiles, fileCount: changedFiles.length };
                triggerVerification(updatedActivity).catch(err => {
                  console.warn('[APOS] Backfill verification failed:', err);
                });
              }
            }
          }
        });
      }
    );
    return () => { unsubscribe(); };
  }, []);

  // 职责 4: 监听 toolCall result 到达，回填已存在的 Activity 的 toolResult
  // 当 completeToolCall 被调用后，如果 Activity 已创建但 toolResult 尚未存入，则补充
  useEffect(() => {
    const unsubscribe = useMessageStore.subscribe(
      (state) => state.activeToolCalls,
      (activeToolCalls) => {
        const { activities, updateActivity } = useActivityStore.getState();

        activeToolCalls.forEach((toolCall, toolUseId) => {
          const existing = activities.get(toolUseId);
          if (
            existing &&
            !existing.toolResult &&
            toolCall.result &&
            (toolCall.status === 'completed' || toolCall.status === 'error')
          ) {
            updateActivity(toolUseId, {
              toolResult: {
                content: toolCall.result.content,
                isError: toolCall.result.isError,
                metadata: toolCall.result.metadata,
              },
            });
          }
        });
      }
    );
    return () => { unsubscribe(); };
  }, []);
}
