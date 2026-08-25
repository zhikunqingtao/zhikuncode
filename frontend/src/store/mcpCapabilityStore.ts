import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';

export interface McpCapabilityDefinition {
  id: string;
  name: string;
  toolName: string;
  url: string;
  apiKeyConfig: string;
  apiKeyDefault?: string;
  domain: string;
  category: string;
  briefDescription: string;
  videoCallSummary?: string;
  description: string;
  input: Record<string, unknown>;
  output: Record<string, unknown>;
  timeoutMs: number;
  enabled: boolean;
  videoCallEnabled: boolean;
}

export interface McpCapabilityStoreState {
  capabilities: McpCapabilityDefinition[];
  domains: string[];
  activeDomain: string | null;
  loading: boolean;
  total: number;
  enabledCount: number;
  testResults: Record<string, { status: string; error?: string }>;

  loadCapabilities: (domain?: string) => Promise<void>;
  loadDomains: () => Promise<void>;
  setActiveDomain: (domain: string | null) => void;
  toggleCapability: (id: string, enabled: boolean) => Promise<{ status: string }>;
  updateCapability: (id: string, data: McpCapabilityDefinition) => Promise<void>;
  addCapability: (data: McpCapabilityDefinition) => Promise<void>;
  deleteCapability: (id: string) => Promise<void>;
  testCapability: (id: string) => Promise<{ status: string; error?: string }>;
}

/**
 * 后端返回的原始能力定义：新契约字段为 url；
 * 旧后端/旧缓存数据可能仍携带 sseUrl
 * （对应后端 McpCapabilityDefinition 的 @JsonProperty("url") + @JsonAlias("sseUrl")）。
 */
type RawMcpCapability = Omit<McpCapabilityDefinition, 'url'> & {
  url?: string;
  sseUrl?: string;
};

/**
 * 契约归一化：将 url ?? sseUrl 统一映射到 url，并剔除旧的 sseUrl 字段。
 * store 内部状态与后续 PUT/POST 请求体因此只依赖 url 契约，
 * 避免旧字段残留导致编辑保存时后端 url 被置空覆盖。
 */
function normalizeCapability(raw: RawMcpCapability): McpCapabilityDefinition {
  const { sseUrl, ...rest } = raw;
  return { ...rest, url: raw.url ?? sseUrl ?? '' };
}

export const useMcpCapabilityStore = create<McpCapabilityStoreState>()(
  subscribeWithSelector(immer((set, get) => ({
    capabilities: [],
    domains: [],
    activeDomain: null,
    loading: false,
    total: 0,
    enabledCount: 0,
    testResults: {},

    loadCapabilities: async (domain?: string) => {
      set(d => { d.loading = true; });
      try {
        const params = new URLSearchParams();
        if (domain) params.set('domain', domain);
        const resp = await fetch(`/api/mcp/capabilities?${params}`);
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const data = await resp.json();
        set(d => {
          d.capabilities = (data.capabilities ?? []).map(normalizeCapability);
          d.total = data.total ?? 0;
          d.enabledCount = data.enabledCount ?? 0;
          d.loading = false;
        });
      } catch (e) {
        console.error('[McpCapabilityStore] loadCapabilities failed:', e);
        set(d => { d.loading = false; });
      }
    },

    loadDomains: async () => {
      try {
        const resp = await fetch('/api/mcp/capabilities/domains');
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const data = await resp.json();
        set(d => { d.domains = data.domains ?? []; });
      } catch (e) {
        console.error('[McpCapabilityStore] loadDomains failed:', e);
      }
    },

    setActiveDomain: (domain) => {
      set(d => { d.activeDomain = domain; });
      get().loadCapabilities(domain ?? undefined);
    },

    toggleCapability: async (id, enabled) => {
      try {
        const resp = await fetch(
          `/api/mcp/capabilities/${id}/toggle?enabled=${enabled}`,
          { method: 'PATCH' }
        );
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const result = await resp.json();
        set(d => {
          const idx = d.capabilities.findIndex(c => c.id === id);
          if (idx >= 0) d.capabilities[idx].enabled = enabled;
          d.enabledCount = d.capabilities.filter(c => c.enabled).length;
        });
        return { status: result.status };
      } catch (e) {
        console.error('[McpCapabilityStore] toggleCapability failed:', e);
        return { status: 'error' };
      }
    },

    updateCapability: async (id, data) => {
      try {
        const resp = await fetch(`/api/mcp/capabilities/${id}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(data),
        });
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const updated = await resp.json();
        set(d => {
          const idx = d.capabilities.findIndex(c => c.id === id);
          if (idx >= 0) d.capabilities[idx] = normalizeCapability(updated);
        });
      } catch (e) {
        console.error('[McpCapabilityStore] updateCapability failed:', e);
      }
    },

    addCapability: async (data) => {
      try {
        const resp = await fetch('/api/mcp/capabilities', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(data),
        });
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const created = await resp.json();
        set(d => { d.capabilities.push(normalizeCapability(created)); d.total++; });
      } catch (e) {
        console.error('[McpCapabilityStore] addCapability failed:', e);
      }
    },

    deleteCapability: async (id) => {
      try {
        const resp = await fetch(`/api/mcp/capabilities/${id}`, { method: 'DELETE' });
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        set(d => {
          d.capabilities = d.capabilities.filter(c => c.id !== id);
          d.total--;
        });
      } catch (e) {
        console.error('[McpCapabilityStore] deleteCapability failed:', e);
      }
    },

    testCapability: async (id) => {
      try {
        const resp = await fetch(`/api/mcp/capabilities/${id}/test`, { method: 'POST' });
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const result = await resp.json();
        set(d => { d.testResults[id] = result; });
        return result;
      } catch (e) {
        const errResult = { status: 'error', error: String(e) };
        set(d => { d.testResults[id] = errResult; });
        return errResult;
      }
    },
  })))
);
