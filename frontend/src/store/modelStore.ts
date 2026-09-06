/**
 * ModelStore — 模型能力缓存
 *
 * Task #22: 前端按模型能力（supportsImages / maxImages）动态限制图片上传。
 * 数据来源: GET /api/models（{@link com.aicodeassistant.controller.ModelController}）。
 * 持久化: 否（启动时按需拉取，模型集合变更需重新调用 fetchModels）。
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';

/** 单个模型能力快照（与后端 ModelController.ModelInfo 字段对齐） */
export interface ModelInfo {
    id: string;
    displayName: string;
    maxOutputTokens?: number;
    contextWindow?: number;
    supportsStreaming?: boolean;
    supportsThinking?: boolean;
    /** 是否支持图片输入 */
    supportsImages: boolean;
    /** 单次请求允许的图片数量上限（supportsImages=false 时无意义，约定为 0） */
    maxImages: number;
    supportsToolUse?: boolean;
    costPer1kInput?: number;
    costPer1kOutput?: number;
}

export interface ModelStoreState {
    models: ModelInfo[];
    defaultModel: string | null;
    loaded: boolean;
    loading: boolean;
    error: string | null;

    fetchModels: () => Promise<void>;
    /** 通过 modelId 查找能力，未找到时返回 null（调用方应做保守处理） */
    getCapabilities: (modelId: string | null | undefined) => ModelInfo | null;
}

let pendingModelFetch: Promise<void> | null = null;

export const useModelStore = create<ModelStoreState>()(
    subscribeWithSelector(immer((set, get) => ({
        models: [],
        defaultModel: null,
        loaded: false,
        loading: false,
        error: null,

        fetchModels: () => {
            if (pendingModelFetch) return pendingModelFetch;
            const request = (async () => {
                set(d => {
                    d.loading = true;
                    d.error = null;
                });
                try {
                    const res = await fetch('/api/models');
                    if (!res.ok) {
                        set(d => { d.error = `HTTP ${res.status}`; });
                        return;
                    }
                    const data = await res.json();
                    if (data && Array.isArray(data.models)) {
                        set(d => {
                            d.models = data.models.map((m: any) => ({
                                id: m.id,
                                displayName: m.displayName ?? m.id,
                                maxOutputTokens: m.maxOutputTokens,
                                contextWindow: m.contextWindow,
                                supportsStreaming: m.supportsStreaming,
                                supportsThinking: m.supportsThinking,
                                supportsImages: !!m.supportsImages,
                                maxImages: typeof m.maxImages === 'number' ? m.maxImages : 0,
                                supportsToolUse: m.supportsToolUse,
                                costPer1kInput: m.costPer1kInput,
                                costPer1kOutput: m.costPer1kOutput,
                            }));
                            d.defaultModel = data.defaultModel ?? null;
                            d.loaded = true;
                            d.error = null;
                        });
                    } else {
                        set(d => { d.error = 'Invalid model response'; });
                    }
                } catch (err) {
                    console.warn('Failed to fetch models:', err);
                    set(d => { d.error = 'Request failed'; });
                } finally {
                    set(d => { d.loading = false; });
                }
            })();
            pendingModelFetch = request;
            void request.finally(() => {
                if (pendingModelFetch === request) pendingModelFetch = null;
            });
            return request;
        },

        getCapabilities: (modelId) => {
            if (!modelId) return null;
            return get().models.find(m => m.id === modelId) ?? null;
        },
    })))
);
