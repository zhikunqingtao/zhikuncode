/**
 * WebSocket 消息分发器 — 覆盖全部 40 种 Server→Client 消息类型
 * SPEC: §8.5.3 dispatch 函数
 *
 * 按 type 字段分发到对应 Zustand Store，
 * 跨 Store 消息通过私有 handle* 方法协调。
 */

import type { Message, ServerMessage, Usage, PermissionRequest, PermissionMode, TokenWarningPayload, ToolPermissionDeniedPayload } from '@/types';
import type { ActivityData } from '@/types/apos';
import { useMessageStore } from '@/store/messageStore';
import { useActivityStore } from '@/store/activityStore';
import { useSessionStore } from '@/store/sessionStore';
import { usePermissionStore } from '@/store/permissionStore';
import { useCostStore } from '@/store/costStore';
import { useTaskStore } from '@/store/taskStore';
import { useAppUiStore } from '@/store/appUiStore';
import { useBridgeStore } from '@/store/bridgeStore';
import { useNotificationStore } from '@/store/notificationStore';
import { useInboxStore } from '@/store/inboxStore';
import { useMcpStore } from '@/store/mcpStore';
import { useSwarmStore } from '@/store/swarmStore';
import { usePlanStore, type PlanStep } from '@/store/planStore';
import { useCoordinatorStore } from '@/store/coordinatorStore';
import { useInsightStore } from '@/store/insightStore';
import { useAnomalyStore } from '@/store/anomalyStore';
import { useJourneyVerifyStore } from '@/store/journeyVerifyStore';
import { useEvidenceStore } from '@/store/evidenceStore';
import { useRunStore } from '@/store/runStore';
import { anomalyEngine } from '@/services/AnomalyDetectionEngine';
import { mapRunChecksResponseToRiskAssessment } from '@/utils/aposAdapters';
import { appendStreamDelta } from '@/hooks/useStreamingText';
import { generateUUID } from '@/utils/uuid';

/** 序列号校验器 — 检测乱序/丢失消息 */
let lastSeqTs = 0;

interface PendingBind {
    sessionId: string;
    bindingEpoch: number;
    resolve: (restored: boolean) => void;
    timer: ReturnType<typeof setTimeout>;
    queued: Array<ServerMessage & { ts?: number }>;
}
const pendingBinds = new Map<string, PendingBind>();
let activeRecoveryId: string | null = null;
let nextBindingEpoch = 0;
let boundBindingEpoch = 0;

/** 恢复状态下仍需立即处理的关键消息类型（时间敏感，不可延迟或丢弃） */
const RECOVERY_BYPASS_TYPES: ReadonlySet<string> = new Set([
    'session_restored', 'protocol_error',
    'interaction_created', 'interaction_updated', 'interaction_terminal',
    'permission_request', 'permission_mode_changed'
]);

/** 已绑定的会话 ID — 跟踪当前 WS 连接已绑定的 sessionId，避免重复发送 bind-session */
let boundSessionId: string | null = null;

interface InteractionView {
    protocolVersion: number;
    interactionId: string;
    correlationKey: string;
    sessionId: string;
    runId: string;
    interactionType: 'permission' | 'elicitation' | 'plan_approval';
    status: string;
    prompt: Record<string, unknown>;
    allowedDecisions: string[];
    scopeOptions: Array<'session' | 'workspace'>;
    response?: unknown;
    source?: string;
    childSessionId?: string;
    deliveryGeneration: number;
    decisionDeadlineAt?: string | number;
    deliveryWindowEndsAt: string | number;
    version: number;
    serverNow: number;
}

function clientDeadline(deadline: string | number | undefined, serverNow?: number): number | undefined {
    if (deadline === undefined || deadline === null) return undefined;
    const parsed = typeof deadline === 'number' ? deadline : Date.parse(deadline);
    if (!Number.isFinite(parsed)) return undefined;
    return Date.now() + Math.max(0, parsed - (serverNow ?? Date.now()));
}

