import { create } from 'zustand';

export interface McpServiceTool {
  id: string;
  name: string;
  toolName: string;
  description: string;
  allowlisted: boolean;
}

export interface McpService {
  serverKey: string;
  displayName: string;
  description: string;
  domain: string;
  source: 'registry' | 'configured';
  transportType: string;
  enabled: boolean;
  status: string;
  readiness: string;
  toolCount: number;
  tools: McpServiceTool[];
}

interface McpServiceStore {
  services: McpService[];
  loading: boolean;
  error: string | null;
  total: number;
  enabledCount: number;
  pending: Record<string, boolean>;
  loadServices: () => Promise<void>;
  toggleService: (serverKey: string, enabled: boolean) => Promise<void>;
}

export const useMcpServiceStore = create<McpServiceStore>((set) => ({
  services: [],
  loading: false,
  error: null,
  total: 0,
  enabledCount: 0,
  pending: {},

  loadServices: async () => {
    set({ loading: true, error: null });
    try {
      const response = await fetch('/api/mcp/services');
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = await response.json();
      set({
        services: data.services ?? [],
        total: data.total ?? 0,
        enabledCount: data.enabledCount ?? 0,
        loading: false,
      });
    } catch (error) {
      set({ loading: false, error: error instanceof Error ? error.message : String(error) });
    }
  },

  toggleService: async (serverKey, enabled) => {
    set(state => ({
      pending: { ...state.pending, [serverKey]: true },
      error: null,
    }));
    try {
      const response = await fetch(
        `/api/mcp/services/${encodeURIComponent(serverKey)}/toggle?enabled=${enabled}`,
        { method: 'PATCH' },
      );
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const updated = await response.json() as McpService;
      set(state => {
        const services = state.services.map(service =>
          service.serverKey === serverKey ? updated : service,
        );
        return {
          services,
          enabledCount: services.filter(service => service.enabled).length,
          pending: { ...state.pending, [serverKey]: false },
        };
      });
    } catch (error) {
      set(state => ({
        error: error instanceof Error ? error.message : String(error),
        pending: { ...state.pending, [serverKey]: false },
      }));
    }
  },
}));
