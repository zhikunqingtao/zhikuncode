import { useEffect, useMemo, useState } from 'react';
import { ChevronDown, ChevronRight, RefreshCw, Search, ShieldCheck, X } from 'lucide-react';
import { useMcpServiceStore, type McpService } from '@/store/mcpServiceStore';
import { McpIcon } from './McpIcon';

interface McpManagementPageProps {
  onClose: () => void;
}

const DOMAIN_LABELS: Record<string, string> = {
  configured: '已配置',
  web_search: '网络搜索',
  news: '新闻',
  legal_research: '法律',
  company_intelligence: '企业信息',
  content_safety: '内容安全',
  map_navigation: '地图',
  image_processing: '图像',
  video_generation: '视频',
  financial_research: '金融',
  academic_research: '学术',
  product_sourcing: '采购',
  logistics: '物流',
  commodity_market: '行情',
  retail_insights: '零售',
  tourism_insights: '旅游',
};

const STATUS_LABELS: Record<string, string> = {
  connected: '已连接',
  connecting: '连接中',
  disabled: '未启用',
  not_connected: '待连接',
  needs_auth: '缺少密钥',
  missing_endpoint: '缺少地址',
  failed: '连接失败',
};

export function McpManagementPage({ onClose }: McpManagementPageProps) {
  const { services, loading, error, total, enabledCount, pending, loadServices, toggleService } =
    useMcpServiceStore();
  const [query, setQuery] = useState('');
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});

  useEffect(() => { void loadServices(); }, [loadServices]);

  const visibleServices = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    if (!keyword) return services;
    return services.filter(service =>
      [service.displayName, service.serverKey, service.description, service.domain,
        ...service.tools.flatMap(tool => [tool.name, tool.toolName])]
        .some(value => value?.toLowerCase().includes(keyword)),
    );
  }, [query, services]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-3 backdrop-blur-sm">
      <div className="flex h-[88vh] w-full max-w-5xl flex-col overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--bg-primary)] shadow-2xl">
        <header className="flex items-start justify-between border-b border-[var(--border)] px-6 py-5">
          <div>
            <div className="flex items-center gap-2">
              <McpIcon className="h-5 w-8 text-blue-500" />
              <h2 className="text-lg font-semibold text-[var(--text-primary)]">MCP 管理</h2>
            </div>
            <p className="mt-1 text-sm text-[var(--text-muted)]">
              低频或高风险 MCP 默认关闭；你可以随时决定哪些服务向模型开放工具。
            </p>
          </div>
          <button onClick={onClose} className="rounded-lg p-2 text-[var(--text-muted)] hover:bg-[var(--bg-hover)]" aria-label="关闭 MCP 管理">
            <X className="h-5 w-5" />
          </button>
        </header>

        <div className="border-b border-[var(--border)] px-6 py-4">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div className="flex items-center gap-4 text-sm text-[var(--text-secondary)]">
              <span>{total} 个服务</span>
              <span className="text-emerald-500">{enabledCount} 个已启用</span>
              <span className="flex items-center gap-1 text-xs text-[var(--text-muted)]">
                <ShieldCheck className="h-4 w-4" /> 状态保存在本机
              </span>
            </div>
            <div className="flex gap-2">
              <label className="relative block min-w-0 flex-1 sm:w-72">
                <Search className="absolute left-3 top-2.5 h-4 w-4 text-[var(--text-muted)]" />
                <input
                  value={query}
                  onChange={event => setQuery(event.target.value)}
                  placeholder="搜索服务或工具"
                  className="w-full rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] py-2 pl-9 pr-3 text-sm text-[var(--text-primary)] outline-none focus:ring-2 focus:ring-blue-500"
                />
              </label>
              <button onClick={() => void loadServices()} disabled={loading} className="rounded-lg border border-[var(--border)] p-2 text-[var(--text-secondary)] hover:bg-[var(--bg-hover)] disabled:opacity-50" aria-label="刷新 MCP 服务">
                <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
              </button>
            </div>
          </div>
          {error && <p className="mt-3 rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-500">加载或更新失败：{error}</p>}
        </div>

        <main className="flex-1 overflow-y-auto px-6 py-4">
          {loading && services.length === 0 ? (
            <div className="py-16 text-center text-sm text-[var(--text-muted)]">正在读取 MCP 配置…</div>
          ) : visibleServices.length === 0 ? (
            <div className="py-16 text-center text-sm text-[var(--text-muted)]">没有匹配的 MCP 服务</div>
          ) : (
            <div className="grid gap-3 lg:grid-cols-2">
              {visibleServices.map(service => (
                <ServiceCard
                  key={service.serverKey}
                  service={service}
                  busy={Boolean(pending[service.serverKey])}
                  expanded={Boolean(expanded[service.serverKey])}
                  onExpand={() => setExpanded(current => ({ ...current, [service.serverKey]: !current[service.serverKey] }))}
                  onToggle={() => void toggleService(service.serverKey, !service.enabled)}
                />
              ))}
            </div>
          )}
        </main>
      </div>
    </div>
  );
}

