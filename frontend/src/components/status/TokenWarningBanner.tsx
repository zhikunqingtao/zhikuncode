import React from 'react';
import { useMessageStore } from '@/store/messageStore';
// TokenWarningPayload type is inferred from messageStore selector

/**
 * Token 使用率告警横幅 — 当上下文窗口占用超过70%时显示。
 * - warning级别：黄色横幅
 * - critical/trigger_compact级别：红色横幅
 * - normal级别：不渲染
 */
export const TokenWarningBanner: React.FC = () => {
  const tokenWarning = useMessageStore((s) => s.tokenWarning);

  if (!tokenWarning || tokenWarning.warningLevel === 'normal') return null;

  const isCritical = tokenWarning.warningLevel === 'critical'
                  || tokenWarning.warningLevel === 'trigger_compact';

  return (
    <div className={`px-3 py-2 text-sm rounded-md mb-2 flex items-center gap-2 ${
      isCritical
        ? 'bg-red-50 text-red-700 border border-red-200 dark:bg-red-950 dark:text-red-300 dark:border-red-800'
        : 'bg-yellow-50 text-yellow-700 border border-yellow-200 dark:bg-yellow-950 dark:text-yellow-300 dark:border-yellow-800'
    }`}>
      <span className="font-medium shrink-0">
        {isCritical ? '⚠️ 上下文窗口即将用尽' : '⚠ 上下文窗口占用较高'}
      </span>
      <span className="text-xs opacity-80">
        {tokenWarning.usagePercent.toFixed(0)}% 已使用
        ({tokenWarning.currentTokens.toLocaleString()} / {tokenWarning.maxTokens.toLocaleString()} tokens)
      </span>
      {isCritical && (
        <span className="text-xs opacity-70 ml-auto">
          建议执行 /compact 压缩上下文
        </span>
      )}
    </div>
  );
};

export default TokenWarningBanner;
