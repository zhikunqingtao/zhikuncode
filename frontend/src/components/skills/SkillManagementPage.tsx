import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { ChevronDown, ChevronRight, Loader2, RefreshCw, Search, ShieldCheck, X } from 'lucide-react';
import { useModalBehavior } from '@/hooks/useModalBehavior';
import { isSkillDetail, useSkillStore, type SkillDetail, type SkillItem } from '@/store/skillStore';
import { SkillIcon } from './SkillIcon';

const SOURCE_LABELS: Record<string, string> = {
  BUNDLED: '内置',
  MANAGED: '管理配置',
  USER: '用户',
  PROJECT: '项目',
  PLUGIN: '插件',
  MCP: 'MCP',
};

const focusRing = 'focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring';

export function SkillManagementPage({ onClose }: { onClose: () => void }) {
  const { skills, loaded, loading, error, stateError, pending, loadSkills, toggleSkill } = useSkillStore();
  const [query, setQuery] = useState('');
  const [detailVersion, setDetailVersion] = useState(0);
  const panel = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const descriptionId = useId();
  useModalBehavior(true, panel, onClose);

  useEffect(() => { void loadSkills(); }, [loadSkills]);

  const visibleSkills = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    if (!keyword) return skills;
    return skills.filter(skill => [skill.name, skill.id, skill.description, skill.source, SOURCE_LABELS[skill.source]]
      .some(value => value?.toLowerCase().includes(keyword)));
  }, [skills, query]);
  const enabledCount = skills.filter(skill => skill.enabled).length;

  const refresh = async () => {
    await loadSkills();
    setDetailVersion(version => version + 1);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-overlay2 backdrop-blur-[3px] md:p-3">
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
        tabIndex={-1}
        className="flex h-[100dvh] w-full flex-col overflow-hidden bg-surfacev2 shadow-e4 outline-none motion-safe:animate-scale-in max-md:pt-[env(safe-area-inset-top)] max-md:pb-[env(safe-area-inset-bottom)] md:h-[88dvh] md:max-w-5xl md:rounded-panel md:border md:border-hairline"
      >
        <header className="flex shrink-0 items-start justify-between gap-2 border-b border-hairline px-4 py-4 md:px-6">
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <SkillIcon className="h-6 w-auto shrink-0 text-accent2-ink" />
              <h2 id={titleId} className="text-xl font-semibold text-t1">Skill 管理</h2>
            </div>
            <p id={descriptionId} className="mt-2 text-sm leading-relaxed text-t2">
              关闭后立即阻止新的技能调用；下一条消息使用更新后的配置，无需重启。
            </p>
            <p className="mt-1 text-[13px] leading-relaxed text-t2">
              当前回答继续，历史中的技能内容仍可能影响模型；新聊天可获得干净上下文。
            </p>
          </div>
          <button type="button" onClick={onClose} aria-label="关闭 Skill 管理" className={`dialog-control flex h-11 w-11 shrink-0 items-center justify-center rounded-[10px] text-t2 hover:bg-hover2 ${focusRing}`}>
            <X className="h-5 w-5" aria-hidden="true" />
          </button>
        </header>

        <div className="shrink-0 border-b border-hairline px-4 py-3 md:px-6 md:py-4">
          <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
            <div className="min-w-0 text-sm text-t2">
              <div className="flex flex-wrap gap-x-4 gap-y-1" aria-live="polite">
                <span>{skills.length} 个 Skill</span>
                <span className="text-ok">{enabledCount} 个已启用</span>
              </div>
              <p className="mt-1 flex items-start gap-1 text-[13px] leading-relaxed">
                <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
                设置保存在服务端，所有设备和会话共享。
              </p>
            </div>
            <div className="flex min-w-0 gap-2 md:w-80 md:shrink-0">
              <label className="relative block min-w-0 flex-1">
                <Search className="pointer-events-none absolute left-3 top-3.5 h-4 w-4 text-t2" aria-hidden="true" />
                <input
                  value={query}
                  onChange={event => setQuery(event.target.value)}
                  aria-label="搜索 Skill"
                  placeholder="搜索名称、描述或来源"
                  className={`min-h-11 w-full rounded-[10px] border border-hairline bg-sunken2 py-2 pl-9 pr-3 text-sm text-t1 ${focusRing}`}
                />
              </label>
              <button type="button" onClick={() => void refresh()} disabled={loading} aria-label="刷新 Skill 列表" className={`dialog-control flex h-11 w-11 shrink-0 items-center justify-center rounded-[10px] border border-hairline text-t2 hover:bg-hover2 disabled:opacity-50 ${focusRing}`}>
                <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} aria-hidden="true" />
              </button>
            </div>
          </div>
          {error && <p role="alert" className="mt-3 break-words rounded-[10px] bg-errsoft px-3 py-2 text-sm text-err">加载或更新失败：{error}</p>}
          {stateError && <p role="alert" className="mt-3 break-words rounded-[10px] bg-errsoft px-3 py-2 text-sm text-err">技能设置暂不可用：{stateError} 所有技能已关闭，暂时无法修改开关。修复设置并重启服务后请刷新。</p>}
        </div>

        <main className="min-h-0 flex-1 overflow-y-auto px-4 py-4 md:px-6">
          {loading && !loaded ? (
            <div role="status" className="flex items-center justify-center gap-2 py-16 text-sm text-t2">
              <Loader2 className="h-5 w-5 animate-spin" aria-hidden="true" />正在读取 Skill 配置…
            </div>
          ) : visibleSkills.length === 0 ? (
            <p className="py-16 text-center text-sm text-t2">{query.trim() ? '没有匹配的 Skill' : error ? '暂时无法读取 Skill，请重试。' : '暂无可管理的 Skill'}</p>
          ) : (
            <div className="grid items-start gap-3 lg:grid-cols-2">
              {visibleSkills.map(skill => (
                <SkillCard key={skill.id} skill={skill} busy={pending[skill.id] === true} blocked={stateError !== null} detailVersion={detailVersion} onToggle={() => void toggleSkill(skill.id, !skill.enabled)} />
              ))}
            </div>
          )}
        </main>
      </div>
    </div>
  );
}

