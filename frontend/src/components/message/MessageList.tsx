/**
 * MessageList — 虚拟滚动消息列表
 *
 * SPEC: §8.2.1 MessageList (virtualized), §8.2.4E VirtualMessageList
 * 使用 react-virtuoso 替代原版自研虚拟滚动:
 * - 动态高度消息项自动测量
 * - followOutput="smooth" 自动滚动到底部
 * - 大量消息场景下的高性能渲染 (仅渲染可见区域)
 * - 流式更新不闪烁 (streaming 消息使用增量渲染)
 *
 * 轮次分组聚合（P0）：顶部按 density 双渲染路径 ——
 * - detailed  → 既有平铺路径（itemContent/时间分隔条等逐字保留，作为一键回旧视图）；
 * - compact / balanced → 轮次分组路径：Virtuoso data 换为 buildTurns 投影，
 *   itemContent 渲染 TurnCard；单条消息分发与平铺路径共享 renderMessageContent。
 * 分组路径附带：顶部 TurnToolbar（密度三档 + 全部展开/折叠）、
 * 新指令自动折叠前轮、手动展开历史轮滚动对齐、pendingMessageId 深链的轮次适配。
 * Wave 3：轮次大纲（TurnOutline，桌面右侧抽屉 / 移动端 SheetShell，toolbar 切换，
 * 点选 = 展开该轮 + scrollToIndex 对齐顶部）与「回到最新」浮动胶囊（BackToLatestCapsule，
 * atBottomStateChange 追踪、两条路径共用，点击平滑滚底后 followOutput 自动恢复跟随）。
 * P2 修复：
 * - detailed 平铺路径不渲染 TurnToolbar（密度切换随之消失），补「退出详细视图」
 *   浮动出口（bottom-right 胶囊，一键回 balanced），否则只能清 localStorage 脱身；
 * - §8.8.3 移动键盘滚动桥：scrollerRef 接 Virtuoso 滚动元素 +
 *   useKeyboardScrollCompensation（仅移动挂载，桌面零副作用），键盘动画压缩
 *   可视高度后把底部滚动位置重新锚定到真实底部，并在布局沉降期间
 *   （padding 过渡 + Virtuoso 重测，P2b-2b）持续锚底直至稳定；
 *   followOutput 在移动态仅在「底部附近」（与胶囊同一检测、同一 60px 阈值）
 *   才跟随，用户上翻不强拉。
 */

import React, { useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState } from 'react';
import { Virtuoso, type VirtuosoHandle } from 'react-virtuoso';
import { ListCollapse } from 'lucide-react';
import { useMessageStore } from '@/store/messageStore';
import { useSessionStore } from '@/store/sessionStore';
import { useWorkbenchViewStore } from '@/store/workbenchViewStore';
import { useTurnViewStore } from '@/store/turnViewStore';
import { buildTurns, type Turn } from '@/store/selectors/turnProjection';
import { useResponsive } from '@/hooks/useResponsive';
import { useKeyboardScrollCompensation } from '@/hooks/useVirtualKeyboard';
import { cn } from '@/components/ui/cn';

import MessageItem from './MessageItem';
import TurnCard from './turn/TurnCard';
import TurnToolbar from './turn/TurnToolbar';
import TurnOutline from './turn/TurnOutline';
import BackToLatestCapsule, { shouldShowBackToLatest } from './BackToLatestCapsule';
import {
    computeTurnOrdinals,
    planTurnDeepLink,
    resolveTurnExpandedStates,
} from './turn/turnUtils';

// react-virtuoso 配置 — 对齐 §8.2.4E VIRTUOSO_CONFIG
const VIRTUOSO_CONFIG = {
    overscan: 200,
    increaseViewportBy: { top: 200, bottom: 200 },
    defaultItemHeight: 80,
    // 「距底 60px 内视为在底部」：避免平滑滚底/内容测量产生亚像素~十几 px 偏差时
    // atBottom 卡在 false，导致 BackToLatest 胶囊不消失、followOutput 不恢复跟随
    atBottomThreshold: 60,
};

