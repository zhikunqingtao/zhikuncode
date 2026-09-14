import { useEffect, useMemo } from 'react';
import DOMPurify from 'dompurify';
import { useMcpStore } from '@/store/mcpStore';
import { PromptArgsForm } from './PromptArgsForm';
import type { McpPrompt } from '@/types';

/**
 * PromptsTab — MCP 提示词发现与执行面板。
 * 按 MCP 服务器分组显示提示词列表，支持选择、参数填写和执行。
 * 对 MCP 服务器返回的内容使用 DOMPurify 进行 XSS 清洗。
 */
export function PromptsTab() {
  const {
    prompts, selectedPrompt, promptResult,
    loadingPrompts, executingPrompt,
    fetchPrompts, executePrompt, selectPrompt, clearPromptResult,
  } = useMcpStore();

  useEffect(() => {
    fetchPrompts();
  }, [fetchPrompts]);

  // 按服务器分组
  const grouped = useMemo(() => {
    const map = new Map<string, McpPrompt[]>();
    for (const p of prompts) {
      const list = map.get(p.serverName) || [];
      list.push(p);
      map.set(p.serverName, list);
    }
    return map;
  }, [prompts]);

  const handleExecute = (args: Record<string, string>) => {
    if (selectedPrompt) {
      executePrompt(selectedPrompt.name, selectedPrompt.serverName, args);
    }
  };

  /** 清洗 HTML/Markdown 内容 — XSS 防护 */
  const sanitize = (content: string): string => {
    return DOMPurify.sanitize(content, {
      ALLOWED_TAGS: ['b', 'i', 'em', 'strong', 'a', 'p', 'br', 'code', 'pre', 'ul', 'ol', 'li', 'span'],
      ALLOWED_ATTR: ['href', 'target', 'rel', 'class'],
    });
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-lg font-semibold">MCP Prompts</h3>
          <p className="text-sm text-t3 mt-1">
            {prompts.length} prompt{prompts.length !== 1 ? 's' : ''} available from {grouped.size} server{grouped.size !== 1 ? 's' : ''}
          </p>
        </div>
        <button
          onClick={() => fetchPrompts()}
          disabled={loadingPrompts}
          className="px-3 py-1.5 text-xs rounded-xl border border-hairline
                     hover:bg-hover2 transition-interactive duration-fast disabled:opacity-50
                     focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring
                     active:scale-[.98]"
        >
          {loadingPrompts ? 'Loading...' : 'Refresh'}
        </button>
      </div>

      {loadingPrompts && prompts.length === 0 ? (
        <div className="text-center text-t3 py-8">Loading prompts...</div>
      ) : prompts.length === 0 ? (
        <div className="text-center text-t3 py-8">
          No prompts discovered. Ensure MCP servers are connected and expose prompts.
        </div>
      ) : (
        <div className="flex gap-4">
          {/* Left: Prompt List */}
          <div className="w-1/2 space-y-4 max-h-[60vh] overflow-y-auto pr-2">
            {Array.from(grouped.entries()).map(([serverName, serverPrompts]) => (
              <div key={serverName}>
                <div className="text-xs font-semibold text-t3 uppercase tracking-wider mb-2">
                  {serverName}
                </div>
                <div className="space-y-2">
                  {serverPrompts.map((prompt) => (
                    <button
                      key={`${prompt.serverName}-${prompt.name}`}
                      onClick={() => selectPrompt(prompt)}
                      className={`w-full text-left p-3 rounded-xl border transition-interactive duration-fast
                        focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring
                        ${selectedPrompt?.name === prompt.name && selectedPrompt?.serverName === prompt.serverName
                          ? 'border-accent2 bg-accent2-soft'
                          : 'border-hairline hover:bg-hover2'
                        }`}
                    >
                      <div className="font-medium text-sm">{prompt.name}</div>
                      {prompt.description && (
                        <p className="text-xs text-t3 mt-1 line-clamp-2">
                          {prompt.description}
                        </p>
                      )}
                      <div className="flex items-center gap-2 mt-1.5">
                        {prompt.arguments.length > 0 && (
                          <span className="text-xs text-t3">
                            {prompt.arguments.length} arg{prompt.arguments.length > 1 ? 's' : ''}
                          </span>
                        )}
                        {prompt.arguments.some(a => a.required) && (
                          <span className="text-xs text-warn">
                            {prompt.arguments.filter(a => a.required).length} required
                          </span>
                        )}
                      </div>
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </div>

          {/* Right: Detail & Execute */}
          <div className="w-1/2 max-h-[60vh] overflow-y-auto pl-2">
            {selectedPrompt ? (
              <div className="space-y-4">
                <div>
                  <h4 className="font-semibold text-sm">{selectedPrompt.name}</h4>
                  <p className="text-xs text-t3 mt-1">
                    Server: {selectedPrompt.serverName}
                  </p>
                  {selectedPrompt.description && (
                    <p className="text-sm text-t2 mt-2">
                      {selectedPrompt.description}
                    </p>
                  )}
                </div>

                <div className="border-t border-hairline pt-3">
                  <h5 className="text-xs font-semibold text-t3 uppercase mb-2">Arguments</h5>
                  <PromptArgsForm
                    arguments={selectedPrompt.arguments}
                    onSubmit={handleExecute}
                    executing={executingPrompt}
                  />
                </div>

                {/* Result Display */}
                {promptResult && (
                  <div className="border-t border-hairline pt-3">
                    <h5 className="text-xs font-semibold text-t3 uppercase mb-2">Result</h5>
                    {promptResult.success ? (
                      <div className="space-y-2">
                        {promptResult.messages?.map((msg, idx) => (
                          <div key={idx} className="p-2 rounded-xl bg-surface2 border border-hairline">
                            <span className="text-xs font-mono text-t3 block mb-1">{msg.role}</span>
                            <div
                              className="text-sm text-t2 whitespace-pre-wrap break-words"
                              dangerouslySetInnerHTML={{ __html: sanitize(msg.content) }}
                            />
                          </div>
                        ))}
                        {(!promptResult.messages || promptResult.messages.length === 0) && (
                          <p className="text-sm text-t3 italic">No messages returned.</p>
                        )}
                      </div>
                    ) : (
                      <div className="p-3 rounded-xl bg-errsoft border border-err">
                        <p className="text-sm text-errstrong dark:text-err font-medium">
                          {promptResult.error || 'Execution failed'}
                        </p>
                        {promptResult.details && promptResult.details.length > 0 && (
                          <ul className="mt-2 text-xs text-err list-disc list-inside">
                            {promptResult.details.map((d, i) => <li key={i}>{d}</li>)}
                          </ul>
                        )}
                      </div>
                    )}
                    <button
                      onClick={clearPromptResult}
                      className="mt-2 text-xs text-t3 hover:text-t1 underline transition-interactive duration-fast
                                 focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring"
                    >
                      Clear result
                    </button>
                  </div>
                )}
              </div>
            ) : (
              <div className="flex items-center justify-center h-full text-t3 text-sm">
                Select a prompt to view details and execute
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
