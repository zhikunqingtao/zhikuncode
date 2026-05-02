/**
 * ApiContractStore — OpenAPI 契约数据管理
 * 管理从 Python 分析服务获取的 OpenAPI 规范数据状态
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';

// ── 类型定义（基于 OpenAPI 3.x 规范） ──

export interface SchemaObject {
    type?: string;
    properties?: Record<string, SchemaObject>;
    items?: SchemaObject;
    required?: string[];
    description?: string;
    enum?: unknown[];
    $ref?: string;
    allOf?: SchemaObject[];
    oneOf?: SchemaObject[];
    anyOf?: SchemaObject[];
    format?: string;
    example?: unknown;
    default?: unknown;
    nullable?: boolean;
    readOnly?: boolean;
    writeOnly?: boolean;
    additionalProperties?: boolean | SchemaObject;
}

export interface ParameterObject {
    name: string;
    in: 'query' | 'header' | 'path' | 'cookie';
    description?: string;
    required?: boolean;
    schema?: SchemaObject;
    example?: unknown;
}

export interface MediaTypeObject {
    schema?: SchemaObject;
    example?: unknown;
}

export interface RequestBodyObject {
    description?: string;
    content?: Record<string, MediaTypeObject>;
    required?: boolean;
}

export interface ResponseObject {
    description?: string;
    content?: Record<string, MediaTypeObject>;
}

export interface EndpointDetail {
    summary?: string;
    description?: string;
    tags?: string[];
    parameters?: ParameterObject[];
    requestBody?: RequestBodyObject;
    responses?: Record<string, ResponseObject>;
    operationId?: string;
    deprecated?: boolean;
}

export interface TagObject {
    name: string;
    description?: string;
}

export interface OpenApiSpec {
    openapi: string;
    info: { title: string; version: string; description?: string };
    paths: Record<string, Record<string, EndpointDetail>>;
    components?: { schemas?: Record<string, SchemaObject> };
    tags?: TagObject[];
}

export type DataSource = 'merged' | 'java' | 'python';

// ── Store 状态 ──

export interface ApiContractState {
    openApiSpec: OpenApiSpec | null;
    source: DataSource;
    selectedEndpoint: { path: string; method: string } | null;
    searchQuery: string;
    isLoading: boolean;
    error: string | null;
    warnings: string[];

    fetchOpenApiSpec: (source?: DataSource) => Promise<void>;
    setSource: (source: DataSource) => void;
    setSelectedEndpoint: (endpoint: { path: string; method: string } | null) => void;
    setSearchQuery: (query: string) => void;
    reset: () => void;
}

const SOURCE_ENDPOINT_MAP: Record<DataSource, string> = {
    merged: '/api/analysis/openapi/merged',
    java: '/api/analysis/openapi/java',
    python: '/api/analysis/openapi/python',
};

export const useApiContractStore = create<ApiContractState>()(
    subscribeWithSelector(immer((set) => ({
        openApiSpec: null,
        source: 'merged',
        selectedEndpoint: null,
        searchQuery: '',
        isLoading: false,
        error: null,
        warnings: [],

        fetchOpenApiSpec: async (source) => {
            const targetSource = source ?? 'merged';
            set(d => {
                d.isLoading = true;
                d.error = null;
                d.warnings = [];
                d.source = targetSource;
            });
            try {
                const resp = await fetch(SOURCE_ENDPOINT_MAP[targetSource]);
                if (!resp.ok) throw new Error(`HTTP ${resp.status}: ${resp.statusText}`);
                const json = await resp.json();

                // API 可能返回 { openapi, info, paths, ... } 或 { data: ..., warnings: [...] }
                const spec: OpenApiSpec = json.openapi ? json : json.data;
                const warnings: string[] = json.warnings ?? [];

                if (!spec || !spec.paths) {
                    throw new Error('Invalid OpenAPI specification: missing paths');
                }

                set(d => {
                    d.openApiSpec = spec as OpenApiSpec;
                    d.warnings = warnings;
                    d.isLoading = false;
                    d.selectedEndpoint = null;
                });
            } catch (e) {
                set(d => {
                    d.error = e instanceof Error ? e.message : String(e);
                    d.isLoading = false;
                    d.openApiSpec = null;
                });
            }
        },

        setSource: (source) => set(d => { d.source = source; }),

        setSelectedEndpoint: (endpoint) => set(d => { d.selectedEndpoint = endpoint; }),

        setSearchQuery: (query) => set(d => { d.searchQuery = query; }),

        reset: () => set(d => {
            d.openApiSpec = null;
            d.source = 'merged';
            d.selectedEndpoint = null;
            d.searchQuery = '';
            d.isLoading = false;
            d.error = null;
            d.warnings = [];
        }),
    })))
);