function handleInteractionCreated(interaction: InteractionView): void {
    if (interaction.protocolVersion !== 2 || interaction.status !== 'pending') return;
    const prompt = interaction.prompt ?? {};
    const deadline = clientDeadline(interaction.decisionDeadlineAt, interaction.serverNow);
    if (interaction.interactionType === 'permission') {
        handlePermissionRequest({
            interactionId: interaction.interactionId,
            version: interaction.version,
            deliveryGeneration: interaction.deliveryGeneration,
            toolUseId: interaction.correlationKey,
            toolName: String(prompt.toolName ?? 'unknown'),
            input: { command: String(prompt.inputSummary ?? '') },
            riskLevel: prompt.riskLevel === 'low' || prompt.riskLevel === 'high'
                ? prompt.riskLevel : 'medium',
            reason: String(prompt.reason ?? ''),
            source: interaction.source,
            childSessionId: interaction.childSessionId,
            scopeOptions: interaction.scopeOptions,
            decisionDeadlineAt: deadline,
        });
    } else if (interaction.interactionType === 'elicitation') {
        useAppUiStore.getState().showElicitationDialog({
            interactionId: interaction.interactionId,
            version: interaction.version,
            requestId: interaction.interactionId,
            question: String(prompt.question ?? ''),
            options: prompt.options ?? [],
            decisionDeadlineAt: deadline,
        });
        void import('./stompClient').then(({ send }) =>
            send('/app/interaction-received', {
                interactionId: interaction.interactionId,
                deliveryGeneration: interaction.deliveryGeneration,
            }));
    }
}

/** 标记会话已绑定 */
export function markSessionBound(sessionId: string): void {
    boundSessionId = sessionId;
}

/** 检查会话是否已绑定 */
export function isSessionBound(sessionId: string): boolean {
    return boundSessionId === sessionId;
}

/** 重置绑定状态 — WS 重连时调用，确保下次发消息时重新发送 bind-session */
export function resetBoundSession(): void {
    boundSessionId = null;
    boundBindingEpoch = 0;
}

/**
 * 等待 session_restored 事件处理完成。
 * 用于 bind-session 后确保 session_restored 已处理完毕再添加用户消息，
 * 避免 clearMessages() 清掉刚添加的用户消息。
 * @param timeoutMs 最大等待时间，默认 5s（包含 Run event 缺口补齐）
 * @return true 表示已收到并处理完服务端 session_restored；false 表示绑定未确认
 */
export function bindSessionAndWait(
    sessionId: string,
    publish: (payload: { sessionId: string; protocolVersion: number; bindRequestId: string; bindingEpoch: number }) => void,
    timeoutMs = 5000,
): Promise<boolean> {
    if (activeRecoveryId) finishBind(activeRecoveryId, false, false);
    const bindRequestId = crypto.randomUUID();
    const bindingEpoch = ++nextBindingEpoch;
    return new Promise(resolve => {
        const timer = setTimeout(() => finishBind(bindRequestId, false, false), timeoutMs);
        pendingBinds.set(bindRequestId, { sessionId, bindingEpoch, resolve, timer, queued: [] });
        activeRecoveryId = bindRequestId;
        publish({ sessionId, protocolVersion: 3, bindRequestId, bindingEpoch });
    });
}

function finishBind(bindRequestId: string, restored: boolean, replayQueued: boolean): void {
    const pending = pendingBinds.get(bindRequestId);
    if (!pending) return;
    clearTimeout(pending.timer);
    pendingBinds.delete(bindRequestId);
    if (activeRecoveryId === bindRequestId) activeRecoveryId = null;
    pending.resolve(restored);
    if (replayQueued) pending.queued.forEach(message => dispatch(message));
}

/**
 * dispatch — 按 type 字段分发 Server→Client 消息到对应 Store。
 * @param data 原始 JSON body (WsMessage 格式: { type, ts, ...payload })
 */
export function dispatch(data: ServerMessage & { ts?: number }): void {
    const routed = data as ServerMessage & { ts?: number; _sessionId?: string; _bindingEpoch?: number };
    if (activeRecoveryId && !RECOVERY_BYPASS_TYPES.has(data.type)) {
        const pending = pendingBinds.get(activeRecoveryId);
        if (pending) {
            if (routed._sessionId && (routed._sessionId !== pending.sessionId
                    || routed._bindingEpoch !== pending.bindingEpoch)) {
                console.warn(`[WS] Recovery filter: discarding message type=${data.type}, sessionId mismatch`);
                return;
            }
            if (pending.queued.length >= 5000) pending.queued.shift();
            pending.queued.push(data);
            console.warn(`[WS] Recovery filter: queuing message type=${data.type} until session restore completes`);
            return;
        }
    }
    if (!activeRecoveryId && routed._sessionId && boundSessionId
            && (routed._sessionId !== boundSessionId || routed._bindingEpoch !== boundBindingEpoch)) {
        return;
    }
    // 序列号/时间戳校验
    if (data.ts) {
        if (data.ts < lastSeqTs) {
            console.warn(`[WS] Out-of-order message: ts=${data.ts} < lastTs=${lastSeqTs}, type=${data.type}`);
        }
        lastSeqTs = data.ts;
    }

    const handler = handlers[data.type];
    if (handler) {
        handler(data as any);
    } else {
        console.warn(`[WS] Unknown message type: ${data.type}`);
    }
}

