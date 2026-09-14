import { useEffect, useState, useCallback } from 'react';
import { useMcpCapabilityStore, type McpCapabilityDefinition } from '@/store/mcpCapabilityStore';

const DOMAIN_LABELS: Record<string, string> = {
  image_processing: '图像处理',
  web_search: '网络搜索',
  map_navigation: '地图导航',
  document_processing: '文档处理',
  code_analysis: '代码分析',
  data_analysis: '数据分析',
  communication: '通信协作',
  media_processing: '媒体处理',
  knowledge_base: '知识库',
};

export function McpCapabilityPanel() {
  const {
    capabilities, domains, activeDomain, loading,
    total, enabledCount, testResults,
    loadCapabilities, loadDomains, setActiveDomain,
    toggleCapability, testCapability,
  } = useMcpCapabilityStore();

  const [editingId, setEditingId] = useState<string | null>(null);

  useEffect(() => {
    loadCapabilities();
    loadDomains();
  }, [loadCapabilities, loadDomains]);

  const handleToggle = useCallback(async (id: string, currentEnabled: boolean) => {
    await toggleCapability(id, !currentEnabled);
  }, [toggleCapability]);

  const handleTest = useCallback(async (id: string) => {
    await testCapability(id);
  }, [testCapability]);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-lg font-semibold">MCP 工具管理</h3>
          <p className="text-sm text-t3 mt-1">共 {total} 个工具，已启用 {enabledCount} 个</p>
        </div>
      </div>

      <div className="flex flex-wrap gap-2">
        <button
          className={`px-3 py-1 text-xs rounded-full border transition-interactive duration-fast
            focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring active:scale-[.98]
            ${activeDomain === null
              ? 'bg-accent2-strong text-white border-accent2-strong'
              : 'border-hairline text-t2 hover:bg-hover2'}`}
          onClick={() => setActiveDomain(null)}
        >全部</button>
        {domains.map((d) => (
          <button key={d}
            className={`px-3 py-1 text-xs rounded-full border transition-interactive duration-fast
              focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring active:scale-[.98]
              ${activeDomain === d
                ? 'bg-accent2-strong text-white border-accent2-strong'
                : 'border-hairline text-t2 hover:bg-hover2'}`}
            onClick={() => setActiveDomain(d)}
          >{DOMAIN_LABELS[d] ?? d}</button>
        ))}
      </div>

      {loading ? (
        <div className="text-center text-t3 py-8">加载中...</div>
      ) : (
        <div className="space-y-3 max-h-[60vh] overflow-y-auto">
          {capabilities.map((cap) => (
            <CapabilityCard key={cap.id} capability={cap} testResult={testResults[cap.id]}
              onToggle={handleToggle} onTest={handleTest} onEdit={() => setEditingId(cap.id)} />
          ))}
          {capabilities.length === 0 && (
            <div className="text-center text-t3 py-8">当前分类下无工具</div>
          )}
        </div>
      )}

      {editingId && <EditDialog capabilityId={editingId} onClose={() => setEditingId(null)} />}
    </div>
  );
}

