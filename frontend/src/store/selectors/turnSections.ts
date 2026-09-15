/**
 * TurnSections — 轮内「三层模型」切分 + 任务分节推导（纯函数，无状态）
 *
 * 三层模型：轮 = 用户 query ｜ 过程区 ｜ 回复。此处只切分消息；
 * 简洁档默认折叠三层，平衡与详细档保留完整 query 和 answer，由视图层控制。
 *
 * splitTurnLayers：把一轮的消息切成三层 —
 * - instruction：turn.instruction（query 层）；
 * - answer：轮内「最终回复」——从尾部起第一条「含 text 块且无 tool_use 块」的
 *   assistant 消息，且其位置在最后一个含 tool_use 的 assistant 消息之后；
 *   流式中的无工具 assistant 消息（streamingMessageId 命中）可作为候选 answer，
 *   保证运行中回复区域实时可见。无合格消息时 answer = null（该轮无最终回复，
 *   如运行中断在工具阶段）；
 * - process：其余全部消息（过程区输入），但轮次错误/中断与命令结果
 *   系统消息提升到 tail（与 answer 同层常驻可见），保留结果展示及交互入口。
 *
 * buildTurnTaskSections：过程区 → 任务分节 —
 * - 首选：轮内 task_boundary 系统消息（subtype='task_boundary'，payload 防御性
 *   兼容 task_id/taskId、turn_index/turnIndex 驼峰与下划线），按 seq 排序
 *   （相同/缺失 seq 回退消息序）切分；每条 boundary 开启一个分节；
 * - 兜底（历史会话无 boundary）：扫描过程区 TodoWrite 工具调用的 todos payload
 *   （优先成功结果 newTodos，兼容旧 todos 与无结果时的 input.todos），某 todo 标题首次以
 *   status=in_progress 出现后开启分节（有 ID 按 ID，否则按标题去重）；最近一次
 *   TodoWrite 快照作为任务清单（含仍 pending 的任务，供 compact 展开清单显示）；
 * - 首个分节起点之前的过程块归入「准备」段（prep，仅 detailed 展示）；
 * - 两者都没有 → hasTaskData=false（该轮无分节，走聚合条路径）。
 *
 * 复杂度：O(过程区消息 × 块数)；不修改输入、不做深拷贝（messages 持有原引用）。
 */

import type { ContentBlock, Message } from '@/types';
import type { Turn } from './turnProjection';
import { prepExpandKey, sectionExpandKey, turnExpandKey, turnMessageExpandKey } from '@/store/turnViewStore';

// ==================== 三层切分 ====================

/** 轮次结果和命令结果常驻可见，避免交互面板被过程区摘要隐藏。 */
const TAIL_SYSTEM_SUBTYPES = new Set([
    'error', 'provider_error', 'interrupt',
    'jsx_result', 'command_result', 'compact_result',
    'compact_boundary', 'command',
]);

export interface TurnLayers {
    /** 完整用户 query（preamble 轮为 null） */
    instruction: Message | null;
    /** 轮内 steering 用户消息（运行中追加的干预指令）——与 query 同层 */
    steering: Message[];
    /** 过程区消息（按原始顺序，引用共享） */
    process: Message[];
    /** 最终回复消息（assistant；无合格回复时为 null） */
    answer: Message | null;
    /** 提升出过程区的轮次结果与命令结果，常驻可见 */
    tail: Message[];
}

function isTailSystemMessage(message: Message): boolean {
    return message.type === 'system'
        && message.subtype !== undefined
        && TAIL_SYSTEM_SUBTYPES.has(message.subtype);
}

/** steering / 轮内补充指令：含 text 或 image 块的 user 消息（与投影的「指令」判定同口径） */
function isRenderableUserMessage(message: Message): boolean {
    return message.type === 'user'
        && message.content.some(block => block.type === 'text' || block.type === 'image');
}

/** answer 资格：含 ≥1 个 text 块且不含 tool_use 块的 assistant 消息 */
function isAnswerCandidate(message: Message): boolean {
    if (message.type !== 'assistant') return false;
    let hasText = false;
    for (const block of message.content) {
        if (block.type === 'tool_use') return false;
        if (block.type === 'text') hasText = true;
    }
    return hasText;
}

function hasToolUseBlock(message: Message): boolean {
    return message.type === 'assistant'
        && message.content.some(block => block.type === 'tool_use');
}

/**
 * 三层切分。streamingMessageId 命中轮内无工具 assistant 消息时作为候选 answer
 * （流式回复实时渲染区域，行为与既有轮内流式渲染一致）。
 */
