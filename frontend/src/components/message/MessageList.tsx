/**
 * MessageList — 虚拟滚动消息列表（轮次分组聚合，三档密度统一路径）
 *
 * SPEC: §8.2.1 MessageList (virtualized), §8.2.4E VirtualMessageList
 * 使用 react-virtuoso 替代原版自研虚拟滚动:
 * - 动态高度消息项自动测量
 * - followOutput="smooth" 自动滚动到底部
 * - 大量消息场景下的高性能渲染 (仅渲染可见区域)
 * - 流式更新不闪烁 (streaming 消息使用增量渲染)
 *
 * 轮次分组聚合（三层模型）：三档密度（compact / balanced / detailed）统一走
 * 轮分组渲染 —— Virtuoso data 为 buildTurns 投影，itemContent 渲染 TurnCard
 * （轮 = query ｜ 过程区 ｜ 回复；简洁档三层默认折叠，其他档保留完整问题与回复，
 * 见 turn/TurnCard、turn/TurnProcessArea）。原 detailed 平铺分叉与
 * 「退出详细视图」浮动出口已随三层模型落地移除。
 * 详细档顶部固定任务导航（无任务轮回退轮次），支持 scrollspy 与移动 Sheet；
 * 密度切换位于输入框工具行，旧全部展开/折叠工具条已移除。
 * 分组路径附带：新指令自动折叠前轮（collectTurnExpandKeys + collapseAll）、
 * 手动展开历史轮滚动对齐（聚合条 data-turn-header 锚点）、pendingMessageId
 * 深链的分节适配（findProcessExpandKey 定位目标分节，compact 下按需先升档
 * 再展开）、「回到最新」浮动胶囊（BackToLatestCapsule，atBottomStateChange
 * 追踪，点击平滑滚底后 followOutput 自动恢复跟随）。
 * §8.8.3 移动键盘滚动桥：scrollerRef 接 Virtuoso 滚动元素 +
 * useKeyboardScrollCompensation（仅移动挂载，桌面零副作用），键盘动画压缩
 * 可视高度后把底部滚动位置重新锚定到真实底部，并在布局沉降期间
 * （padding 过渡 + Virtuoso 重测）持续锚底直至稳定；
 * followOutput 在移动态仅在「底部附近」（与胶囊同一检测、同一 60px 阈值）
 * 才跟随，用户上翻不强拉。
 */

import React, { useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState } from 'react';
import { Virtuoso, type VirtuosoHandle } from 'react-virtuoso';
import { useMessageStore } from '@/store/messageStore';
import { useSessionStore } from '@/store/sessionStore';
import { useWorkbenchViewStore } from '@/store/workbenchViewStore';
import { useTurnViewStore } from '@/store/turnViewStore';
import { buildTurns, type Turn } from '@/store/selectors/turnProjection';
import { collectTurnExpandKeys, findProcessExpandKey, findTurnMessageExpandKey } from '@/store/selectors/turnSections';
import { useResponsive } from '@/hooks/useResponsive';
import { useKeyboardScrollCompensation } from '@/hooks/useVirtualKeyboard';