/** 重置序列号 (断线重连时调用) */
export function resetSequence(): void {
    lastSeqTs = 0;
}

// ==================== 事件分发表 — 覆盖全部 25 种消息 ====================

const handlers: Record<string, (data: any) => void> = {
    'protocol_error': (d: { code: string; supportedVersion: number; bindRequestId?: string; bindingEpoch?: number }) => {
        if (d.bindRequestId) finishBind(d.bindRequestId, false, false);
        useNotificationStore.getState().addNotification({
            key: 'protocol-error', level: 'error',
            message: d.code === 'UPGRADE_REQUIRED'
                ? `客户端协议版本不兼容（服务端需要 v${d.supportedVersion}），请刷新页面`
                : '实时连接协议错误',
            timeout: 0,
        });
    },
    'interaction_created': (d: InteractionView) => handleInteractionCreated(d),
    // === messageStore (5 种) ===
    'stream_delta':       (d) => {
        // 首次 delta 时在 messageStore 创建占位 assistant 消息
        if (!useMessageStore.getState().streamingMessageId) {
            useMessageStore.getState().appendStreamDelta('');
        }
        // 后续 delta 仅写入外部高性能 store（绕过 Immer 开销）
        appendStreamDelta(d.delta);
    },
    'thinking_delta':     (d) => useMessageStore.getState().appendThinkingDelta(d.delta),
    'tool_use_start':     (d) => useMessageStore.getState().startToolCall(d.toolUseId, d.toolName, d.input),
    'tool_use_input':     (d) => {
        useMessageStore.getState().updateToolCallInput(d.toolUseId, d.input);
    },
    'tool_use_progress':  (d) => useMessageStore.getState().updateToolCallProgress(d.toolUseId, d.progress),
    'tool_result':        (d) => useMessageStore.getState().completeToolCall(d.toolUseId, d.result ?? { content: d.content ?? '', isError: d.isError ?? false }),

    // === messageStore + sessionStore (2 种) ===
    'error':              (d) => handleError(d),
    'compact_complete':   (d) => handleCompactComplete(d),

    // === messageStore + sessionStore (1 种) ===
    'message_complete':   (d) => handleMessageComplete(d),

    // === sessionStore (2 种) ===
    'compact_start':      ()  => useSessionStore.getState().setStatus('compacting'),
    'rate_limit':         (d) => useSessionStore.getState().handleRateLimit(d),

    // === permissionStore + sessionStore (1 种) ===
    'permission_request': (d) => handlePermissionRequest(d),

    // === activityStore: 权限拒绝后清除 changedFiles (1 种) ===
    'tool_permission_denied': (d: ToolPermissionDeniedPayload) => {
        useActivityStore.getState().markToolUseDenied(d.toolUseId);
        console.log('[APOS] tool_permission_denied: cleared changedFiles for', d.toolUseId, d.toolName);
    },

    // === costStore (1 种) ===
    'cost_update':        (d) => useCostStore.getState().updateCost(d),

    // === taskStore (4 种) ===
    'task_update':        (d) => useTaskStore.getState().updateTask(d.taskId, d),
    'agent_spawn':        (d) => useTaskStore.getState().addAgentTask(d),
    'agent_update':       (d) => useTaskStore.getState().updateAgentTask(d.taskId, d.progress),
    'agent_complete':     (d) => useTaskStore.getState().completeAgentTask(d.taskId, d.result),

    // === appUiStore (3 种) ===
    'elicitation':        (d) => {
        useAppUiStore.getState().showElicitationDialog(d);
        if (d.interactionId) void import('./stompClient').then(({ send }) =>
            send('/app/interaction-received', { interactionId: d.interactionId }));
    },
    'interaction_updated': (d: { interactionId: string; decisionDeadlineAt: number | string; serverNow?: number; version?: number }) => {
        const deadline = clientDeadline(d.decisionDeadlineAt, d.serverNow);
        if (deadline === undefined) return;
        usePermissionStore.getState().updateInteractionDeadline(d.interactionId, deadline, d.version);
        useAppUiStore.getState().updateElicitationDeadline(d.interactionId, deadline, d.version);
    },
    'interaction_terminal': (d: { interactionId: string; interactionType: string }) => {
        usePermissionStore.getState().removeInteraction(d.interactionId);
        if (d.interactionType === 'elicitation'
            && useAppUiStore.getState().elicitationDialog?.interactionId === d.interactionId) {
            useAppUiStore.getState().dismissElicitationDialog();
        }
    },
    'prompt_suggestion':  (d) => useAppUiStore.getState().setPromptSuggestion(d),
    'speculation_result': (d) => useAppUiStore.getState().updateSpeculation(d),

    // === bridgeStore (1 种) ===
    'bridge_status':      (d) => useBridgeStore.getState().updateBridgeStatus(d),

    // === notificationStore (1 种) ===
    'notification':       (d) => useNotificationStore.getState().addNotification(d),

    // === inboxStore (1 种) ===
    'teammate_message':   (d) => useInboxStore.getState().addInboxMessage(d),

    // === mcpStore (1 种) ===
    'mcp_tool_update':    (d) => useMcpStore.getState().updateMcpTools(d),

    // === mcpStore: MCP 健康状态 (1 种) ===
    'mcp_health_status':  (d: { serverName: string; status: string; consecutiveFailures?: number; lastSuccessfulPing?: number; timestamp?: number }) => {
        useMcpStore.getState().updateHealthStatus({
            serverName: d.serverName,
            status: d.status,
            consecutiveFailures: d.consecutiveFailures ?? 0,
            lastSuccessfulPing: d.lastSuccessfulPing ?? null,
            timestamp: d.timestamp ?? Date.now(),
        });
    },

    // === mcpStore: M4 工具调用进度 (1 种) ===
    'mcp_tool_progress':  (d: { progressToken: string; serverName: string; toolName: string; progress: number; total: number; message: string }) => {
        useMcpStore.getState().updateMcpProgress({
            type: 'mcp_tool_progress',
            progressToken: d.progressToken,
            serverName: d.serverName,
            toolName: d.toolName,
            progress: d.progress ?? 0,
            total: d.total ?? 0,
            message: d.message ?? '',
        });
    },

    // === 断线重连 (1 种) ===
    'session_restored':   (d) => handleSessionRestore(d),

    // === 心跳 (1 种) ===
    'pong':               ()  => { /* 连接存活确认, 重置超时计时器 */ },

    // === 新增: 压缩进度/token警告/中断确认 (3 种) ===
    'compact_event':      (d: { phase: string; usagePercent: number }) => {
        if (d.phase === 'warning') {
            useNotificationStore.getState().addNotification({
                key: 'compact-warning',
                level: 'warning',
                message: `\u4e0a\u4e0b\u6587\u4f7f\u7528\u7387 ${d.usagePercent}%\uff0c\u5373\u5c06\u81ea\u52a8\u538b\u7f29`,
                timeout: 5000,
            });
        }
    },
    'token_warning':      (d: { currentTokens: number; maxTokens: number; usagePercent: number; warningLevel: string }) => {
        useNotificationStore.getState().addNotification({
            key: 'token-warning',
            level: d.warningLevel === 'critical' ? 'error' : 'warning',
            message: `Token \u4f7f\u7528\u7387 ${d.usagePercent}% (${d.currentTokens}/${d.maxTokens})`,
            timeout: 5000,
        });
        useMessageStore.getState().setTokenWarning(d as TokenWarningPayload);
    },
    'interrupt_ack':      (d: { reason: string }) => {
        useSessionStore.getState().setStatus('idle');
        if (d.reason === 'USER_INTERRUPT') {
            useMessageStore.getState().addMessage({
                type: 'system',
                uuid: generateUUID(),
                timestamp: Date.now(),
                content: '\u5df2\u4e2d\u65ad AI \u54cd\u5e94',
                subtype: 'interrupt',
            } as Message);
        }
    },
    // === 新增: 模型/权限模式切换确认 (2 种) ===
    'model_changed':            (d: { model: string }) => {
        useSessionStore.getState().setModel(d.model);
    },
    // === 智能模型路由通知（图片自动路由到视觉模型）===
    // 后端在用户当前模型不支持图片时自动切换到视觉模型，并推送本事件用于 UI 提示。
    'model_routed':             (d: { originalModel: string; routedModel: string; routedModelName: string; reason: string }) => {
        const message = d.reason
            || `图片已自动路由到 ${d.routedModelName} 处理（原模型 ${d.originalModel} 不支持图片）`;
        useNotificationStore.getState().addNotification({
            key: `model-routed-${d.routedModel}`,
            level: 'info',
            message,
            timeout: 6000,
        });
    },
    'permission_mode_changed':  (d: { mode: string }) => {
        // ★ 大小写安全：后端 PermissionMode 枚举为大写（如 "AUTO"），前端类型为小写（如 'auto'）
        const normalizedMode = d.mode.toLowerCase() as PermissionMode;
        // 更新权限 Store 的权限模式
        usePermissionStore.getState().setPermissionMode(normalizedMode);
        // 清除挂起的权限请求
        if (usePermissionStore.getState().pendingPermissions.length > 0) {
            usePermissionStore.getState().clearPermissions();
        }
        // 通知用户权限模式已变更
        useNotificationStore.getState().addNotification({
            key: 'permission-mode-changed',
            level: 'info',
            message: `权限模式已切换为: ${normalizedMode}`,
            timeout: 3000,
        });
    },
    // === 新增: 命令结果/文件回退完成 (2 种) ===
    'command_result':     (d: { command: string; resultType: 'text' | 'jsx' | 'prompt'; output?: string; data?: Record<string, unknown> }) => {
        if (d.resultType === 'jsx' && d.data) {
            // JSX 类型: 创建带 metadata 的 system Message
            useMessageStore.getState().addMessage({
                type: 'system',
                uuid: generateUUID(),
                timestamp: Date.now(),
                content: '',
                subtype: 'jsx_result',
                metadata: { command: d.command, ...d.data },
            } as Message);
        } else if (d.resultType === 'prompt') {
            // PROMPT 命令: 显示简洁的加载指示器（不含完整提示词）
            useMessageStore.getState().addMessage({
                type: 'system',
                uuid: generateUUID(),
                timestamp: Date.now(),
                content: `AI 正在处理 /${d.command} 命令...`,
                subtype: 'prompt_executing',
            } as Message);
        } else {
            // TEXT 类型: LOCAL 命令结果，保持原有行为
            useMessageStore.getState().addMessage({
                type: 'system',
                uuid: generateUUID(),
                timestamp: Date.now(),
                content: `/${d.command}: ${d.output ?? ''}`,
                subtype: 'command_result',
            } as Message);
        }
    },
    'rewind_complete':    (d: { messageId: string; files: string[] }) => {
        useNotificationStore.getState().addNotification({
            key: `rewind-${d.messageId}`,
            level: 'info',
            message: `\u5df2\u56de\u9000 ${d.files.length} \u4e2a\u6587\u4ef6`,
            timeout: 5000,
        });
    },
    // === #37: Token 预算续写 nudge (1 种) ===
    'token_budget_nudge':  (d: { pct: number; currentTokens: number; budgetTokens: number }) => {
        useMessageStore.getState().setTokenBudgetState({
            pct: d.pct,
            currentTokens: d.currentTokens,
            budgetTokens: d.budgetTokens,
            visible: true,
        });
    },

    // === #38-40: Swarm 消息 (3 种) ===
    'swarm_state_update':  (d: import('@/types').SwarmStateUpdatePayload) => {
        useSwarmStore.getState().updateSwarmState(d);
    },
    'worker_progress':     (d: import('@/types').WorkerProgressPayload) => {
        useSwarmStore.getState().updateWorkerProgress(d);

        // Phase 2: 异常检测触发 — 独立 try-catch 保护，不影响主流程
        if (d.recentToolCalls && Array.isArray(d.recentToolCalls) && d.recentToolCalls.length > 0) {
            try {
                const firstItem = d.recentToolCalls[0];
                if (typeof firstItem === 'object' && firstItem !== null && 'toolName' in firstItem) {
                    const swarms = useSwarmStore.getState().swarms;
                    let worker: import('@/types').WorkerInfo | undefined;
                    for (const [, swarm] of swarms) {
                        if (swarm.workers[d.workerId]) {
                            worker = swarm.workers[d.workerId];
                            break;
                        }
                    }
                    if (worker) {
                        const anomalies = anomalyEngine.evaluate(worker, d.recentToolCalls as unknown as import('@/types/apos').ToolCallRecord[]);
                        anomalies.forEach(a => {
                            a.swarmId = d.swarmId || '';
                            useAnomalyStore.getState().addAnomaly(a);
                        });
                    }
                }
            } catch (err) {
                console.error('[dispatch] worker_progress anomaly detection failed:', err);
                // 异常检测失败不影响 Worker 进度更新（updateWorkerProgress 已在上方执行）
            }
        }
    },
    'permission_bubble':   (d: import('@/types').PermissionBubblePayload) => {
        useSwarmStore.getState().addPermissionBubble(d);
    },

    // === #41: Coordinator 工作流 (1 种) ===
    'workflow_phase_update': (d: import('@/types').WorkflowPhaseUpdatePayload) => {
        useCoordinatorStore.getState().updateWorkflowPhase(d);
    },

    // === 会话列表变更通知 (1 种) ===
    'session_list_updated': () => {
        // 延迟 200ms 再通知刷新，确保数据库落盘完成（SQLite WAL 可见性）
        setTimeout(() => {
            window.dispatchEvent(new CustomEvent('session-list-updated'));
        }, 200);
    },

    // === messageStore: 记忆变更通知 (1 种, 配合 P0-2 统一存储) ===
    'memory_update': (d: { action: 'created' | 'updated' | 'deleted'; entry?: Record<string, unknown>; entryId?: string }) => {
        useMessageStore.getState().addMessage({
            type: 'system',
            uuid: generateUUID(),
            timestamp: Date.now(),
            content: '',
            subtype: 'memory_update',
            metadata: d,
        } as Message);
    },

    // === planStore: Plan Mode 更新 (1 种) ===
    'plan_update': (d: {
        isPlanMode: boolean;
        planName?: string;
        planOverview?: string;
        steps?: PlanStep[];
        currentStepId?: string;
    }) => {
        const store = usePlanStore.getState();
        if (d.isPlanMode !== undefined) {
            d.isPlanMode
                ? store.enablePlanMode(d.planName || '', d.planOverview || '')
                : store.disablePlanMode();
        }
        if (d.steps) store.setSteps(d.steps);
        if (d.currentStepId) store.setCurrentStep(d.currentStepId);
    },

    // === messageStore: 差异化升级 v1.5 §4.5 C — 结构化输出自动可视化 (1 种) ===
    'visualization': (d: { uuid?: string; ts?: number; viewType: string; props?: Record<string, unknown> }) => {
        useMessageStore.getState().addMessage({
            type: 'visualization',
            uuid: d.uuid ?? generateUUID(),
            timestamp: d.ts ?? Date.now(),
            viewType: d.viewType,
            props: d.props ?? {},
        } as Message);
    },

    // === APOS / Runtime Verification: 验证结果 + 验证进度 (2 种) ===
    'verification_result': (d: any) => {
        try {
            // PR-C.6 路径：运行时验证（Runtime Verification）— payload 含 verdict/bundleId
            if ('verdict' in d) {
                useJourneyVerifyStore.getState().setResult(
                    d.verdict,
                    d.bundleId ?? '',
                    d.errorMessage ?? '',
                );
                return;
            }

            // Phase 2 路径：payload 直接包含 signal 字段（由 VerifyCheckService.pushVerificationResult 推送）
            if ('signal' in d && 'overallStatus' in d) {
                const response: import('@/types/apos').VerifyCheckResponse = {
                    results: d.results ?? [],
                    heuristic: d.heuristic ?? { affectedApiCount: 0, indirectImpactCount: 0, potentialImpactCount: 0, hasHighConfidenceImpact: false, truncated: false, filesAffected: [] },
                    signal: d.signal,
                    signalReason: d.signalReason ?? '',
                    overallStatus: d.overallStatus,
                    duration: d.duration ?? 0,
                    timestamp: d.timestamp ?? new Date().toISOString(),
                };
                useInsightStore.getState().handleVerificationResult(response);
                return;
            }

            // Phase 1 兼容路径：payload 包含 operationId + result（旧版 legacy-checks 推送）
            const { operationId, result } = d;
            if (!operationId || !result) {
                console.warn('[APOS] verification_result missing fields:', d);
                return;
            }
            const assessment = mapRunChecksResponseToRiskAssessment(result);
            useInsightStore.getState().addAssessment(operationId, assessment);
        } catch (err) {
            console.error('[APOS] Failed to process verification_result:', err);
        }
    },
    'verify_progress': (d: any) => {
        // PR-C.6 路径：运行时验证步骤进度（含 stepIndex/action/ok/durationMs）
        if (typeof d?.stepIndex === 'number' && typeof d?.action === 'string') {
            useJourneyVerifyStore.getState().addStepProgress({
                stepIndex: d.stepIndex,
                action: d.action,
                ok: !!d.ok,
                durationMs: typeof d.durationMs === 'number' ? d.durationMs : 0,
            });
            return;
        }
        // 旧路径：APOS 文件级进度（operationId + check + progress）
        console.debug('[APOS] verify_progress:', d.operationId, d.check, d.progress);
    },

    // === RV-4: 证据包待审批通知（推送至移动端审批面板） ===
    'verify_attention': (d: any) => {
        if (!d?.bundleId) {
            console.warn('[RV-4] verify_attention missing bundleId:', d);
            return;
        }
        useEvidenceStore.getState().addAttention({
            type: 'verify_attention',
            sessionId: d.sessionId ?? '',
            bundleId: d.bundleId,
            verdict: d.verdict ?? 'inconclusive',
            claim: d.claim ?? '',
            summary: d.summary ?? '',
            requiresApproval: d.requiresApproval !== false,
            timestamp: d.timestamp ?? new Date().toISOString(),
        });
    },
};