function SkillCard({ skill, busy, blocked, detailVersion, onToggle }: {
  skill: SkillItem;
  busy: boolean;
  blocked: boolean;
  detailVersion: number;
  onToggle: () => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const [detail, setDetail] = useState<SkillDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  const detailId = useId();

  useEffect(() => {
    if (!expanded) return;
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    void fetch(`/api/skills/manage/${encodeURIComponent(skill.id)}`, { signal: controller.signal })
      .then(async response => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const detail: unknown = await response.json();
        if (!isSkillDetail(detail) || detail.id !== skill.id) throw new Error('技能详情格式无效');
        return detail;
      })
      .then(value => { if (!controller.signal.aborted) setDetail(value); })
      .catch(failure => {
        if (!controller.signal.aborted) setError(failure instanceof Error ? failure.message : String(failure));
      })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [expanded, skill.id, detailVersion, retry]);

  return (
    <article className={`min-w-0 rounded-[14px] border p-3 transition-colors md:p-4 ${skill.enabled ? 'border-accent2-ring bg-accent2-soft' : 'border-hairline bg-sunken2'}`}>
      <div className="flex items-start gap-2">
        <button type="button" onClick={() => setExpanded(value => !value)} aria-label={`${expanded ? '收起' : '展开'} ${skill.name} 详情`} aria-expanded={expanded} aria-controls={detailId} className={`dialog-control flex h-11 w-11 shrink-0 items-center justify-center rounded-[10px] text-t2 hover:bg-hover2 ${focusRing}`}>
          {expanded ? <ChevronDown className="h-4 w-4" aria-hidden="true" /> : <ChevronRight className="h-4 w-4" aria-hidden="true" />}
        </button>
        <div className="min-w-0 flex-1 pt-2">
          <h3 className="break-words text-base font-semibold text-t1 [overflow-wrap:anywhere]">{skill.name}</h3>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-[13px]">
            <span className="rounded bg-hover2 px-2 py-0.5 text-t2">{SOURCE_LABELS[skill.source] ?? skill.source}</span>
            <span className={`rounded px-2 py-0.5 ${skill.enabled ? 'bg-oksoft text-ok' : 'bg-hover2 text-t2'}`}>{skill.enabled ? '已启用' : '已关闭'}</span>
          </div>
          <p className="mt-2 line-clamp-3 break-words text-[13px] leading-relaxed text-t2 [overflow-wrap:anywhere]">{skill.description}</p>
        </div>
        <button type="button" role="switch" aria-checked={skill.enabled} aria-label={`${skill.enabled ? '关闭' : '启用'} ${skill.name}`} disabled={busy || blocked} onClick={onToggle} className={`panel-control flex h-11 w-11 shrink-0 items-center rounded-[10px] disabled:opacity-50 ${busy ? 'cursor-wait' : blocked ? 'cursor-not-allowed' : ''} ${focusRing}`}>
          <span aria-hidden="true" className={`inline-flex h-6 w-11 items-center rounded-full transition-colors ${skill.enabled ? 'bg-accent2-strong' : 'bg-t3'}`}>
            <span className={`inline-block h-4 w-4 rounded-full transition-transform ${skill.enabled ? 'translate-x-6 bg-white' : 'translate-x-1 bg-surfacev2'}`} />
          </span>
        </button>
      </div>
      {expanded && (
        <div id={detailId} className="mt-3 min-w-0 border-t border-hairline pt-3 text-[13px] text-t2">
          {loading ? <p role="status" className="flex items-center gap-2"><Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />正在读取详情…</p> : error ? (
            <div>
              <p role="alert" className="break-words text-err">读取详情失败：{error}</p>
              <button type="button" onClick={() => setRetry(value => value + 1)} className={`mt-2 min-h-11 rounded-[10px] border border-hairline px-3 text-t1 hover:bg-hover2 ${focusRing}`}>重试读取详情</button>
            </div>
          ) : detail && (
            <div className="space-y-3">
              <p className="break-words leading-relaxed [overflow-wrap:anywhere]">{skill.description}</p>
              <p className="break-all"><span className="font-medium">文件：</span>{detail.filePath || '内置 Skill'}</p>
              <pre className="max-h-80 overflow-y-auto whitespace-pre-wrap break-words rounded-[10px] bg-surfacev2 p-3 font-mono leading-relaxed [overflow-wrap:anywhere]">{detail.content || '暂无内容'}</pre>
            </div>
          )}
        </div>
      )}
    </article>
  );
}