import TurnCard from './turn/TurnCard';
import DetailNavigation from './turn/DetailNavigation';
import { buildTurnNavigation } from './turn/turnNavigation';
import { useTurnNavigation } from './turn/useTurnNavigation';
import BackToLatestCapsule, { shouldShowBackToLatest } from './BackToLatestCapsule';
import { planTurnDeepLink } from './turn/turnUtils';

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
 * 持续锚底直至稳定）；用户上翻时（atBottom=false）不补偿，
 * 保留其阅读位置，沉降循环遇用户上滚手势立即停让。
 * 与 App.tsx MobileKeyboardBridge「弹起瞬间一次性滚底」正交、互不接管：
 * App 桥负责弹起瞬间滚到底部，本桥自键盘开启瞬间接续，在布局沉降期间
 * （padding 过渡 + Virtuoso 重测）持续锚底直至稳定 —— 不以 atBottom 翻转
 * 为启动条件（实测其 true 事件在过渡窗口内不送达 React）。
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
    // useWindowScroll 场景，本组件不涉及），桥接到普通 ref
    const scrollerRef = useRef<HTMLDivElement | null>(null);
    const handleScrollerRef = useCallback((el: HTMLElement | Window | null) => {
        scrollerRef.current = el instanceof HTMLElement ? el as HTMLDivElement : null;
    }, []);
    // §7.6 移动态：消息流底部 96px 渐隐遮罩（仅移动渲染，桌面零变化）
    const { isMobile } = useResponsive();

    // Virtual rows can change height after the last index is mounted. Follow that
    // one explicit jump until geometry settles; any user scroll cancels it.
    const latestTurnIndex = useRef(0);
    const cancelLatestJump = useRef<() => void>(() => {});
    useEffect(() => () => cancelLatestJump.current(), []);
    const jumpToLatest = useCallback(() => {
        cancelLatestJump.current();
        const scroller = scrollerRef.current;
        if (!scroller) return;
        let frame = 0, attempts = 0, stableFrames = 0, previousHeight = -1;
        const cancel = () => {
            cancelAnimationFrame(frame);
            scroller.removeEventListener('wheel', cancel);
            scroller.removeEventListener('touchstart', cancel);
            scroller.removeEventListener('pointerdown', cancel);
            scroller.removeEventListener('keydown', cancel);
        };
        cancelLatestJump.current = cancel;
        scroller.addEventListener('wheel', cancel, { passive: true });
        scroller.addEventListener('touchstart', cancel, { passive: true });
        scroller.addEventListener('pointerdown', cancel);
        scroller.addEventListener('keydown', cancel);
        virtuosoRef.current?.scrollToIndex({ index: 'LAST', align: 'end', behavior: 'auto' });
        const settle = () => {
            const height = scroller.scrollHeight;
            const lastMounted = scroller.querySelector(`[data-turn-index="${latestTurnIndex.current}"]`);
            const lastRect = lastMounted?.getBoundingClientRect();
            const viewport = scroller.getBoundingClientRect();
            const footerHeight = scroller.querySelector('.glass-chat-spacer')?.getBoundingClientRect().height ?? 0;
            const inPlace = lastRect && lastRect.bottom > viewport.top && lastRect.bottom <= viewport.bottom - footerHeight + 2;
            stableFrames = inPlace && height === previousHeight ? stableFrames + 1 : 0;
            previousHeight = height;
            if (!inPlace) virtuosoRef.current?.scrollToIndex({ index: 'LAST', align: 'end', behavior: 'auto', offset: footerHeight });
            if (++attempts < 60 && stableFrames < 5) frame = requestAnimationFrame(settle);
            else cancel();
        };
        frame = requestAnimationFrame(settle);
    }, []);
    useImperativeHandle(ref, () => ({ scrollToBottom: jumpToLatest }), [jumpToLatest]);

    // Subscribe to store slices
    const messages = useMessageStore(s => s.messages);
    const streamingMessageId = useMessageStore(s => s.streamingMessageId);
    const streamingContent = useMessageStore(s => s.streamingContent);
    const thinkingContent = useMessageStore(s => s.thinkingContent);
    const activeToolCalls = useMessageStore(s => s.activeToolCalls);
    const pendingMessageId = useWorkbenchViewStore(s => s.pendingMessageId);
    const consumePendingMessage = useWorkbenchViewStore(s => s.consumePendingMessage);

    // ==================== 轮次分组装配 ====================
    const sessionId = useSessionStore(s => s.sessionId);
    const sessionStatus = useSessionStore(s => s.status);
    // messageStore 无 runState 字段；与 App.tsx 的 runActive 口径保持一致
    const isRunActive = sessionStatus === 'streaming' || sessionStatus === 'waiting_permission';
    const steeringIds = useMessageStore(s => (sessionId ? s.steeringMessageIds[sessionId] : undefined));

    const turns = useMemo(
        () => buildTurns(messages, {
            steeringMessageIds: steeringIds && steeringIds.length > 0 ? new Set(steeringIds) : undefined,
        }),
        [messages, steeringIds],
    );
    latestTurnIndex.current = turns.at(-1)?.index ?? 0;
    useEffect(() => () => cancelLatestJump.current(), [sessionId]);
    const density = useTurnViewStore(s => s.density);
    const navigationSignature = JSON.stringify(buildTurnNavigation(turns));
    const navigationEntries = useMemo(() => JSON.parse(navigationSignature) as ReturnType<typeof buildTurnNavigation>, [navigationSignature]);
    const navigation = useTurnNavigation(navigationEntries, density === 'detailed', sessionId, scrollerRef, virtuosoRef);
    const previousView = useRef({ sessionId, density });
    useEffect(() => {
        const previous = previousView.current;
        previousView.current = { sessionId, density };
        // 仅用户切入详细档时定位最新节；初始恢复/切会话保持底部，深链优先。
        if (previous.sessionId === sessionId && previous.density !== 'detailed'
            && density === 'detailed' && !pendingMessageId) {
            const latest = navigationEntries[navigationEntries.length - 1];
            if (latest) navigation.select(latest);
        }
    }, [density, sessionId, pendingMessageId, navigationEntries, navigation.select]);

    // pendingMessageId 深链：消息 uuid → 轮次 + 所属分节，展开目标分节后
    // 按轮次滚动，再在轮内用 data-message-uuid 锚点精确定位。
    // 目标在过程区时：compact 下分节不可见（任务清单不含消息体）、prep 仅
    // detailed 展示 —— 按需先升档（setDensity 清空 overrides）再写展开 override。
    useEffect(() => {
        if (!pendingMessageId) return;
        const plan = planTurnDeepLink(turns, pendingMessageId);
        if (!plan) {
            consumePendingMessage();
            return;
        }
        const targetTurn = turns[plan.turnIndex];
        if (sessionId && targetTurn) {
            const store = useTurnViewStore.getState();
            const expandKey = findProcessExpandKey(targetTurn, plan.messageId);
            if (expandKey !== null) {
                const targetMessage = targetTurn.messages.find(message => message.uuid === plan.messageId);
                // 平衡档只渲染工具摘要；含其他内容的目标需详细档才能完整显示。
                const toolSummaryOnly = targetMessage?.type === 'assistant'
                    && targetMessage.content.length > 0
                    && targetMessage.content.every(block => block.type === 'tool_use');
                if (expandKey.endsWith(':prep') || (expandKey.includes(':') && !toolSummaryOnly)) {
                    if (store.density !== 'detailed') store.setDensity('detailed', sessionId);
                } else if (expandKey.includes(':') && store.density === 'compact') {
                    store.setDensity('balanced', sessionId);
                }
                store.setSectionExpanded(sessionId, expandKey, true);
            }
            if (expandKey === null && store.density === 'compact') {
                const messageKey = findTurnMessageExpandKey(targetTurn, plan.messageId);
                if (messageKey) store.setSectionExpanded(sessionId, messageKey, true);
            }
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
    }, [consumePendingMessage, pendingMessageId, turns, sessionId]);

    // 简洁/平衡档新指令自动折叠前轮过程；详细档保持当前展开状态。
    // ref 无条件随 turns 更新，因此切会话不会误伤既有展开偏好。
    const prevTurnCountRef = useRef<{ sessionId: string | null; count: number }>({
        sessionId: null,
        count: 0,
    });
    useEffect(() => {
        const prev = prevTurnCountRef.current;
        prevTurnCountRef.current = { sessionId, count: turns.length };
        if (!sessionId || density === 'detailed') return;
        if (prev.sessionId !== sessionId || prev.count === 0) return;
        if (turns.length < 2 || turns.length <= prev.count) return;
        const from = Math.max(0, prev.count - 1);
        const keys = turns.slice(from, turns.length - 1).flatMap(turn => collectTurnExpandKeys(turn));
        if (keys.length > 0) {
            useTurnViewStore.getState().collapseAll(sessionId, keys);
        }
    }, [sessionId, turns, density]);

    // 手动展开历史轮不抢滚动：展开后若其聚合条已滚出视口上方，对齐到该轮
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

    // 「回到最新」胶囊显隐：atBottom 追踪
    const [atBottom, setAtBottom] = useState(true);

    // 「回到最新」：精确定位末项并计入输入区留白；到位后 atBottomStateChange(true) 使
    // followOutput(isAtBottom=true) 重新成立，跟随语义自动恢复（无需额外状态接管）
    const handleBackToLatest = useCallback(() => {
        navigation.cancelNavigation();
        jumpToLatest();
    }, [navigation.cancelNavigation, jumpToLatest]);

    // 每个 Virtuoso item = 一张 TurnCard（三层模型；密度/展开态由 TurnCard 自订阅）
    const turnItemContent = useCallback((_index: number, turn: Turn) => (
        <TurnCard
            turn={turn}
            sessionId={sessionId}
            isRunActive={isRunActive}
            streamingMessageId={streamingMessageId}
            streamingContent={streamingContent}
            thinkingContent={thinkingContent}
            activeToolCalls={activeToolCalls}
            onAfterToggle={handleAfterTurnToggle}
        />
    ), [
        sessionId, isRunActive, streamingMessageId, streamingContent, thinkingContent,
        activeToolCalls, handleAfterTurnToggle,
    ]);

    const turnItemKey = useCallback((_index: number, turn: Turn) => turn.key, []);

    // Auto-scroll: follow output when streaming
    const followOutput = useCallback((isAtBottom: boolean): boolean | 'smooth' => {
        // 桌面保持既有语义：流式期间始终跟随。
        if (streamingMessageId && !isMobile && density !== 'detailed') return 'smooth';
        // 移动态（§8.8.3 P2 键盘桥）：发送后/流式期间仅在「底部附近」才自动跟随
        // —— isAtBottom 与 BackToLatestCapsule 同一检测、同一 60px 阈值 ——
        // 用户上翻时不强拉（键盘压缩视口期间的滚动位置由补偿桥重新锚定）。
        return isAtBottom ? 'smooth' : false;
    }, [streamingMessageId, isMobile, density]);

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

    // 三档密度统一：轮次分组渲染路径
    return (
        <div className="message-list relative flex h-full flex-col overflow-hidden" role="log" aria-live="polite" aria-label="对话消息">
            {density === 'detailed' && <DetailNavigation
                key={`navigation-${sessionId}`}
                entries={navigationEntries}
                activeKey={navigation.activeKey}
                isMobile={isMobile}
                onSelect={navigation.select}
                onLatest={handleBackToLatest}
            />}
            <Virtuoso
                key={`messages-${sessionId}`}
                initialTopMostItemIndex={Math.max(0, turns.length - 1)}
                ref={virtuosoRef}
                scrollerRef={handleScrollerRef}
                components={{ Footer: GlassComposerSpacer }}
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
    <div className="flex-1 flex items-center justify-center text-t2">
        <div className="text-center">
            <div className="text-4xl mb-3">💬</div>
            <div className="text-sm">Start a conversation</div>
            <div className="text-[13px] text-t2 mt-1">
                Type a message or use / for commands
            </div>
        </div>
    </div>
);

MessageList.displayName = 'MessageList';

export default React.memo(MessageList);

/** Stable footer identity keeps Virtuoso measurement intact while the composer grows. */
function GlassComposerSpacer() {
    return <div className="glass-chat-spacer" aria-hidden="true" />;
}
