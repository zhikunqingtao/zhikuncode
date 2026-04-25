/**
 * 前端 TypeScript 类型定义 — 从 Java 后端 record/enum 对齐
 * 
 * SPEC: §8.3 前端状态管理
 * 文件位置: frontend/src/types/index.ts (统一导出)
 */

// ==================== 消息类型 — 对齐 §5.1 Java sealed interface Message ====================

export type Message =
    | { type: 'user';      uuid: string; timestamp: number; content: ContentBlock[]; toolUseResult?: string }
    | { type: 'assistant';  uuid: string; timestamp: number; content: ContentBlock[]; stopReason: string; usage: Usage }
    | { type: 'system';     uuid: string; timestamp: number; content: string; subtype?: string;
        errorCode?: string; retryable?: boolean;
        metadata?: Record<string, unknown> }
    | { type: 'attachment'; uuid: string; timestamp: number;
        filePath: string; fileName: string; mimeType: string; size: number }
    | { type: 'grouped_tool_use'; uuid: string; timestamp: number;
        toolCalls: Array<{ toolUseId: string; toolName: string; status: string }> }
    | { type: 'collapsed_read_search'; uuid: string; timestamp: number;
        operations: Array<{ type: string; path?: string; query?: string }> };

export type ContentBlock =
    | { type: 'text'; text: string }
    | { type: 'tool_use'; toolUseId: string; toolName: string; input: Record<string, unknown> }
    | { type: 'tool_result'; toolUseId: string; content: string; isError: boolean }
    | { type: 'thinking'; thinking: string }
    | { type: 'redacted_thinking' }
    | { type: 'server_tool_use'; toolUseId: string; toolName: string }
    | { type: 'image'; mediaType: string; base64Data: string };

/** Token 用量 — 对齐 §5.1 Java record Usage */
export interface Usage {
    inputTokens: number;
    outputTokens: number;
    cacheReadInputTokens: number;
    cacheCreationInputTokens: number;
}

// ==================== ServerMessage 25 种类型 — 对齐 §8.5.1a ====================

export interface StreamDeltaPayload { type: 'stream_delta'; delta: string; messageId: string }
export interface ThinkingDeltaPayload { type: 'thinking_delta'; delta: string; messageId: string }
export interface ToolUseStartPayload { type: 'tool_use_start'; toolUseId: string; toolName: string; input: Record<string, unknown> }
export interface ToolUseProgressPayload { type: 'tool_use_progress'; toolUseId: string; progress: string }
export interface ToolResultPayload { type: 'tool_result'; toolUseId: string; content: string; isError: boolean }
export interface CompactStartPayload { type: 'compact_start'; sessionId: string }
export interface CompactCompletePayload { type: 'compact_complete'; sessionId: string; removedCount: number; summary: string }
export interface RateLimitPayload { type: 'rate_limit'; retryAfterMs: number; limitType: string }
export interface PermissionRequestPayload { type: 'permission_request'; toolUseId: string; toolName: string; input: Record<string, unknown>; riskLevel: 'low' | 'medium' | 'high'; reason: string; source?: 'subagent' | string; childSessionId?: string }
export interface CostUpdatePayload { type: 'cost_update'; sessionCost: number; totalCost: number; usage: Usage }
export interface TaskUpdatePayload { type: 'task_update'; taskId: string; status: string; progress?: unknown; result?: unknown }
export interface AgentSpawnPayload { type: 'agent_spawn'; taskId: string; agentName: string; agentType: string }
export interface AgentUpdatePayload { type: 'agent_update'; taskId: string; progress: unknown }
export interface AgentCompletePayload { type: 'agent_complete'; taskId: string; result: unknown }
export interface ElicitationPayload { type: 'elicitation'; requestId: string; question: string; options: unknown }
export interface PromptSuggestionPayload { type: 'prompt_suggestion'; text: string; promptId: string; generationRequestId: string }
export interface BridgeStatusPayload { type: 'bridge_status'; status: string; url: string }
export interface NotificationPayload { type: 'notification'; key: string; level: 'info' | 'success' | 'warning' | 'error'; message: string; priority?: NotificationPriority; timeout?: number }
export interface TeammateMessagePayload { type: 'teammate_message'; fromId: string; content: string }
export interface McpToolUpdatePayload { type: 'mcp_tool_update'; serverId: string; tools: McpTool[] }
export interface SessionRestoredPayload { type: 'session_restored'; messages: Message[]; metadata: { sessionId: string; model: string; status: string }; totalCount?: number; hasMore?: boolean; compactSummary?: string | null; oldestLoadedUuid?: string }
export interface MessageCompletePayload { type: 'message_complete'; messageId: string; usage: Usage; stopReason: string }
export interface PongPayload { type: 'pong'; timestamp: number }
export interface ErrorPayload { type: 'error'; code: string; message: string; retryable: boolean }
export interface CompactEventPayload { type: 'compact_event'; phase: string; usagePercent: number; currentTokens: number }
export interface TokenWarningPayload { type: 'token_warning'; currentTokens: number; maxTokens: number; usagePercent: number; warningLevel: string }
export interface InterruptAckPayload { type: 'interrupt_ack'; reason: string }
export interface ModelChangedPayload { type: 'model_changed'; model: string }
export interface PermissionModeChangedPayload { type: 'permission_mode_changed'; mode: string }
export interface CommandResultPayload { type: 'command_result'; command: string; resultType: 'text' | 'jsx'; output?: string; data?: Record<string, unknown> }
export interface RewindCompletePayload { type: 'rewind_complete'; messageId: string; files: string[] }
export interface TokenBudgetNudgePayload { type: 'token_budget_nudge'; pct: number; currentTokens: number; budgetTokens: number }
export interface PlanUpdatePayload { type: 'plan_update'; isPlanMode: boolean; planName?: string; planOverview?: string; steps?: Array<{ id: string; title: string; status: string }>; currentStepId?: string }