/**
 * §8.8.3 P2 移动键盘滚动补偿桥（仅 isMobile 时挂载，桌面零副作用）：
 * 复用 App 传入的 keyboardHeight 驱动
 * useKeyboardScrollCompensation —— 键盘弹起动画压缩滚动容器可视高度后，
 * 把仍位于底部的滚动位置重新锚定到真实底部（delta 补偿 + 布局沉降期间
 * 持续锚底直至稳定，P2b-2b）；用户上翻时（atBottom=false）不补偿，
 * 保留其阅读位置，沉降循环遇用户上滚手势立即停让。
 * 与 App.tsx MobileKeyboardBridge「弹起瞬间一次性滚底」正交、互不接管：
 * App 桥负责弹起瞬间滚到底部，本桥自键盘开启瞬间接续，在布局沉降期间
 * （padding 过渡 + Virtuoso 重测）持续锚底直至稳定 —— 不以 atBottom 翻转
 * 为启动条件（实测其 true 事件在过渡窗口内不送达 React，P2b-2b 探针）。
 */
const MobileKeyboardScrollBridge: React.FC<{
    scrollerRef: React.RefObject<HTMLDivElement | null>;
    atBottom: boolean;
    keyboardHeight: number;
}> = ({ scrollerRef, atBottom, keyboardHeight }) => {
    useKeyboardScrollCompensation(scrollerRef, keyboardHeight, atBottom);
    return null;
};

/**
 * §7.6 纯新增：MessageList 对外命令式 API。
 * scrollToBottom 供「移动键盘弹起瞬间一次性滚底」等场景调用；
 * 不触碰 followOutput 等既有滚动行为（用户上翻不强制滚底逻辑保持原样）。
 */
export interface MessageListHandle {
    scrollToBottom: () => void;
}

