/**
 * PromptDraftStore — 输入框草稿（文本 + 图片附件 + 本地文件引用）按会话暂存
 * SPEC: §8.3 前端状态管理
 * P1 修复：移动端底部导航切换（AppLayout 条件渲染）会整体卸载聊天树，
 * 草稿原存于组件内 useState，卸载即丢失；现统一托管到本 Store，
 * 卸载/重挂载循环后草稿可恢复，且不同会话的草稿相互隔离。
 * P2 修复：本地文件引用（native picker 路径 + OSS 已上传引用）原存于
 * useLocalFileReference 的组件 useState，移动端打开文件管理卸载输入条后
 * 引用丢失；现与附件同模式按键托管到本 Store。
 *
 * 持久化: 仅内存（不使用 persist/localStorage）——附件 LocalAttachment
 * 持有 File 引用与 blob: ObjectURL，无法序列化，刷新页面后失效属预期。
 *
 * 键控: drafts[sessionId]；无活动会话时统一回落到 PROMPT_DRAFT_FALLBACK_KEY，
 * 保证未建会话时的草稿同样在卸载后可恢复。sessionId 先经
 * resolvePromptDraftKey 规整后再调用各 Action（Hook 侧已处理）。
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import type { LocalAttachment, PickedLocalFile, PublishedLocalFile } from '@/types';
import { generateUUID } from '@/utils/uuid';

/** 无活动会话时的兜底草稿键 */
export const PROMPT_DRAFT_FALLBACK_KEY = '__none__';

/** 将可空 sessionId 规整为稳定的草稿键 */
export function resolvePromptDraftKey(sessionId?: string | null): string {
    return sessionId ? sessionId : PROMPT_DRAFT_FALLBACK_KEY;
}

/** 单个会话的草稿（输入文本 + 图片附件 + 本地文件引用） */
export interface PromptDraft {
    /** 草稿身份随首次会话迁移保留，异步操作不依赖当前选中的会话。 */
    id: string;
    input: string;
    attachments: LocalAttachment[];
    /** native picker 暂存的本地路径引用（P2 修复：随草稿托管，卸载不丢失） */
    localFiles: PickedLocalFile[];
    /** 已上传 OSS 的本地文件引用（同上） */
    publishedLocalFiles: PublishedLocalFile[];
}

/** 新建草稿记录的默认形状（字段缺省会破坏精确快照断言与读取一致性） */
function createEmptyDraft(): PromptDraft {
    return { id: generateUUID(), input: '', attachments: [], localFiles: [], publishedLocalFiles: [] };
}

/** setState 风格取值：直接值或基于前值的函数式更新 */
export type PromptDraftUpdater<T> = T | ((prev: T) => T);

export interface PromptDraftStoreState {
    // 状态
    /** drafts[sessionId] = 该会话的输入草稿与图片附件（仅内存） */
    drafts: Record<string, PromptDraft>;

    // Actions
    ensureDraft: (sessionId: string) => string;
    setInput: (sessionId: string, value: PromptDraftUpdater<string>) => void;
    setAttachments: (sessionId: string, value: PromptDraftUpdater<LocalAttachment[]>) => void;
    setLocalFiles: (sessionId: string, value: PromptDraftUpdater<PickedLocalFile[]>) => void;
    setPublishedLocalFiles: (
        sessionId: string,
        value: PromptDraftUpdater<PublishedLocalFile[]>,
    ) => void;
    /**
     * 清空某会话草稿（无记录时为空操作）。
     * 注意：本 Action 不回收附件的 previewUrl；调用方若丢弃带预览 URL 的
     * 附件，需先自行 URL.revokeObjectURL（现有提交/移除路径均已处理）。
     */
    clear: (sessionId: string) => void;
    /**
     * 首个会话创建（sessionId null → id 绑定，视为同一草稿的延续）时，
     * 将兜底键下的草稿整体迁移到新会话键；目标键已有草稿时以兜底草稿为准。
     * 无兜底草稿或 sessionId 为空时为空操作。
     */
    migrateFallbackTo: (sessionId: string) => void;
}

export const usePromptDraftStore = create<PromptDraftStoreState>()(
    immer((set, get) => ({
        drafts: {},

        ensureDraft: (sessionId) => {
            if (!get().drafts[sessionId]) {
                set(d => { d.drafts[sessionId] = createEmptyDraft(); });
            }
            return get().drafts[sessionId].id;
        },

        setInput: (sessionId, value) => set(d => {
            const record = d.drafts[sessionId] ?? createEmptyDraft();
            d.drafts[sessionId] = record;
            record.input = typeof value === 'function' ? value(record.input) : value;
        }),
        setAttachments: (sessionId, value) => set(d => {
            const record = d.drafts[sessionId] ?? createEmptyDraft();
            d.drafts[sessionId] = record;
            record.attachments = typeof value === 'function' ? value(record.attachments) : value;
        }),
        setLocalFiles: (sessionId, value) => set(d => {
            const record = d.drafts[sessionId] ?? createEmptyDraft();
            d.drafts[sessionId] = record;
            record.localFiles = typeof value === 'function' ? value(record.localFiles) : value;
        }),
        setPublishedLocalFiles: (sessionId, value) => set(d => {
            const record = d.drafts[sessionId] ?? createEmptyDraft();
            d.drafts[sessionId] = record;
            record.publishedLocalFiles = typeof value === 'function'
                ? value(record.publishedLocalFiles) : value;
        }),
        clear: (sessionId) => set(d => {
            delete d.drafts[sessionId];
        }),
        migrateFallbackTo: (sessionId) => set(d => {
            if (!sessionId) return;
            const fallback = d.drafts[PROMPT_DRAFT_FALLBACK_KEY];
            if (!fallback) return;
            d.drafts[sessionId] = fallback;
            delete d.drafts[PROMPT_DRAFT_FALLBACK_KEY];
        }),
    })),
);

/** Capture at the start of an operation, then resolve after each await.
 * Migration preserves the ID; clearing/replacing a draft invalidates old work.
 */
export function capturePromptDraftTarget(sessionKey: string): () => string | undefined {
    const id = usePromptDraftStore.getState().ensureDraft(sessionKey);
    return () => Object.entries(usePromptDraftStore.getState().drafts)
        .find(([, draft]) => draft.id === id)?.[0];
}