// ==================== Swarm 消息类型 (#38-#40) ====================

export interface SwarmStateUpdatePayload {
    type: 'swarm_state_update';
    swarmId: string;
    phase: 'INITIALIZING' | 'RUNNING' | 'IDLE' | 'SHUTTING_DOWN' | 'TERMINATED';
    activeWorkers: number;
    totalWorkers: number;
    completedTasks: number;
    totalTasks: number;
    workers: Record<string, WorkerSnapshot>;
}

export interface WorkerSnapshot {
    workerId: string;
    status: 'STARTING' | 'WORKING' | 'IDLE' | 'TERMINATED';
    currentTask: string;
    toolCallCount: number;
    tokenConsumed: number;
}

export interface WorkerProgressPayload {
    type: 'worker_progress';
    swarmId: string;
    workerId: string;
    status: 'STARTING' | 'WORKING' | 'IDLE' | 'TERMINATED';
    currentTask: string;
    toolCallCount: number;
    tokenConsumed: number;
    recentToolCalls: string[];
}

export interface PermissionBubblePayload {
    type: 'permission_bubble';
    requestId: string;
    workerId: string;
    toolName: string;
    riskLevel: 'low' | 'medium' | 'high';
    reason: string;
}

export type ServerMessage =
    | StreamDeltaPayload
    | ThinkingDeltaPayload
    | ToolUseStartPayload
    | ToolUseProgressPayload
    | ToolResultPayload
    | CompactStartPayload
    | CompactCompletePayload
    | RateLimitPayload
    | PermissionRequestPayload
    | CostUpdatePayload
    | TaskUpdatePayload
    | AgentSpawnPayload
    | AgentUpdatePayload
    | AgentCompletePayload
    | ElicitationPayload
    | PromptSuggestionPayload
    | BridgeStatusPayload
    | NotificationPayload
    | TeammateMessagePayload
    | McpToolUpdatePayload
    | SessionRestoredPayload
    | MessageCompletePayload
    | PongPayload
    | ErrorPayload
    | CompactEventPayload
    | TokenWarningPayload
    | InterruptAckPayload
    | ModelChangedPayload
    | PermissionModeChangedPayload
    | CommandResultPayload
    | RewindCompletePayload
    | TokenBudgetNudgePayload
    | SwarmStateUpdatePayload
    | WorkerProgressPayload
    | PermissionBubblePayload
    | WorkflowPhaseUpdatePayload;

