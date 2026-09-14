/**
 * TurnOutline — 轮次大纲导航（轮次分组聚合 Wave 3，仅 compact/balanced 分组路径挂载）
 *
 * 双形态：
 * - 桌面端：消息区右侧滑出抽屉（absolute 定位于 MessageList 相对容器内，宽 260px，
 *   bg-surfacev2 + 左 hairline + shadow-e2，translate-x 滑入滑出，
 *   reduced-motion 由全局 §8.8.5 规则降级 + motion-reduce 兜底）。
 *   抽屉常驻挂载以保留关窗动画；列表延迟 300ms 卸载（对齐 TurnCard 折叠卸载模式），
 *   避免隐藏内容常驻 DOM / 可被聚焦。
 * - 移动端：复用 §8.4 SheetShell（MobileBottomSheet 的通用壳）底部抽屉承载同一份列表。
 *
 * 列表项：状态点（语义色与 TurnCard 卡片头一致）+ 「第 N 轮」+ 指令首行截断
 * + 右侧耗时；active 轮高亮（bg-hover2 + aria-current）；preamble 轮显示「会话上下文」。
 * 轮次多（>50）时列表区内部滚动（桌面 wrapper / 移动 SheetShell 内容区），无需虚拟化。
 *
 * 点击行为由调用方（MessageList）编排：setTurnExpanded(true) + Virtuoso scrollToIndex
 * 对齐顶部 + 移动端关闭（桌面保持打开便于连续跳转）。
 */

import React, { useEffect, useRef, useState } from 'react';
import { X } from 'lucide-react';
import type { Turn } from '@/store/selectors/turnProjection';
import { SheetShell } from '@/components/apos/MobileBottomSheet';
import { cn } from '@/components/ui/cn';
import {
    formatTurnDuration,
    instructionFirstLine,
    resolveTurnOutcome,
} from './turnUtils';

/** 关抽屉动画（--v2-dur-slow 240ms）结束后卸载列表的缓冲时长（对齐 TurnCard COLLAPSE_UNMOUNT_MS） */
const CLOSE_UNMOUNT_MS = 300;

export interface TurnOutlineProps {
    turns: Turn[];
    /** 「第 N 轮」序号数组（下标 = turn.index；preamble 为 null），来自 computeTurnOrdinals */
    turnOrdinals: Array<number | null>;
    open: boolean;
    /** 移动端 → SheetShell 底部抽屉；桌面 → 右侧滑出抽屉 */
    isMobile: boolean;
    /** run 进行中（active 轮状态点呼吸），口径与 TurnCard 一致 */
    isRunActive: boolean;
    onClose: () => void;
    /** 点击某轮：调用方负责 expand + scrollToIndex +（移动端）onClose */
    onSelect: (turnIndex: number) => void;
}

/**
 * 状态点语义色：与 TurnCard 卡片头保持一致。
 * （TurnCard 未导出该映射，此处按同一推导复制；TurnCard 归并行 worker 所有，勿改。）
 */
function outlineDotClass(turn: Turn, isRunActive: boolean): string {
    if (turn.status === 'active' && isRunActive) {
        return 'bg-accent2 animate-accent-pulse motion-reduce:animate-none';
    }
    const outcome = resolveTurnOutcome(turn);
    if (outcome === 'error') return 'bg-err';
    if (outcome === 'interrupted') return 'bg-warn';
    return 'bg-ok';
}

interface TurnOutlineListProps {
    turns: Turn[];
    turnOrdinals: Array<number | null>;
    isRunActive: boolean;
    onSelect: (turnIndex: number) => void;
}