function CapabilityCard({
  capability: cap, testResult, onToggle, onTest, onEdit,
}: {
  capability: McpCapabilityDefinition;
  testResult?: { status: string; error?: string };
  onToggle: (id: string, enabled: boolean) => void;
  onTest: (id: string) => void;
  onEdit: () => void;
}) {
  return (
    <div className={`p-4 rounded-2xl border transition-surface duration-fast
      ${cap.enabled
        ? 'border-accent2 bg-accent2-soft'
        : 'border-hairline'}`}>
      <div className="flex items-start justify-between">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="font-medium text-sm">{cap.name}</span>
            <span className="text-xs px-2 py-0.5 rounded bg-surface2 text-t3">
              {DOMAIN_LABELS[cap.domain] ?? cap.domain}
            </span>
          </div>
          <p className="text-xs text-t3 mt-1 line-clamp-2">{cap.briefDescription}</p>
          <div className="flex items-center gap-3 mt-2 text-xs text-t3">
            <span>超时: {(cap.timeoutMs / 1000).toFixed(0)}s</span>
            <span>SSE</span>
            <span className="font-mono">{cap.toolName}</span>
          </div>
        </div>
        <button onClick={() => onToggle(cap.id, cap.enabled)}
          className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ml-4 flex-shrink-0
            focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring
            ${cap.enabled ? 'bg-accent2-strong' : 'bg-sunken2 shadow-well'}`}>
          <span className={`inline-block h-4 w-4 transform rounded-full bg-white shadow-soft-sm transition-transform
            ${cap.enabled ? 'translate-x-6' : 'translate-x-1'}`} />
        </button>
      </div>
      <div className="flex items-center gap-2 mt-3">
        <button onClick={onEdit}
          className="text-xs px-2 py-1 rounded-xl border border-hairline
                     hover:bg-hover2 transition-interactive duration-fast
                     focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring
                     active:scale-[.98]">编辑</button>
        <button onClick={() => onTest(cap.id)}
          className="text-xs px-2 py-1 rounded-xl border border-hairline
                     hover:bg-hover2 transition-interactive duration-fast
                     focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring
                     active:scale-[.98]">测试</button>
        {testResult && (
          <span className={`text-xs ${testResult.status === 'reachable' ? 'text-ok' : 'text-err'}`}>
            {testResult.status === 'reachable' ? '可达' : testResult.status}
          </span>
        )}
      </div>
    </div>
  );
}

function EditDialog({ capabilityId, onClose }: { capabilityId: string; onClose: () => void }) {
  const { capabilities, updateCapability } = useMcpCapabilityStore();
  const cap = capabilities.find(c => c.id === capabilityId);
  const [formData, setFormData] = useState<McpCapabilityDefinition | null>(cap ?? null);
  if (!formData) return null;

  const handleSave = async () => { await updateCapability(capabilityId, formData); onClose(); };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-overlay2 backdrop-blur-[3px]" onClick={onClose}>
      <div className="bg-surfacev2 border border-hairline rounded-panel shadow-e4 motion-safe:animate-scale-in w-full max-w-lg max-h-[80vh] overflow-y-auto p-6"
        onClick={(e) => e.stopPropagation()}>
        <h3 className="text-lg font-semibold mb-4">编辑 MCP 工具</h3>
        <div className="space-y-3">
          <Field label="名称" value={formData.name}
            onChange={(v) => setFormData({ ...formData, name: v })} />
          <Field label="描述" value={formData.description} multiline
            onChange={(v) => setFormData({ ...formData, description: v })} />
          <Field label="简要描述" value={formData.briefDescription}
            onChange={(v) => setFormData({ ...formData, briefDescription: v })} />
          <Field label="Server URL" value={formData.url}
            onChange={(v) => setFormData({ ...formData, url: v })} />
          <Field label="超时 (ms)" value={String(formData.timeoutMs)}
            onChange={(v) => setFormData({ ...formData, timeoutMs: parseInt(v) || 30000 })} />
          <Field label="API Key Config" value={formData.apiKeyConfig}
            onChange={(v) => setFormData({ ...formData, apiKeyConfig: v })} />
        </div>
        <div className="flex justify-end gap-2 mt-6">
          <button onClick={onClose}
            className="px-4 py-2 text-sm rounded-xl border border-hairline text-t2
                       hover:bg-hover2 transition-interactive duration-fast
                       focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring
                       active:scale-[.98]">取消</button>
          <button onClick={handleSave}
            className="px-4 py-2 text-sm rounded-xl bg-accent2-strong text-white
                       hover:bg-accent2-hover transition-interactive duration-fast
                       focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring
                       active:scale-[.98]">保存</button>
        </div>
      </div>
    </div>
  );
}

function Field({ label, value, onChange, multiline }: {
  label: string; value: string; onChange: (v: string) => void; multiline?: boolean;
}) {
  return (
    <div>
      <label className="block text-xs font-medium text-t3 mb-1">{label}</label>
      {multiline ? (
        <textarea value={value} onChange={(e) => onChange(e.target.value)}
          className="w-full p-2 text-sm border border-hairline rounded-xl bg-sunken2 shadow-well text-t1 h-20
                     transition-surface duration-fast
                     focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring" />
      ) : (
        <input type="text" value={value} onChange={(e) => onChange(e.target.value)}
          className="w-full p-2 text-sm border border-hairline rounded-xl bg-sunken2 shadow-well text-t1
                     transition-surface duration-fast
                     focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring" />
      )}
    </div>
  );
}