// ==================== 工具相关类型 ====================

/** 工具结果 — 对齐 §3.3 Java record ToolResult */
export interface ToolResult {
    content: string;
    isError: boolean;
    metadata?: Record<string, unknown>;
}

/** 工具调用状态 — MessageStore 内部状态 */
export interface ToolCallState {
    toolName: string;
    input: unknown;
    status: 'pending' | 'running' | 'completed' | 'error' | 'permission_needed';
    result?: ToolResult;
    progress?: string;
    startTime: number;
    duration?: number;
}

// ==================== 通知类型 ====================

export type NotificationPriority = 'low' | 'normal' | 'high' | 'urgent';

export interface NotificationItem {
    key: string;
    level: 'info' | 'success' | 'warning' | 'error';
    message: string;
    priority: NotificationPriority;
    timeout: number;
    createdAt: number;
}

// ==================== 收件箱消息 ====================

export interface InboxMessage {
    id: string;
    fromId: string;
    content: string;
    timestamp: number;
    read: boolean;
}

// ==================== MCP 工具 ====================

export interface McpTool {
    name: string;
    description: string;
    inputSchema: Record<string, unknown>;
    serverId: string;
}

// ==================== MCP Prompt 类型 ====================

/** MCP Prompt 参数定义 */
export interface McpPromptArgument {
    name: string;
    description: string;
    required: boolean;
}

/** MCP Prompt 模板定义 */
export interface McpPrompt {
    name: string;
    description: string;
    serverName: string;
    arguments: McpPromptArgument[];
}

/** MCP Prompt 执行结果 */
export interface McpPromptExecuteResult {
    success: boolean;
    serverName: string;
    promptName: string;
    messages?: Array<{ role: string; content: string }>;
    error?: string;
    details?: string[];
}

// ==================== MCP 资源 ====================

/** MCP 资源定义 — 对齐后端 McpServerConnection.McpResourceDefinition */
export interface McpResource {
    uri: string;
    name: string;
    description: string;
    mimeType: string;
    serverName: string;
}

/** MCP 资源内容 — 对齐 GET /api/mcp/resources/read 响应 */
export interface McpResourceContent {
    uri: string;
    serverName: string;
    content: string;
}

/** MCP Prompt 定义 — 对齐后端 McpServerConnection.McpPromptDefinition */
export interface McpPrompt {
    name: string;
    description: string;
    serverName: string;
    arguments: McpPromptArgument[];
}

/** MCP Prompt 参数 */
export interface McpPromptArgument {
    name: string;
    description: string;
    required: boolean;
}

// ==================== AI 反向提问 ====================

export interface ElicitationRequest {
    requestId: string;
    question: string;
    options: unknown;
}

// ==================== 提示建议 ====================

export interface PromptSuggestion {
    text: string;
    promptId: string;
    shownAt: number | null;
    acceptedAt: number | null;
    generationRequestId: string;
}

// ==================== 任务状态 ====================

export interface TaskState {
    taskId: string;
    status: 'pending' | 'running' | 'completed' | 'failed' | 'cancelled';
    progress?: unknown;
    result?: unknown;
    isCoordinator?: boolean;
    agentName?: string;
    agentType?: string;
    parentTaskId?: string;
    createdAt: number;
}

// ==================== 桥接 ====================

export interface BridgeHandle {
    sessionId: string;
    url: string;
    status: 'connected' | 'disconnected' | 'reconnecting';
}

export interface BridgeConfig {
    url: string;
    authToken: string;
    reconnectDelay?: number;
}

// ==================== 附件 ====================

export interface Attachment {
    type: 'file' | 'image' | 'url';
    name: string;
    content: string;
    mimeType?: string;
}

// ==================== 权限相关 ====================

export type PermissionMode = 'default' | 'plan' | 'accept_edits' | 'dont_ask' | 'bypass_permissions' | 'auto' | 'bubble';

export interface PermissionDecision {
    toolUseId: string;
    decision: 'allow' | 'deny';
    remember?: boolean;
    scope?: string;
}

export interface DenialTrackingState {
    consecutiveDenials: number;
    totalDenials: number;
}