const MessageList = React.forwardRef<MessageListHandle, { keyboardHeight?: number }>(({ keyboardHeight = 0 }, ref) => {
    const virtuosoRef = useRef<VirtuosoHandle>(null);
    // §8.8.3 P2：Virtuoso scroller 元素（移动键盘滚动补偿的直接作用对象；
    // react-virtuoso 的 scrollerRef 为回调形式（其类型含 Window 分支——仅
    // useWindowScroll 场景，本组件不涉及），桥接到普通 ref，两条渲染路径共用）
    const scrollerRef = useRef<HTMLDivElement | null>(null);
    const handleScrollerRef = useCallback((el: HTMLElement | Window | null) => {
        scrollerRef.current = el instanceof HTMLElement ? el as HTMLDivElement : null;
    }, []);
    // §7.6 移动态：消息流底部 96px 渐隐遮罩（仅移动渲染，桌面零变化）
    const { isMobile } = useResponsive();

    // §7.6 滚底 API（behavior:'auto' 一帧到位，避免与键盘/容器动画竞争）
    useImperativeHandle(ref, () => ({
        scrollToBottom: () => {
            virtuosoRef.current?.scrollToIndex({ index: 'LAST', align: 'end', behavior: 'auto' });
            // P2b-2b：scrollToIndex 以估算行高定位末项，未测高行会欠冲
            // （实测 ~276px）；用 scroller 真实几何强制抵底，保证
            // 「弹起滚底」语义当帧确定落地（不等沉降锚底循环首帧）
            const scroller = scrollerRef.current;
            if (scroller) scroller.scrollTop = scroller.scrollHeight - scroller.clientHeight;
        },
    }), []);

    // Subscribe to store slices
    const messages = useMessageStore(s => s.messages);
    const streamingMessageId = useMessageStore(s => s.streamingMessageId);
    const streamingContent = useMessageStore(s => s.streamingContent);
    const thinkingContent = useMessageStore(s => s.thinkingContent);
    const activeToolCalls = useMessageStore(s => s.activeToolCalls);
    const pendingMessageId = useWorkbenchViewStore(s => s.pendingMessageId);
    const consumePendingMessage = useWorkbenchViewStore(s => s.consumePendingMessage);

    // ==================== 轮次分组装配（detailed 路径不参与渲染，仅保持 hook 次序稳定） ====================
    const sessionId = useSessionStore(s => s.sessionId);
    const sessionStatus = useSessionStore(s => s.status);
    // messageStore 无 runState 字段；与 App.tsx 的 runActive 口径保持一致
    const isRunActive = sessionStatus === 'streaming' || sessionStatus === 'waiting_permission';
    const density = useTurnViewStore(s => s.density);
    const grouped = density !== 'detailed';
    const expandOverrides = useTurnViewStore(s => (sessionId ? s.expandOverrides[sessionId] : undefined));
    const steeringIds = useMessageStore(s => (sessionId ? s.steeringMessageIds[sessionId] : undefined));

    const turns = useMemo(
        () => buildTurns(messages, {
            steeringMessageIds: steeringIds && steeringIds.length > 0 ? new Set(steeringIds) : undefined,
        }),
        [messages, steeringIds],
    );
    const turnOrdinals = useMemo(() => computeTurnOrdinals(turns), [turns]);
    const turnIndexes = useMemo(() => turns.map(turn => turn.index), [turns]);
    const turnExpandedStates = useMemo(
        () => resolveTurnExpandedStates(turns, density, isRunActive, expandOverrides),
        [turns, density, isRunActive, expandOverrides],
    );

    useEffect(() => {
        if (!pendingMessageId) return;
        // 轮次分组路径：消息 uuid → 轮次 index，展开该轮后按轮次滚动，
        // 再在轮内用 data-message-uuid 锚点精确定位
        if (grouped) {
            const plan = planTurnDeepLink(turns, pendingMessageId);
            if (!plan) {
                consumePendingMessage();
                return;
            }
            if (sessionId) {
                useTurnViewStore.getState().setTurnExpanded(sessionId, plan.turnIndex, true);
            }
            let cancelled = false;
            let timer: ReturnType<typeof setTimeout> | null = null;
            let attempts = 0;
            const reveal = () => {
                if (cancelled) return;
                attempts += 1;
                if (!virtuosoRef.current && attempts < 10) {
                    timer = setTimeout(reveal, 50);
                    return;
                }
                virtuosoRef.current?.scrollToIndex({ index: plan.turnIndex, align: 'center', behavior: 'auto' });
                // Virtuoso performs its initial measurement after mount. A second
                // authoritative scroll prevents that first layout pass from
                // resetting a deep link to the beginning of a long Session.
                timer = setTimeout(() => {
                    virtuosoRef.current?.scrollToIndex({ index: plan.turnIndex, align: 'center', behavior: 'auto' });
                    requestAnimationFrame(() => {
                        document
                            .querySelector(`[data-message-uuid="${plan.messageId}"]`)
                            ?.scrollIntoView({ block: 'center' });
                        consumePendingMessage();
                    });
                }, 120);
            };
            const frame = requestAnimationFrame(reveal);
            return () => {
                cancelled = true;
                cancelAnimationFrame(frame);
                if (timer) clearTimeout(timer);
            };
        }
        // detailed 平铺路径：原有逻辑保持不变
        const index = messages.findIndex(message => message.uuid === pendingMessageId);
        if (index < 0) {
            consumePendingMessage();
            return;
        }
        let cancelled = false;
        let timer: ReturnType<typeof setTimeout> | null = null;
        let attempts = 0;
        const reveal = () => {
            if (cancelled) return;
            attempts += 1;
            if (!virtuosoRef.current && attempts < 10) {
                timer = setTimeout(reveal, 50);
                return;
            }
            virtuosoRef.current?.scrollToIndex({ index, align: 'center', behavior: 'auto' });
            // Virtuoso performs its initial measurement after mount. A second
            // authoritative scroll prevents that first layout pass from
            // resetting a deep link to the beginning of a long Session.
            timer = setTimeout(() => {
                virtuosoRef.current?.scrollToIndex({ index, align: 'center', behavior: 'auto' });
                consumePendingMessage();
            }, 120);
        };
        const frame = requestAnimationFrame(reveal);
        return () => {
            cancelled = true;
            cancelAnimationFrame(frame);
            if (timer) clearTimeout(timer);
        };
    }, [consumePendingMessage, messages, pendingMessageId, grouped, turns, sessionId]);

    // 新指令自动折叠前轮（仅分组路径）：轮次数增加时，原末轮及其中间轮一并折叠。
    // ref 无条件随 turns 更新，因此切会话/切密度不会误伤既有展开偏好。
    const prevTurnCountRef = useRef<{ sessionId: string | null; count: number }>({
        sessionId: null,
        count: 0,
    });
    useEffect(() => {
        const prev = prevTurnCountRef.current;
        prevTurnCountRef.current = { sessionId, count: turns.length };
        if (!grouped || !sessionId) return;
        if (prev.sessionId !== sessionId || prev.count === 0) return;
        if (turns.length < 2 || turns.length <= prev.count) return;
        const from = Math.max(0, prev.count - 1);
        for (let i = from; i <= turns.length - 2; i += 1) {
            useTurnViewStore.getState().setTurnExpanded(sessionId, turns[i].index, false);
        }
    }, [grouped, sessionId, turns]);

    // 手动展开历史轮不抢滚动：展开后若其 header 已滚出视口上方，对齐到该轮
    const handleAfterTurnToggle = useCallback((turnIndex: number, expanded: boolean) => {
        if (!expanded) return;
        requestAnimationFrame(() => {
            const header = document.querySelector(`[data-turn-header="${turnIndex}"]`);
            if (!header) return;
            const scroller = header.closest('[data-virtuoso-scroller]');
            const scrollerTop = scroller ? scroller.getBoundingClientRect().top : 0;
            if (header.getBoundingClientRect().top < scrollerTop) {
                virtuosoRef.current?.scrollToIndex({ index: turnIndex, align: 'start', behavior: 'auto' });
            }
        });
    }, []);

    // ==================== Wave 3：轮次大纲 + 「回到最新」胶囊 ====================
    // 大纲抽屉开关（仅分组路径渲染；detailed 下不可见）
    const [outlineOpen, setOutlineOpen] = useState(false);
    // atBottom 追踪（两条 Virtuoso 路径共用），驱动胶囊显隐
    const [atBottom, setAtBottom] = useState(true);

    // 切回 detailed（平铺路径无大纲入口）或切会话时收起抽屉
    useEffect(() => {
        if (!grouped) setOutlineOpen(false);
    }, [grouped]);
    useEffect(() => {
        setOutlineOpen(false);
    }, [sessionId]);

    const handleToggleOutline = useCallback(() => {
        setOutlineOpen(prev => !prev);
    }, []);
    const handleCloseOutline = useCallback(() => {
        setOutlineOpen(false);
    }, []);

    // 大纲点选：展开该轮 + Virtuoso 对齐顶部；移动端关闭 Sheet，桌面保持打开便于连续跳转
    const handleOutlineSelect = useCallback((turnIndex: number) => {
        if (sessionId) {
            useTurnViewStore.getState().setTurnExpanded(sessionId, turnIndex, true);
        }
        virtuosoRef.current?.scrollToIndex({ index: turnIndex, align: 'start', behavior: 'auto' });
        if (isMobile) setOutlineOpen(false);
    }, [sessionId, isMobile]);

    // 「回到最新」：平滑滚底；滚动到位后 atBottomStateChange(true) 使
    // followOutput(isAtBottom=true) 重新成立，跟随语义自动恢复（无需额外状态接管）
    const handleBackToLatest = useCallback(() => {
        virtuosoRef.current?.scrollToIndex({ index: 'LAST', align: 'end', behavior: 'smooth' });
    }, []);

    // P2 修复：detailed 平铺路径无 TurnToolbar（密度切换 UI 随之消失），
    // 「退出详细视图」浮动出口 = 回 balanced 的常驻 UI 路径；
    // 与 TurnToolbar 同口径：切换密度即放弃当前会话手动展开偏好。
    const handleExitDetailed = useCallback(() => {
        useTurnViewStore.getState().setDensity('balanced', sessionId ?? undefined);
    }, [sessionId]);

    // Render each message item
    const itemContent = useCallback((index: number, _data: unknown) => {
        const msg = messages[index];
        if (!msg) return null;

        const prevMsg = index > 0 ? messages[index - 1] : undefined;
        const isStreaming = msg.uuid === streamingMessageId;

        return (
            <MessageItem
                message={msg}
                prevMessage={prevMsg}
                isStreaming={isStreaming}
                streamingContent={isStreaming ? streamingContent : undefined}
                thinkingContent={isStreaming ? thinkingContent : undefined}
                activeToolCalls={activeToolCalls}
            />
        );
    }, [messages, streamingMessageId, streamingContent, thinkingContent, activeToolCalls]);

    // 分组路径：每个 Virtuoso item = 一张 TurnCard
    const turnItemContent = useCallback((_index: number, turn: Turn) => (
        <TurnCard
            turn={turn}
            turnNumber={turnOrdinals[turn.index]}
            sessionId={sessionId}
            expanded={turnExpandedStates[turn.index] ?? false}
            isRunActive={isRunActive}
            streamingMessageId={streamingMessageId}
            streamingContent={streamingContent}
            thinkingContent={thinkingContent}
            activeToolCalls={activeToolCalls}
            onAfterToggle={handleAfterTurnToggle}
        />
    ), [
        turnOrdinals, sessionId, turnExpandedStates, isRunActive,
        streamingMessageId, streamingContent, thinkingContent, activeToolCalls,
        handleAfterTurnToggle,
    ]);

    const turnItemKey = useCallback((_index: number, turn: Turn) => turn.key, []);

    // Auto-scroll: follow output when streaming
    const followOutput = useCallback((isAtBottom: boolean): boolean | 'smooth' => {
        // 桌面保持既有语义：流式期间始终跟随。
        if (streamingMessageId && !isMobile) return 'smooth';
        // 移动态（§8.8.3 P2 键盘桥）：发送后/流式期间仅在「底部附近」才自动跟随
        // —— isAtBottom 与 BackToLatestCapsule 同一检测、同一 60px 阈值 ——
        // 用户上翻时不强拉（键盘压缩视口期间的滚动位置由补偿桥重新锚定）。
        return isAtBottom ? 'smooth' : false;
    }, [streamingMessageId, isMobile]);

    // §7.6 底部渐隐：transparent → --v2-bg-app，pointer-events-none，仅 isMobile
    const mobileBottomFade = isMobile ? (
        <div
            data-testid="mobile-message-fade"
            aria-hidden="true"
            className="pointer-events-none absolute inset-x-0 bottom-0 z-10 h-24"
            style={{
                background: 'linear-gradient(to bottom, transparent, var(--v2-bg-app))',
            }}
        />
    ) : null;

    if (messages.length === 0) {
        return (
            <div className="message-list relative flex h-full flex-col overflow-hidden"
                role="log" aria-live="polite" aria-label="对话消息">
                <EmptyState />
                {mobileBottomFade}
            </div>
        );
    }

    // detailed：既有平铺渲染路径（一键回旧视图；P2 起附加「退出详细视图」浮动出口）
    if (!grouped) {
        return (
            <div className="message-list relative h-full overflow-hidden" role="log" aria-live="polite" aria-label="对话消息">
                <Virtuoso
                    ref={virtuosoRef}
                    scrollerRef={handleScrollerRef}
                    totalCount={messages.length}
                    itemContent={itemContent}
                    followOutput={followOutput}
                    atBottomStateChange={setAtBottom}
                    atBottomThreshold={VIRTUOSO_CONFIG.atBottomThreshold}
                    overscan={VIRTUOSO_CONFIG.overscan}
                    increaseViewportBy={VIRTUOSO_CONFIG.increaseViewportBy}
                    defaultItemHeight={VIRTUOSO_CONFIG.defaultItemHeight}
                    alignToBottom
                    className="h-full"
                />
                {mobileBottomFade}
                <BackToLatestCapsule
                    visible={shouldShowBackToLatest(atBottom, messages.length)}
                    isRunActive={isRunActive}
                    onClick={handleBackToLatest}
                />
                {/* P2 修复：detailed 无 TurnToolbar，右下浮动出口一键回 balanced
                    （bottom-4/right-4 避开居中的 BackToLatestCapsule；z-20 与胶囊同层、
                    高于底部渐隐 z-10；样式对齐胶囊令牌） */}
                <button
                    type="button"
                    onClick={handleExitDetailed}
                    aria-label="退出详细视图"
                    title="退出详细视图"
                    data-testid="exit-detailed-view"
                    className={cn(
                        'absolute bottom-4 right-4 z-20 inline-flex items-center gap-1.5 rounded-full',
                        'border border-hairline bg-surfacev2 px-3 py-1.5 shadow-e2',
                        'text-xs font-medium text-t2',
                        'transition-interactive duration-fast hover:bg-hover2 hover:text-t1 hover:shadow-e3',
                        'focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring',
                    )}
                >
                    <ListCollapse className="h-3.5 w-3.5" aria-hidden="true" />
                    退出详细视图
                </button>
                {isMobile && <MobileKeyboardScrollBridge scrollerRef={scrollerRef} atBottom={atBottom} keyboardHeight={keyboardHeight} />}
            </div>
        );
    }

    // compact / balanced：轮次分组渲染路径
    return (
        <div className="message-list relative flex h-full flex-col overflow-hidden" role="log" aria-live="polite" aria-label="对话消息">
            <TurnToolbar
                density={density}
                sessionId={sessionId}
                turnIndexes={turnIndexes}
                outlineOpen={outlineOpen}
                onToggleOutline={handleToggleOutline}
            />
            <Virtuoso
                ref={virtuosoRef}
                scrollerRef={handleScrollerRef}
                data={turns}
                computeItemKey={turnItemKey}
                itemContent={turnItemContent}
                followOutput={followOutput}
                atBottomStateChange={setAtBottom}
                atBottomThreshold={VIRTUOSO_CONFIG.atBottomThreshold}
                overscan={VIRTUOSO_CONFIG.overscan}
                increaseViewportBy={VIRTUOSO_CONFIG.increaseViewportBy}
                defaultItemHeight={VIRTUOSO_CONFIG.defaultItemHeight}
                alignToBottom
                className="min-h-0 flex-1"
            />
            {mobileBottomFade}
            {isMobile && <MobileKeyboardScrollBridge scrollerRef={scrollerRef} atBottom={atBottom} keyboardHeight={keyboardHeight} />}
            <TurnOutline
                turns={turns}
                turnOrdinals={turnOrdinals}
                open={outlineOpen}
                isMobile={isMobile}
                isRunActive={isRunActive}
                onClose={handleCloseOutline}
                onSelect={handleOutlineSelect}
            />
            <BackToLatestCapsule
                visible={shouldShowBackToLatest(atBottom, messages.length)}
                isRunActive={isRunActive}
                onClick={handleBackToLatest}
            />
        </div>
    );
});

// ==================== Empty State ====================

const EmptyState: React.FC = () => (
    <div className="flex-1 flex items-center justify-center text-gray-500">
        <div className="text-center">
            <div className="text-4xl mb-3">💬</div>
            <div className="text-sm">Start a conversation</div>
            <div className="text-xs text-gray-600 mt-1">
                Type a message or use / for commands
            </div>
        </div>
    </div>
);

MessageList.displayName = 'MessageList';

export default React.memo(MessageList);
