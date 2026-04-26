import { useState, useEffect } from 'react';

interface PluginInfo {
  name: string;
  version: string;
  description: string;
  enabled: boolean;
  isBuiltin: boolean;
  sourceType: 'LOCAL' | 'MARKETPLACE' | 'BUILTIN';
  commandCount: number;
  toolCount: number;
  hookCount: number;
}

export default function PluginManager() {
  const [plugins, setPlugins] = useState<PluginInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloading, setReloading] = useState(false);

  const fetchPlugins = async () => {
    try {
      setLoading(true);
      setError(null);
      const res = await fetch('/api/plugins');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      setPlugins(data.plugins || []);
    } catch (e: unknown) {
      const message = e instanceof Error ? e.message : 'Failed to load plugins';
      setError(message);
    } finally {
      setLoading(false);
    }
  };

  const reloadPlugins = async () => {
    try {
      setReloading(true);
      setError(null);
      const res = await fetch('/api/plugins/reload', { method: 'POST' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      await fetchPlugins();
    } catch (e: unknown) {
      const message = e instanceof Error ? e.message : 'Failed to reload plugins';
      setError(message);
    } finally {
      setReloading(false);
    }
  };

  useEffect(() => { fetchPlugins(); }, []);

  if (loading) return <div className="p-4 text-muted">Loading plugins...</div>;

  return (
    <div className="p-4">
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-lg font-semibold">Plugin Manager</h2>
        <button
          onClick={reloadPlugins}
          disabled={reloading}
          className="px-3 py-1 text-sm bg-primary text-white rounded hover:bg-primary-hover disabled:opacity-50"
        >
          {reloading ? 'Reloading...' : 'Reload Plugins'}
        </button>
      </div>

      {error && (
        <div className="mb-4 p-3 bg-danger/10 text-danger rounded border border-danger/20">
          {error}
        </div>
      )}

      {plugins.length === 0 ? (
        <div className="text-muted text-center py-8">No plugins loaded</div>
      ) : (
        <div className="space-y-3">
          {plugins.map(plugin => (
            <div key={plugin.name} className="border border-border rounded-lg p-4 hover:shadow-sm transition-shadow">
              <div className="flex items-center justify-between">
                <div>
                  <span className="font-medium">{plugin.name}</span>
                  <span className="ml-2 text-xs text-muted">v{plugin.version}</span>
                  {plugin.isBuiltin && (
                    <span className="ml-2 px-1.5 py-0.5 text-xs bg-surface-sunken text-muted rounded">
                      builtin
                    </span>
                  )}
                </div>
                <span className={`text-xs px-2 py-0.5 rounded ${
                  plugin.enabled ? 'bg-success/10 text-success' : 'bg-surface-sunken text-muted'
                }`}>
                  {plugin.enabled ? 'Enabled' : 'Disabled'}
                </span>
              </div>
              {plugin.description && (
                <p className="text-sm text-muted mt-1">{plugin.description}</p>
              )}
              <div className="flex gap-4 mt-2 text-xs text-muted">
                <span>{plugin.commandCount} commands</span>
                <span>{plugin.toolCount} tools</span>
                <span>{plugin.hookCount} hooks</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