// ==================== 跨 Store 私有方法 ====================

/** 权限请求 — permissionStore + sessionStore */
function handlePermissionRequest(data: PermissionRequest): void {
    usePermissionStore.getState().showPermission(data);
    useSessionStore.getState().setStatus('waiting_permission');
    // ACK only after the reducer accepted the request into the visible inbox.
    void import('./stompClient').then(({ send }) =>
        send('/app/interaction-received', {
            interactionId: data.interactionId,
            deliveryGeneration: data.deliveryGeneration,
            correlationKey: data.toolUseId,
        }));
}

/**
 * 助手回合完成 — messageStore + sessionStore
 * v1.53.0: 不再更新 costStore，费用由 #15 cost_update 权威推送
 */
function handleMessageComplete(data: { usage: Usage; stopReason: string }): void {
    // 延迟 finalizeStream，确保最后的 stream_delta 已渲染
    queueMicrotask(() => {
        useMessageStore.getState().finalizeStream(data.usage);
        // ★ 回合结束时清除 token budget 状态
        useMessageStore.getState().clearTokenBudgetState();
        if (data.stopReason === 'end_turn') {
            useSessionStore.getState().setStatus('idle');
        }
    });
    // stopReason === 'tool_use' 时保持 streaming 状态，等待工具结果
}