/** 大纲列表（桌面/移动共用；滚动由外层容器承担） */
const TurnOutlineList: React.FC<TurnOutlineListProps> = ({
    turns,
    turnOrdinals,
    isRunActive,
    onSelect,
}) => (
    <ul className="turn-outline-list py-1" data-testid="turn-outline-list">
        {turns.map((turn) => {
            const ordinal = turnOrdinals[turn.index];
            const isActiveTurn = turn.status === 'active';
            return (
                <li key={turn.key}>
                    <button
                        type="button"
                        onClick={() => onSelect(turn.index)}
                        aria-current={isActiveTurn || undefined}
                        data-testid={`turn-outline-item-${turn.index}`}
                        className={cn(
                            'flex w-full items-center gap-2 px-3 py-2 text-left',
                            'transition-interactive duration-fast hover:bg-hover2',
                            isActiveTurn && 'bg-hover2',
                        )}
                    >
                        <span
                            className={cn(
                                'inline-block h-2 w-2 shrink-0 rounded-full',
                                outlineDotClass(turn, isRunActive),
                            )}
                            aria-hidden="true"
                            data-testid={`turn-outline-dot-${turn.index}`}
                        />
                        {turn.instruction ? (
                            <>
                                <span className="shrink-0 text-xs text-t4 tabular-nums">
                                    第 {ordinal} 轮
                                </span>
                                <span className="min-w-0 flex-1 truncate text-sm text-t1">
                                    {instructionFirstLine(turn.instruction)}
                                </span>
                            </>
                        ) : (
                            <span className="min-w-0 flex-1 truncate text-sm text-t1">
                                会话上下文
                            </span>
                        )}
                        <span className="shrink-0 text-xs text-t4 tabular-nums">
                            {formatTurnDuration(turn.startedAt, turn.endedAt)}
                        </span>
                    </button>
                </li>
            );
        })}
    </ul>
);

const TurnOutline: React.FC<TurnOutlineProps> = ({
    turns,
    turnOrdinals,
    open,
    isMobile,
    isRunActive,
    onClose,
    onSelect,
}) => {
    // 关抽屉时延迟卸载列表：保留滑出动画，同时避免隐藏列表常驻 DOM / 可被 Tab 聚焦
    const [listMounted, setListMounted] = useState(open);
    useEffect(() => {
        if (open) {
            setListMounted(true);
            return;
        }
        if (!listMounted) return;
        const timer = setTimeout(() => setListMounted(false), CLOSE_UNMOUNT_MS);
        return () => clearTimeout(timer);
    }, [open, listMounted]);

    // aria-hidden=true 时同步 inert：隐藏的抽屉（含关闭按钮等可聚焦内容）整体移出
    // Tab 序与辅助技术交互树。React 18 不支持 inert 布尔 prop，故经 ref 操作 DOM 属性。
    const drawerRef = useRef<HTMLElement | null>(null);
    useEffect(() => {
        drawerRef.current?.toggleAttribute('inert', !open);
    }, [open]);

    const list = (
        <TurnOutlineList
            turns={turns}
            turnOrdinals={turnOrdinals}
            isRunActive={isRunActive}
            onSelect={onSelect}
        />
    );

    const closeButton = (
        <button
            type="button"
            onClick={onClose}
            aria-label="关闭大纲"
            data-testid="turn-outline-close"
            className={cn(
                'shrink-0 rounded-md p-1 text-t3',
                'transition-interactive duration-fast hover:bg-hover2 hover:text-t1',
            )}
        >
            <X className="h-4 w-4" />
        </button>
    );

    // 移动端：§8.4 SheetShell 底部抽屉（遮罩/拖拽/Esc 关闭均由壳承担）
    if (isMobile) {
        return (
            <SheetShell
                isOpen={open}
                onClose={onClose}
                ariaLabel="轮次大纲"
                header={
                    <div className="flex items-center justify-between border-b border-hairline px-4 pb-3">
                        <h3 className="text-sm font-semibold text-t1">轮次大纲</h3>
                        {closeButton}
                    </div>
                }
            >
                <div className="pb-2">{list}</div>
            </SheetShell>
        );
    }

    // 桌面端：消息区右侧滑出抽屉（常驻挂载以保留 transition；关闭时 translate-x-full 移出）
    return (
        <aside
            ref={drawerRef}
            aria-label="轮次大纲"
            aria-hidden={!open}
            data-testid="turn-outline-drawer"
            className={cn(
                'absolute inset-y-0 right-0 z-20 flex w-[260px] flex-col',
                'border-l border-hairline bg-surfacev2 shadow-e2',
                'transition-transform duration-slow ease-soft motion-reduce:transition-none',
                !open && 'pointer-events-none translate-x-full',
            )}
        >
            <div className="flex shrink-0 items-center justify-between border-b border-hairline px-3 py-2">
                <span className="text-sm font-semibold text-t1">轮次大纲</span>
                {closeButton}
            </div>
            {listMounted && (
                <div className="min-h-0 flex-1 overflow-y-auto">{list}</div>
            )}
        </aside>
    );
};

export default React.memo(TurnOutline);