export interface PermissionRequest {
    toolUseId: string;
    toolName: string;
    input: Record<string, unknown>;
    riskLevel: 'low' | 'medium' | 'high';
    reason: string;
    source?: 'subagent' | string;
    childSessionId?: string;
}

// ==================== 配置相关 ====================

export interface ThemeConfig {
    mode: 'light' | 'dark' | 'system' | 'glass';
    accentColor: string;
    fontSize?: string;
    fontFamily?: string;
    borderRadius?: string;
}

export interface OutputStyleDef {
    name: string;
    description: string;
    keepCodingInstructions: boolean;
    content: string;
}

export interface Config {
    theme: ThemeConfig;
    locale: string;
    autoCompact: { enabled: boolean; threshold: number };
    verbose: boolean;
    expandedView: boolean;
    outputStyle: { availableStyles: OutputStyleDef[]; activeStyleName: string | null };
    defaultModel: string;
}

// ==================== 命令 ====================

/** Slash 命令定义 — 对齐 §8.2.6a CommandPalette */
export interface Command {
    name: string;
    description: string;
    group?: string;
    hidden?: boolean;
}

/** 输入提交事件 — 对齐 §8.2.6a.7 SubmitEvent */
export interface SubmitEvent {
    text: string;
    attachments: Attachment[];
    references: Map<string, string>;
    isFastMode: boolean;
    effortLevel?: 'low' | 'medium' | 'high';
}

/** 本地附件 (含 File 对象，用于上传) */
export interface LocalAttachment {
    id: string;
    name: string;
    size: number;
    type: string;
    file: File;
}

// ==================== 输入路由目标 ====================

export type InputTarget =
    | { type: 'main' }
    | { type: 'agent'; taskId: string }
    | { type: 'coordinator'; taskId: string };

// ==================== Coordinator 工作流类型 (#41) ====================

export type WorkflowPhaseName = 'Research' | 'Synthesis' | 'Implementation' | 'Verification';
export type WorkflowStatus = 'NOT_STARTED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export interface WorkflowPhaseUpdatePayload {
    type: 'workflow_phase_update';
    workflowId: string;
    phaseName: WorkflowPhaseName | '';
    status: WorkflowStatus;
    phaseIndex: number;      // 0-3, -1 when completed
    totalPhases: number;     // 4
    phasePrompt: string;
    objective: string;
}

export interface WorkflowPhaseState {
    name: WorkflowPhaseName;
    index: number;
    status: 'pending' | 'active' | 'completed' | 'skipped';
    prompt: string;
    startTime?: number;
    endTime?: number;
}

export interface WorkflowState {
    workflowId: string;
    objective: string;
    status: WorkflowStatus;
    currentPhaseIndex: number;
    phases: WorkflowPhaseState[];
    startTime: number;
}

export interface DelegationWarning {
    id: string;
    message: string;
    timestamp: number;
    dismissed: boolean;
}

export interface AgentTask {
    taskId: string;
    agentName: string;
    agentType: string;
    description: string;
    status: 'running' | 'completed' | 'failed';
    progress?: string;
    result?: string;
    startTime: number;
}

// ==================== Swarm 状态类型 ====================

export interface SwarmInfo {
    swarmId: string;
    teamName: string;
    phase: 'INITIALIZING' | 'RUNNING' | 'IDLE' | 'SHUTTING_DOWN' | 'TERMINATED';
    activeWorkers: number;
    totalWorkers: number;
    completedTasks: number;
    totalTasks: number;
    workers: Record<string, WorkerInfo>;
}

export interface WorkerInfo {
    workerId: string;
    status: 'STARTING' | 'WORKING' | 'IDLE' | 'TERMINATED';
    currentTask: string;
    toolCallCount: number;
    tokenConsumed: number;
    recentToolCalls: string[];
}

export interface PermissionBubbleRequest {
    requestId: string;
    workerId: string;
    toolName: string;
    riskLevel: 'low' | 'medium' | 'high';
    reason: string;
    timestamp: number;
}

export interface SwarmLogEntry {
    id: string;
    timestamp: number;
    type: 'worker_start' | 'worker_complete' | 'worker_error' | 'task_assigned' | 'permission_bubble' | 'message';
    workerId?: string;
    content: string;
}