export function splitTurnLayers(turn: Turn, streamingMessageId?: string | null): TurnLayers {
    const rest = turn.instruction
        ? turn.messages.filter(message => message !== turn.instruction)
        : [...turn.messages];
    const steering: Message[] = [];
    const tail: Message[] = [];
    const body: Message[] = [];
    for (const message of rest) {
        if (isRenderableUserMessage(message)) steering.push(message);
        else if (isTailSystemMessage(message)) tail.push(message);
        else body.push(message);
    }

    // 工具出现即归入过程区；只有无工具的流式段才保持在回复区。
    let answer: Message | null = null;
    if (streamingMessageId != null) {
        const streaming = body.find(
            message => message.uuid === streamingMessageId && message.type === 'assistant',
        );
        if (streaming && !hasToolUseBlock(streaming)) answer = streaming;
    }
    if (!answer) {
        // 最后一个含 tool_use 的 assistant 消息位置：其后的文本消息才是「最终回复」
        let lastToolIndex = -1;
        body.forEach((message, index) => {
            if (hasToolUseBlock(message)) lastToolIndex = index;
        });
        for (let index = body.length - 1; index > lastToolIndex; index -= 1) {
            if (isAnswerCandidate(body[index])) {
                answer = body[index];
                break;
            }
        }
    }

    return {
        instruction: turn.instruction,
        steering,
        process: answer ? body.filter(message => message !== answer) : body,
        answer,
        tail,
    };
}

// ==================== 任务分节 ====================

/** TodoWrite 工具名（与后端 CC 风格命名一致） */
const TODOWRITE_TOOL_NAME = 'TodoWrite';
/** 「准备」段固定标题 */
export const PREP_SECTION_TITLE = '准备';

export interface TurnTaskSection {
    /** 分节序号（轮内 0 起；prep 段不参与编号，固定 -1） */
    index: number;
    /** 任务 ID（task_boundary 提供；TodoWrite 兜底为 null） */
    taskId: string | null;
    /** 任务标题 */
    title: string;
    /** 分节来源 */
    source: 'boundary' | 'todowrite';
    /** 是否「准备」段（首个任务起点之前；仅 detailed 展示） */
    isPrep: boolean;
    /** 分节内过程消息（连续子序列，引用共享） */
    messages: Message[];
    startedAt: number;
    endedAt: number;
}

export interface TurnTaskItem {
    /** 任务标题 */
    title: string;
    /** 对应分节序号；无分节（TodoWrite 快照中仍 pending、从未 in_progress 的任务）为 null */
    sectionIndex: number | null;
    /**
     * TodoWrite 兜底来源时的原始 todo 状态（pending/in_progress/completed…），
     * boundary 来源为 null（状态由分节内容实时推导）。
     */
    todoStatus: string | null;
}

export interface TurnTaskSections {
    /** 任务分节（不含 prep） */
    sections: TurnTaskSection[];
    /** 「准备」段（首个任务起点之前的过程块；无为 null） */
    prep: TurnTaskSection | null;
    /** 任务清单（compact 展开态只读清单数据源） */
    tasks: TurnTaskItem[];
    /** 是否存在任务分节数据；false → 该轮走聚合条路径 */
    hasTaskData: boolean;
}

export const EMPTY_TASK_SECTIONS: TurnTaskSections = {
    sections: [],
    prep: null,
    tasks: [],
    hasTaskData: false,
};

// ---------- task_boundary 解析（首选） ----------

interface TaskBoundaryInfo {
    taskId: string | null;
    title: string;
    seq: number | null;
    msgIndex: number;
}

function asRecord(value: unknown): Record<string, unknown> | null {
    return value !== null && typeof value === 'object' && !Array.isArray(value)
        ? value as Record<string, unknown>
        : null;
}

/**
 * 解析 task_boundary 系统消息。payload 防御性兼容：
 * - 字段名：task_id/taskId、turn_index/turnIndex 驼峰与下划线均可；
 * - 载体：优先 metadata，缺失时尝试 content JSON。
 */
function parseTaskBoundary(message: Message, msgIndex: number): TaskBoundaryInfo | null {
    if (message.type !== 'system' || message.subtype !== 'task_boundary') return null;
    let payload = asRecord(message.metadata);
    if (!payload && typeof message.content === 'string' && message.content.trim().startsWith('{')) {
        try {
            payload = asRecord(JSON.parse(message.content));
        } catch {
            payload = null;
        }
    }
    if (!payload) return null;
    if (![payload.task_id ?? payload.taskId, payload.title]
        .some(value => typeof value === 'string' && value.trim().length > 0)) return null;
    const taskIdRaw = payload.task_id ?? payload.taskId;
    const titleRaw = payload.title;
    const seqRaw = payload.seq;
    return {
        taskId: typeof taskIdRaw === 'string' && taskIdRaw.length > 0 ? taskIdRaw : null,
        title: typeof titleRaw === 'string' && titleRaw.length > 0 ? titleRaw : '任务',
        seq: typeof seqRaw === 'number' && Number.isFinite(seqRaw) ? seqRaw : null,
        msgIndex,
    };
}

