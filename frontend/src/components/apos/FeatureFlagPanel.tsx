/**
 * FeatureFlagPanel — APOS Feature Flag 管理面板
 * SPEC: §8.6.2 APOS Layout Integration
 *
 * 列出所有 APOS Feature Flag 及其当前状态，提供 toggle 开关。
 * 当 Flag 因依赖关系被禁用时，显示灰色状态 + 提示。
 */

import { useFeatureFlagStore } from '@/store/featureFlagStore';
import { APOS_FLAG_DEFAULTS, type APOSFeatureFlags } from '@/types/apos';
import { Settings2 } from 'lucide-react';

/** Flag 描述映射 */
const FLAG_DESCRIPTIONS: Record<keyof APOSFeatureFlags, string> = {
  APOS_ACTIVITY_STREAM: '活动流主面板，展示 AI 操作实时流',
  APOS_AI_INSIGHT: 'AI 自审洞察分析（需先启用活动流）',
  APOS_BATCH_REVIEW: '批量审查操作支持（需先启用活动流）',
  APOS_RISK_HEATMAP: '风险热力图可视化（需先启用 AI 洞察）',
  APOS_CHANGE_IMPACT: '变更影响全景面板（需先启用活动流）',
  APOS_AGENT_PIPELINE: 'Agent Pipeline 多 Worker 可视化（需先启用活动流）',
  APOS_ANOMALY_ALERT: '异常告警面板（需先启用 Agent Pipeline）',
  APOS_MOBILE_STATUS: '移动端底部状态栏（需先启用活动流）',
};

/** Flag 显示名称 */
const FLAG_LABELS: Record<keyof APOSFeatureFlags, string> = {
  APOS_ACTIVITY_STREAM: 'Activity Stream',
  APOS_AI_INSIGHT: 'AI Insight',
  APOS_BATCH_REVIEW: 'Batch Review',
  APOS_RISK_HEATMAP: 'Risk Heatmap',
  APOS_CHANGE_IMPACT: 'Change Impact',
  APOS_AGENT_PIPELINE: 'Agent Pipeline',
  APOS_ANOMALY_ALERT: 'Anomaly Alert',
  APOS_MOBILE_STATUS: 'Mobile Status',
};

export function FeatureFlagPanel() {
  const flags = useFeatureFlagStore((s) => s.flags);
  const toggleFlag = useFeatureFlagStore((s) => s.toggleFlag);
  const getMissingDependencies = useFeatureFlagStore((s) => s.getMissingDependencies);
  const resetToDefaults = useFeatureFlagStore((s) => s.resetToDefaults);

  const flagKeys = Object.keys(APOS_FLAG_DEFAULTS) as (keyof APOSFeatureFlags)[];

  return (
    <div className="mx-3 my-3 rounded-lg border border-gray-700/50 bg-[#1e1e30] overflow-hidden">
      {/* Header */}
      <div className="flex items-center justify-between px-3 py-2 border-b border-gray-700/50 bg-[#16162a]">
        <div className="flex items-center gap-2">
          <Settings2 className="w-3.5 h-3.5 text-gray-400" />
          <span className="text-xs font-medium text-gray-300">Feature Flags</span>
        </div>
        <button
          onClick={resetToDefaults}
          className="text-[10px] px-2 py-0.5 rounded text-gray-500 hover:text-gray-300 hover:bg-gray-700/50 transition-colors"
        >
          重置
        </button>
      </div>

      {/* Flag List */}
      <div className="divide-y divide-gray-800/50">
        {flagKeys.map((key) => {
          const enabled = flags[key];
          const missing = getMissingDependencies(key);
          const isBlocked = missing.length > 0;

          return (
            <div
              key={key}
              className={`flex items-center justify-between px-3 py-2.5 ${
                isBlocked ? 'opacity-50' : ''
              }`}
            >
              <div className="flex-1 min-w-0 mr-3">
                <div className="text-xs font-medium text-gray-200 truncate">
                  {FLAG_LABELS[key]}
                </div>
                <div className="text-[10px] text-gray-500 mt-0.5 truncate">
                  {FLAG_DESCRIPTIONS[key]}
                </div>
                {isBlocked && (
                  <div className="text-[10px] text-amber-500/80 mt-0.5">
                    需要先启用: {missing.join(', ')}
                  </div>
                )}
              </div>

              {/* Toggle Switch */}
              <button
                onClick={() => !isBlocked && toggleFlag(key)}
                disabled={isBlocked}
                className={`relative w-8 h-[18px] rounded-full transition-colors flex-shrink-0 ${
                  enabled
                    ? 'bg-blue-600'
                    : isBlocked
                      ? 'bg-gray-700 cursor-not-allowed'
                      : 'bg-gray-600 hover:bg-gray-500'
                }`}
                title={isBlocked ? `依赖未满足: ${missing.join(', ')}` : `切换 ${FLAG_LABELS[key]}`}
              >
                <span
                  className={`absolute top-[2px] w-[14px] h-[14px] rounded-full bg-white shadow transition-transform ${
                    enabled ? 'translate-x-[16px]' : 'translate-x-[2px]'
                  }`}
                />
              </button>
            </div>
          );
        })}
      </div>
    </div>
  );
}