/** API 错误 — messageStore + sessionStore */
function handleError(data: { code: string; message: string; retryable: boolean }): void {
    useMessageStore.getState().addMessage({
        type: 'system',
        uuid: generateUUID(),
        timestamp: Date.now(),
        content: data.message,
        subtype: 'error',
        errorCode: data.code,
        retryable: data.retryable,
    } as Message);
    useSessionStore.getState().setStatus('idle');
}

/** 上下文压缩完成 — messageStore + sessionStore */
function handleCompactComplete(data: {
    summary?: string; tokensSaved?: number;  // 旧格式: 自动压缩
    displayText?: string; compactionData?: Record<string, unknown>;  // 新格式: /compact 手动压缩
}): void {
    useSessionStore.getState().setStatus('idle');
    if (data.compactionData) {
        // 新格式: 来自 /compact 命令
        useMessageStore.getState().addMessage({
            type: 'system',
            uuid: generateUUID(),
            timestamp: Date.now(),
            content: data.displayText ?? '',
            subtype: 'compact_result',
            metadata: data.compactionData,
        } as Message);
    } else {
        // 旧格式: 来自自动压缩
        useMessageStore.getState().addMessage({
            type: 'system',
            uuid: generateUUID(),
            timestamp: Date.now(),
            content: `上下文已压缩，节省 ${data.tokensSaved ?? 0} tokens`,
            subtype: 'compact_boundary',
        } as Message);
    }
}