// ---------- TodoWrite 兜底解析 ----------

interface TodoItem {
    id: string | null;
    title: string;
    status: string;
}

function parseTodoEntries(value: unknown): TodoItem[] | null {
    if (!Array.isArray(value)) return null;
    const todos: TodoItem[] = [];
    for (const entry of value) {
        const record = asRecord(entry);
        if (!record) continue;
        const title = record.content ?? record.title ?? record.name;
        if (typeof title !== 'string' || title.length === 0) continue;
        todos.push({
            id: typeof record.id === 'string' && record.id ? record.id : null,
            title,
            status: typeof record.status === 'string'
                ? record.status.toLowerCase().replace(/^complete$/, 'completed') : 'pending',
        });
    }
    return todos;
}

type ToolUseBlock = Extract<ContentBlock, { type: 'tool_use' }>;

/** 成功结果是实际状态；明确失败不产生分节。无结果的旧记录才尽力使用入参。 */
function extractTodos(block: ToolUseBlock): { todos: TodoItem[]; previous: TodoItem[] } | null {
    if (block.result?.isError) return null;
    const content = block.result?.content;
    if (typeof content === 'string') {
        try {
            const parsed = asRecord(JSON.parse(content));
            const todos = parseTodoEntries(parsed?.newTodos) ?? parseTodoEntries(parsed?.todos);
            if (todos) return { todos, previous: parseTodoEntries(parsed?.oldTodos) ?? [] };
        } catch {
            return null;
        }
        return null;
    }
    const todos = parseTodoEntries(asRecord(block.input)?.todos);
    return todos ? { todos, previous: [] } : null;
}

function todoKey(todo: TodoItem): string {
    return todo.id ? `id:${todo.id}` : `title:${todo.title}`;
}

// ---------- 分节装配 ----------

interface SectionStart {
    msgIndex: number;
    title: string;
    taskId: string | null;
    source: 'boundary' | 'todowrite';
}

function assembleSections(process: Message[], starts: SectionStart[]): TurnTaskSections {
    const ordered = [...starts].sort((a, b) => a.msgIndex - b.msgIndex);
    const sections: TurnTaskSection[] = ordered.map((start, index) => {
        const end = ordered[index + 1]?.msgIndex ?? process.length;
        const messages = process.slice(start.msgIndex, end);
        return {
            index,
            taskId: start.taskId,
            title: start.title,
            source: start.source,
            isPrep: false,
            messages,
            startedAt: messages[0]?.timestamp ?? 0,
            endedAt: messages[messages.length - 1]?.timestamp ?? 0,
        };
    });
    const firstStart = ordered[0]?.msgIndex ?? 0;
    const prepMessages = process.slice(0, firstStart);
    const prep: TurnTaskSection | null = prepMessages.length > 0
        ? {
            index: -1,
            taskId: null,
            title: PREP_SECTION_TITLE,
            source: ordered[0]?.source ?? 'boundary',
            isPrep: true,
            messages: prepMessages,
            startedAt: prepMessages[0].timestamp,
            endedAt: prepMessages[prepMessages.length - 1].timestamp,
        }
        : null;
    return { sections, prep, tasks: [], hasTaskData: true };
}

/**
 * 过程区 → 任务分节。首选 task_boundary 切分；无 boundary 时走 TodoWrite 兜底；
 * 两者都无 → EMPTY_TASK_SECTIONS（hasTaskData=false）。
 */
