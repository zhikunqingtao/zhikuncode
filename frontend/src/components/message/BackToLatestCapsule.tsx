/**
 * BackToLatestCapsule — 「回到最新」浮动胶囊（轮次分组聚合 Wave 3）
 *
 * 用户上翻离开底部时浮现于输入区上方居中（MessageList 相对容器内 absolute bottom，
 * 移动端天然位于 MobilePromptBar 上方；z-20 高于底部渐隐遮罩 z-10）。
 * 点击平滑滚回底部；atBottom 恢复后 Virtuoso followOutput 语义自动续上跟随，
 * 无需额外状态接管。run 进行中胶囊左侧带 accent 呼吸点。
 *
 * 显隐用 opacity + translate 过渡（常驻挂载；reduced-motion 由全局 §8.8.5 规则
 * 降级 + motion-reduce 兜底）；隐藏时 aria-hidden + 移出 Tab 序 + pointer-events-none，
 * 不遮挡消息交互。
 *
 * shouldShowBackToLatest 为可单测的纯函数（空会话不显示）。
 */

import React from 'react';
import { ArrowDown } from 'lucide-react';
import { cn } from '@/components/ui/cn';

/** 胶囊显隐（纯函数）：有消息且不在底部时显示 */
export function shouldShowBackToLatest(atBottom: boolean, messageCount: number): boolean {
    return messageCount > 0 && !atBottom;
}

export interface BackToLatestCapsuleProps {
    visible: boolean;
    /** run 进行中 → 左侧 accent 呼吸点 */
    isRunActive: boolean;
    onClick: () => void;
}

const BackToLatestCapsule: React.FC<BackToLatestCapsuleProps> = ({
    visible,
    isRunActive,
    onClick,
}) => (
    <div
        className={cn(
            'back-to-latest-position pointer-events-none absolute inset-x-0 bottom-4 z-20 flex justify-center',
            'transition-pop duration-base ease-soft motion-reduce:transition-none',
            visible ? 'translate-y-0 opacity-100' : 'translate-y-2 opacity-0',
        )}
    >
        <button
            type="button"
            onClick={onClick}
            disabled={!visible}
            aria-hidden={!visible}
            tabIndex={visible ? 0 : -1}
            data-testid="back-to-latest"
            className={cn(
                'inline-flex items-center gap-1.5 rounded-full',
                visible ? 'pointer-events-auto' : 'pointer-events-none',
                'border border-hairline bg-surfacev2 px-3 py-1.5 shadow-e2',
                'text-[13px] font-medium text-t2',
                'transition-interactive duration-fast hover:bg-hover2 hover:text-t1 hover:shadow-e3',
            )}
        >
            {isRunActive && (
                <span
                    className="inline-block h-1.5 w-1.5 rounded-full bg-accent2 animate-accent-pulse motion-reduce:animate-none"
                    aria-hidden="true"
                />
            )}
            <ArrowDown className="h-3.5 w-3.5" aria-hidden="true" />
            最新进展
        </button>
    </div>
);

export default React.memo(BackToLatestCapsule);