function ServiceCard({ service, busy, expanded, onExpand, onToggle }: {
  service: McpService;
  busy: boolean;
  expanded: boolean;
  onExpand: () => void;
  onToggle: () => void;
}) {
  const statusTone = service.status === 'connected'
    ? 'bg-emerald-500/10 text-emerald-500'
    : ['failed', 'needs_auth', 'missing_endpoint'].includes(service.status)
      ? 'bg-amber-500/10 text-amber-500'
      : 'bg-[var(--bg-hover)] text-[var(--text-muted)]';

  return (
    <article className={`rounded-xl border p-4 transition-colors ${service.enabled ? 'border-blue-500/40 bg-blue-500/5' : 'border-[var(--border)] bg-[var(--bg-secondary)]'}`}>
      <div className="flex items-start gap-3">
        <button onClick={onExpand} disabled={service.tools.length === 0} className="mt-0.5 rounded p-1 text-[var(--text-muted)] hover:bg-[var(--bg-hover)] disabled:opacity-30" aria-label={`${expanded ? '收起' : '展开'} ${service.displayName} 工具`}>
          {expanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
        </button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="font-medium text-[var(--text-primary)]">{service.displayName}</h3>
            <span className="rounded bg-[var(--bg-hover)] px-2 py-0.5 text-[11px] text-[var(--text-muted)]">{DOMAIN_LABELS[service.domain] ?? service.domain}</span>
            <span className={`rounded px-2 py-0.5 text-[11px] ${statusTone}`}>{STATUS_LABELS[service.status] ?? service.status}</span>
          </div>
          <p className="mt-1 line-clamp-2 text-xs text-[var(--text-muted)]">{service.description}</p>
          <p className="mt-2 text-[11px] text-[var(--text-muted)]">
            {service.source === 'registry' ? `${service.toolCount} 个白名单工具` : service.toolCount > 0 ? `${service.toolCount} 个已发现工具` : '工具将在连接后发现'}
            {' · '}{service.transportType}
          </p>
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={service.enabled}
          aria-label={`${service.enabled ? '停用' : '启用'} ${service.displayName}`}
          disabled={busy}
          onClick={onToggle}
          className={`relative mt-1 inline-flex h-6 w-11 shrink-0 items-center rounded-full transition-colors disabled:cursor-wait disabled:opacity-50 ${service.enabled ? 'bg-blue-500' : 'bg-gray-400/60'}`}
        >
          <span className={`inline-block h-4 w-4 rounded-full bg-white transition-transform ${service.enabled ? 'translate-x-6' : 'translate-x-1'}`} />
        </button>
      </div>

      {expanded && service.tools.length > 0 && (
        <div className="mt-3 border-t border-[var(--border)] pt-3">
          <ul className="space-y-2">
            {service.tools.map(tool => (
              <li key={tool.id} className="flex items-start justify-between gap-3 text-xs">
                <div className="min-w-0">
                  <span className="text-[var(--text-secondary)]">{tool.name}</span>
                  <span className="ml-2 font-mono text-[10px] text-[var(--text-muted)]">{tool.toolName}</span>
                </div>
                {!tool.allowlisted && <span className="shrink-0 text-amber-500">未授权</span>}
              </li>
            ))}
          </ul>
        </div>
      )}
    </article>
  );
}