/**
 * 断线重连恢复 — 全量同步
 * messageStore + sessionStore + bridgeStore + notificationStore
 */
function handleSessionRestore(data: {
    bindRequestId: string;
    bindingEpoch: number;
    messages: Message[];
    activities?: ActivityData[];
    totalActivityCount?: number;
    hasMore?: boolean;
    metadata: {
        sessionId: string;
        model: string;
        status: 'idle' | 'interrupted';
    };
    runSnapshot?: { id: string; status: string };
    snapshotEventSeq?: number;
    activeToolCalls?: Array<{ toolUseId: string; toolName: string; input: unknown; startedAt?: number }>;
    costSummary?: { totalCost?: number };
}): void {
    const pending = pendingBinds.get(data.bindRequestId);
    if (!pending || pending.sessionId !== data.metadata.sessionId
            || pending.bindingEpoch !== data.bindingEpoch) return;
    // 1. 重置序列号
    resetSequence();

    // 2. 清除旧状态 → 加载完整消息历史
    useMessageStore.getState().clearMessages();
    data.messages.forEach(msg => useMessageStore.getState().addMessage(msg));
    useMessageStore.getState().replaceActiveToolCalls(data.activeToolCalls ?? []);
    if (data.runSnapshot?.id) {
        useRunStore.getState().replaceRecoverySnapshot(
            data.runSnapshot.id,
            data.runSnapshot as unknown as Record<string, unknown>,
            data.snapshotEventSeq ?? 0,
        );
    }

    // 3. 恢复会话元数据
    useSessionStore.getState().resumeSession(data.metadata.sessionId);
    useSessionStore.getState().setModel(data.metadata.model);
    // session_restored 是服务端 bind-session 的确认；只有收到它才记为已绑定。
    markSessionBound(data.metadata.sessionId);
    boundBindingEpoch = data.bindingEpoch;

    // 4. 恢复状态
    if (data.metadata.status === 'interrupted' || data.runSnapshot?.status === 'INTERRUPTED') {
        useSessionStore.getState().setStatus('idle');
        useNotificationStore.getState().addNotification({
            key: 'session-restore-interrupted',
            level: 'warning',
            message: 'AI 输出在断线期间被中断，你可以发送消息继续对话',
            timeout: 8000,
        });
    } else if (data.runSnapshot?.status === 'RUNNING' || data.runSnapshot?.status === 'CANCELLING') {
        useSessionStore.getState().setStatus('streaming');
    } else if (data.runSnapshot?.status === 'WAITING_INTERACTION') {
        useSessionStore.getState().setStatus('waiting_permission');
    } else {
        useSessionStore.getState().setStatus('idle');
    }

    if (data.costSummary && typeof data.costSummary.totalCost === 'number') {
        const currentCost = useCostStore.getState();
        useCostStore.getState().updateCost({
            sessionCost: data.costSummary.totalCost,
            totalCost: currentCost.totalCost,
            usage: currentCost.usage,
        });
    }

    // 5. 更新连接状态
    useBridgeStore.getState().updateBridgeStatus({ status: 'connected', url: '' });

    // 6. 恢复 Activity 数据（从后端持久化存储，最多 50 条最近记录）
    if (data.activities && data.activities.length > 0) {
        const activityStore = useActivityStore.getState();
        activityStore.clearAll();
        data.activities.forEach(a => {
            // 防御性规范化：确保 changedFiles 始终为数组（后端可能为 null）
            const normalized = {
                ...a,
                changedFiles: Array.isArray(a.changedFiles) ? a.changedFiles : [],
                sessionId: a.sessionId ?? data.metadata.sessionId,
            };
            activityStore.addActivity(normalized);
        });

        // Phase 2: hasMore 标志处理 — 通知 activityStore 有更多历史数据可按需加载
        if (data.hasMore && data.totalActivityCount) {
            activityStore.setHasMoreHistory(true, data.totalActivityCount);
        }
    }

    // 7. The snapshot already represents snapshotEventSeq. Frames received after
    // bind are held by the recovery gate and replayed only after this projection.
    void recoverPendingInteractions(data.metadata.sessionId)
        .catch((error) => useNotificationStore.getState().addNotification({
            key: 'run-event-recovery-failed', level: 'warning',
            message: `运行状态补齐失败：${error instanceof Error ? error.message : String(error)}`,
            timeout: 8000,
        }))
        .finally(() => finishBind(data.bindRequestId, true, true));
}

async function recoverPendingInteractions(sessionId: string): Promise<void> {
    const response = await fetch(`/api/interactions/pending?sessionId=${encodeURIComponent(sessionId)}`, {
        headers: { 'X-Session-Id': sessionId },
    });
    if (!response.ok) throw new Error(`INTERACTION_RECOVERY_${response.status}`);
    const pending = await response.json() as InteractionView[];
    for (const interaction of pending) {
        handleInteractionCreated(interaction);
    }
}