export function buildTurnTaskSections(process: Message[]): TurnTaskSections {
    // ---------- 首选：task_boundary ----------
    const boundaries: TaskBoundaryInfo[] = [];
    const seenBoundaryIds = new Set<string>();
    process.forEach((message, index) => {
        const boundary = parseTaskBoundary(message, index);
        if (boundary && !seenBoundaryIds.has(message.uuid)) {
            seenBoundaryIds.add(message.uuid);
            boundaries.push(boundary);
        }
    });
    if (boundaries.length > 0) {
        // 按 seq 排序（缺失 seq 回退消息序）；分节范围以消息序为准（防御乱序 seq）
        const bySeq = [...boundaries].sort((a, b) =>
            (a.seq ?? a.msgIndex) - (b.seq ?? b.msgIndex));
        const starts: SectionStart[] = bySeq.map(boundary => ({
            msgIndex: boundary.msgIndex,
            title: boundary.title,
            taskId: boundary.taskId,
            source: 'boundary',
        }));
        const result = assembleSections(process, starts);
        // 任务清单顺序与分节一致（状态由分节内容实时推导）
        result.tasks = result.sections.map(section => ({
            title: section.title,
            sectionIndex: section.index,
            todoStatus: null,
        }));
        return result;
    }

    // ---------- 兜底：TodoWrite 首次 in_progress ----------
    const starts: SectionStart[] = [];
    const seenTasks = new Set<string>();
    let latestTodos: TodoItem[] | null = null;
    // 用同步循环保留 latestTodos 的类型推导，避免闭包赋值后被误判为 null/never。
    for (const [index, message] of process.entries()) {
        if (message.type !== 'assistant') continue;
        for (const block of message.content) {
            if (block.type !== 'tool_use' || block.toolName !== TODOWRITE_TOOL_NAME) continue;
            const snapshot = extractTodos(block);
            if (!snapshot) continue;
            latestTodos = snapshot.todos;
            const previous = new Map(snapshot.previous.map(todo => [todoKey(todo), todo.status]));
            for (const todo of snapshot.todos) {
                const key = todoKey(todo);
                if (todo.status !== 'in_progress' || seenTasks.has(key)
                    || previous.get(key) === 'in_progress') continue;
                seenTasks.add(key);
                // 与 boundary 对齐：本次 TodoWrite 及同消息中的其他工具留在前一节/准备区。
                starts.push({ msgIndex: index + 1, title: todo.title, taskId: todo.id, source: 'todowrite' });
            }
        }
    }
    if (starts.length === 0) return EMPTY_TASK_SECTIONS;

    const result = assembleSections(process, starts);
    const sectionIndexByTask = new Map(result.sections.map(section => [
        section.taskId ? `id:${section.taskId}` : `title:${section.title}`, section.index,
    ]));
    // 任务清单 = 最近一次 TodoWrite 快照（含仍 pending、未形成分节的任务）
    result.tasks = (latestTodos ?? []).map(todo => ({
        title: todo.title,
        sectionIndex: sectionIndexByTask.get(todoKey(todo)) ?? null,
        todoStatus: todo.status,
    }));
    return result;
}

// ==================== 展开 key 辅助 ====================

/** 过程消息中的 tool_use 块总数（聚合条「M 步」数据源） */
export function countToolUses(messages: Message[]): number {
    let total = 0;
    for (const message of messages) {
        if (message.type !== 'assistant') continue;
        for (const block of message.content) {
            if (block.type === 'tool_use') total += 1;
        }
    }
    return total;
}

/**
 * 一轮的全部展开 key（新指令自动折叠前轮、深链定位的入参）：
 * 轮级 key + 各任务分节 key + prep key。
 */
export function collectTurnExpandKeys(turn: Turn): string[] {
    const keys = [turnExpandKey(turn.index)];
    const { process } = splitTurnLayers(turn);
    const { sections, prep } = buildTurnTaskSections(process);
    for (const section of sections) keys.push(sectionExpandKey(turn.index, section.index));
    if (prep) keys.push(prepExpandKey(turn.index));
    return keys;
}

/**
 * 深链定位：消息 uuid → 所属分节的展开 key。
 * 命中 prep / 任务分节返回对应 key；命中轮级过程（无分节）返回轮级 key；
 * 不在过程区（instruction / answer / tail）返回 null。
 */
export function findProcessExpandKey(
    turn: Turn,
    messageUuid: string,
): string | null {
    const { process } = splitTurnLayers(turn);
    if (!process.some(message => message.uuid === messageUuid)) return null;
    const { sections, prep, hasTaskData } = buildTurnTaskSections(process);
    if (!hasTaskData) return turnExpandKey(turn.index);
    if (prep?.messages.some(message => message.uuid === messageUuid)) {
        return prepExpandKey(turn.index);
    }
    for (const section of sections) {
        if (section.messages.some(message => message.uuid === messageUuid)) {
            return sectionExpandKey(turn.index, section.index);
        }
    }
    return turnExpandKey(turn.index);
}

/** 简洁档深链：定位问题、补充指令或回复的折叠正文，保持当前密度。 */
export function findTurnMessageExpandKey(turn: Turn, messageUuid: string): string | null {
    const { instruction, steering, answer } = splitTurnLayers(turn);
    if (instruction?.uuid === messageUuid) return turnMessageExpandKey(turn.index, 'query');
    if (answer?.uuid === messageUuid) return turnMessageExpandKey(turn.index, 'answer');
    const index = steering.findIndex(message => message.uuid === messageUuid);
    return index < 0 ? null : turnMessageExpandKey(turn.index, `steering-${index}`);
}
