import { useEffect, useRef, useState } from 'react';
import { X, Zap, Play } from 'lucide-react';
import { findEnabledSkill, isSkillDetail, useSkillStore, type SkillDetail } from '@/store/skillStore';
import { useModalBehavior } from '@/hooks/useModalBehavior';

export const SkillDetailModal: React.FC<{
  skillName: string;
  onClose: () => void;
  onExecute: (name: string, userInput: string) => void | Promise<void>;
}> = ({ skillName, onClose, onExecute }) => {
  const [detail, setDetail] = useState<SkillDetail | null>(null);
  const [userInput, setUserInput] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const panel = useRef<HTMLDivElement>(null);
  const enabled = useSkillStore(state => !state.loaded || !!findEnabledSkill(state.skills, skillName));
  useModalBehavior(true, panel, onClose);

  useEffect(() => {
    const controller = new AbortController();
    setError(null);
    setDetail(null);
    if (enabled) {
      void fetch(`/api/skills/detail/${encodeURIComponent(skillName)}`, { signal: controller.signal, cache: 'no-store' })
        .then(async response => {
          if (!response.ok) throw new Error(response.status === 404
            ? '该技能不可用，请在 Skill 管理中检查启用状态。'
            : `加载技能详情失败（HTTP ${response.status}）`);
          const data: unknown = await response.json();
          const expected = findEnabledSkill(useSkillStore.getState().skills, skillName);
          if (!isSkillDetail(data) || (expected && data.id !== expected.id)) {
            throw new Error('技能详情格式无效，请重试');
          }
          if (!controller.signal.aborted) setDetail(data);
        })
        .catch(error => {
          if (!controller.signal.aborted) setError(error instanceof Error ? error.message : '无法连接服务端，请重试');
        });
    }
    return () => controller.abort();
  }, [skillName, enabled]);

  const execute = async () => {
    setSubmitting(true);
    try {
      await useSkillStore.getState().loadSkills({ background: true });
      const skill = findEnabledSkill(useSkillStore.getState().skills, skillName);
      if (!skill) {
        setError('该技能不可用，请在 Skill 管理中检查启用状态。');
        return;
      }
      await onExecute(skill.id, userInput.trim());
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-overlay2 backdrop-blur-[3px]" onClick={onClose}>
      <div ref={panel} role="dialog" aria-modal="true" aria-label={`技能详情：${skillName}`} tabIndex={-1}
        className="bg-surfacev2 border border-hairline shadow-e4 motion-safe:animate-scale-in w-full h-[100dvh]
          md:h-auto md:max-w-xl md:mx-4 md:max-h-[88dvh] md:rounded-panel overflow-hidden flex flex-col outline-none"
        onClick={event => event.stopPropagation()}>
        <div className="p-4 border-b border-hairline flex items-center justify-between gap-2">
          <div className="flex items-center gap-2 min-w-0 flex-wrap">
            <Zap size={18} className="text-warn shrink-0" aria-hidden="true" />
            <h2 className="text-t1 text-base font-semibold break-all">{detail?.name ?? skillName}</h2>
            {detail && <span className="text-[13px] px-1.5 py-0.5 rounded bg-sunken2 text-t2">{detail.source}</span>}
          </div>
          <button type="button" onClick={onClose} aria-label="关闭技能详情"
            className="dialog-control min-h-11 min-w-11 shrink-0 flex items-center justify-center hover:bg-hover2 rounded-[10px]">
            <X size={18} aria-hidden="true" />
          </button>
        </div>
        <div className="flex-1 min-h-0 overflow-y-auto p-4 space-y-3">
          {!enabled && <p role="status" className="text-sm text-t2">该技能已关闭，请在 Skill 管理中重新开启。</p>}
          {error && <p role="alert" className="text-err text-sm">{error}</p>}
          {enabled && !detail && !error && <p role="status" className="text-sm text-t2">正在加载技能详情…</p>}
          {detail && <>
            <p className="text-sm text-t2 break-words">{detail.description}</p>
            <div className="text-[13px] text-t2 break-all">文件：{detail.filePath || '内置技能'}</div>
            <pre className="text-[13px] font-mono bg-sunken2 p-3 rounded-[10px] whitespace-pre-wrap break-words [overflow-wrap:anywhere] text-t2">
              {detail.content}
            </pre>
          </>}
        </div>
        <div className="px-4 pb-3">
          <label htmlFor="skill-user-input" className="text-[13px] text-t2 mb-1 block">补充说明（可选）</label>
          <textarea id="skill-user-input" value={userInput} onChange={event => setUserInput(event.target.value)}
            disabled={!enabled || submitting} placeholder="输入你希望 AI 处理的内容或补充说明…"
            className="w-full h-20 text-sm bg-sunken2 border border-hairline rounded-[10px] p-2 text-t1 placeholder:text-t3 resize-none focus:outline-none focus:ring-1 focus:ring-accent2-ring disabled:opacity-50" />
        </div>
        <div className="p-3 border-t border-hairline flex justify-end pb-[max(0.75rem,env(safe-area-inset-bottom))]">
          <button type="button" onClick={() => void execute()} disabled={!enabled || !detail || !!error || submitting}
            className="dialog-control flex items-center gap-1 px-3 min-h-11 text-[13px] font-medium rounded-[10px] bg-accent2-strong hover:bg-accent2 text-white transition-colors disabled:opacity-50 disabled:cursor-not-allowed">
            <Play size={14} aria-hidden="true" />{submitting ? '检查技能状态…' : '执行技能'}
          </button>
        </div>
      </div>
    </div>
  );
};
