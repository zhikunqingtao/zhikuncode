/**
 * MobileBottomSheet — 移动端 Bottom Sheet 体系（§8.4）
 *
 * 规范落实：
 * - 顶部 rounded-panel（rounded-t-panel）、grabber 36×4px、max-height 85dvh
 * - 遮罩 overlay2；拖拽 / 遮罩点击 / Esc 关闭；spring 参数保留（damping 25 / stiffness 300）
 * - 拖拽关闭阈值：下拉 >25% 面板高度 或 速度 >500px/s，否则回弹
 * - 滚动分层：内容区 `touch-action: pan-y` 正常滚动，滚动到顶后继续下拉才触发拖拽关闭
 *   （grabber / 头部区 `touch-action: none`，始终可拖拽）
 * - 焦点归还（§10.7-④）：打开时聚焦面板，关闭后归还触发器
 *
 * 导出：
 * - `SheetShell` — 通用 sheet 壳（MobileApprovalSheet 等移动弹层复用；
 *    framer-motion 仅允许存在于本文件，§5.4 白名单）
 * - `MobileBottomSheet` — Activity 详情 sheet（由 MobileStatusBar 点击展开接线）
 */

import { useEffect, useCallback, useRef, type ReactNode, type PointerEvent as ReactPointerEvent } from 'react';
import { createPortal } from 'react-dom';
import { motion, AnimatePresence, useDragControls, type PanInfo } from 'framer-motion';
import { X, Check, ArrowRight } from 'lucide-react';
import type { ActivityData, RiskAssessment } from '@/types/apos';
import { useActivityStore } from '@/store/activityStore';
import { useInsightStore } from '@/store/insightStore';
import { SignalBadge } from './SignalBadge';
import { VerificationIcon } from './VerificationIcon';
import { MobileImpactList } from './MobileImpactList';

/** §8.4 拖拽关闭阈值：速度 >500px/s 立即关闭 */
const DRAG_CLOSE_VELOCITY = 500;
/** §8.4 拖拽关闭阈值：下拉距离超过面板高度的 25% */
const DRAG_CLOSE_RATIO = 0.25;

export interface SheetShellProps {
  isOpen: boolean;
  onClose: () => void;
  /** 无障碍名（同时作为 e2e 定位锚点） */
  ariaLabel: string;
  children: ReactNode;
  /** 头部（可选）— 渲染于 grabber 之下，随 grabber 一起可拖拽 */
  header?: ReactNode;
  /** 底部固定操作区（可选）— 不参与滚动、不触发拖拽 */
  footer?: ReactNode;
}

export function SheetShell({ isOpen, onClose, ariaLabel, children, header, footer }: SheetShellProps) {
  const dragControls = useDragControls();
  const panelRef = useRef<HTMLDivElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);

  // ESC key close
  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    },
    [onClose]
  );

  // 打开时：锁定背景滚动 + 聚焦面板；关闭/卸载时：归还焦点到触发器（§10.7-④）
  useEffect(() => {
    if (!isOpen) return;
    const previousActive = document.activeElement as HTMLElement | null;
    document.body.style.overflow = 'hidden';
    document.addEventListener('keydown', handleKeyDown);
    const raf = requestAnimationFrame(() => panelRef.current?.focus());
    return () => {
      cancelAnimationFrame(raf);
      document.body.style.overflow = '';
      document.removeEventListener('keydown', handleKeyDown);
      if (previousActive && document.contains(previousActive)) {
        previousActive.focus();
      }
    };
  }, [isOpen, handleKeyDown]);

  // 拖拽结束 — 下拉 >25% 高度或速度 >500px/s 才关闭，否则回弹（spring 参数保留）
  const handleDragEnd = (_event: MouseEvent | TouchEvent | PointerEvent, info: PanInfo) => {
    const panelHeight = panelRef.current?.getBoundingClientRect().height ?? window.innerHeight;
    if (info.offset.y > panelHeight * DRAG_CLOSE_RATIO || info.velocity.y > DRAG_CLOSE_VELOCITY) {
      onClose();
    }
  };

  // grabber / 头部：始终可拖拽
  const startDrag = (e: ReactPointerEvent<HTMLDivElement>) => {
    dragControls.start(e);
  };

  // 内容区：滚动到顶后继续下拉才触发关闭（touch-action: pan-y 分层）
  const handleContentPointerDown = (e: ReactPointerEvent<HTMLDivElement>) => {
    const target = e.target as HTMLElement;
    if (target.closest('button, a, input, textarea, select, [role="button"], [data-no-drag]')) return;
    if ((contentRef.current?.scrollTop ?? 0) <= 0) {
      dragControls.start(e);
    }
  };

  return createPortal(
    <AnimatePresence>
      {isOpen && (
        <div
          className="fixed inset-0 z-[9999] flex items-end"
          role="dialog"
          aria-modal="true"
          aria-label={ariaLabel}
        >
          {/* Overlay — overlay2 令牌 */}
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="absolute inset-0 bg-overlay2"
            onClick={onClose}
          />

          {/* Bottom Sheet Panel — 顶部 rounded-panel，max-height 85dvh */}
          <motion.div
            ref={panelRef}
            tabIndex={-1}
            initial={{ y: '100%' }}
            animate={{ y: 0 }}
            exit={{ y: '100%' }}
            transition={{ type: 'spring', damping: 25, stiffness: 300 }}
            drag="y"
            dragListener={false}
            dragControls={dragControls}
            dragConstraints={{ top: 0, bottom: 0 }}
            dragElastic={{ top: 0, bottom: 0.6 }}
            onDragEnd={handleDragEnd}
            className="relative w-full rounded-t-panel bg-surfacev2 shadow-e4 border-t border-hairline z-10 flex flex-col outline-none"
            style={{ maxHeight: '85dvh' }}
          >
            {/* Grabber — 36×4px，整区可拖拽 */}
            <div
              onPointerDown={startDrag}
              style={{ touchAction: 'none' }}
              className="flex justify-center pt-2 pb-1 flex-shrink-0 cursor-grab active:cursor-grabbing"
              aria-hidden="true"
            >
              <div className="h-1 w-9 rounded-full bg-t3/40" />
            </div>

            {header && (
              <div
                onPointerDown={startDrag}
                style={{ touchAction: 'none' }}
                className="flex-shrink-0"
              >
                {header}
              </div>
            )}

            {/* 可滚动内容 — 滚动到顶后继续下拉才触发关闭 */}
            <div
              ref={contentRef}
              onPointerDown={handleContentPointerDown}
              style={{ touchAction: 'pan-y' }}
              className="flex-1 min-h-0 overflow-y-auto overscroll-contain"
            >
              {children}
            </div>

            {footer && <div className="flex-shrink-0">{footer}</div>}
          </motion.div>
        </div>
      )}
    </AnimatePresence>,
    document.body
  );
}

export interface MobileBottomSheetProps {
  isOpen: boolean;
  onClose: () => void;
  /** 最新 Activity（可选）— 缺省时 sheet 仅展示状态摘要（高风险文件） */
  activity?: ActivityData;
  assessment?: RiskAssessment;
  onApprove?: (id: string) => void;
  onReject?: (id: string) => void;
  onViewDetails?: (id: string) => void;
}

const IMPACT_BADGE_COLORS: Record<string, string> = {
  direct: 'bg-errsoft text-errstrong',
  indirect: 'bg-warnsoft text-warnstrong',
  potential: 'bg-accent2-soft text-accent2',
};

export function MobileBottomSheet({
  isOpen,
  onClose,
  activity: activityProp,
  assessment: assessmentProp,
  onApprove,
  onReject,
  onViewDetails,
}: MobileBottomSheetProps) {
  // 订阅 store 获取最新数据，prop 作为 fallback
  const liveActivity = useActivityStore(
    (s) => (activityProp ? s.activities.get(activityProp.id) : undefined)
  );
  const liveAssessment = useInsightStore((s) =>
    activityProp ? s.assessments.get(activityProp.id) : undefined
  );
  const activity = liveActivity ?? activityProp;
  const assessment = liveAssessment ?? assessmentProp;

  const signal = activity?.insight?.signal ?? 'auto_approve';

  const header = (
    <div className="flex items-center justify-between px-4 pb-3 border-b border-hairline">
      <div className="flex items-center gap-2 min-w-0">
        <h3 className="text-sm font-semibold text-t1 truncate">
          {activity ? activity.summary : '状态详情'}
        </h3>
        {activity && <SignalBadge signal={signal} size="sm" />}
      </div>
      <button
        onClick={onClose}
        className="flex-shrink-0 min-w-[44px] min-h-[44px] -mr-2 flex items-center justify-center rounded-full hover:bg-hover2 transition-colors duration-fast"
        aria-label="关闭"
      >
        <X size={18} className="text-t3" />
      </button>
    </div>
  );

  const footer = activity ? (
    <div className="flex items-center gap-2 px-4 py-2 border-t border-hairline pb-[max(env(safe-area-inset-bottom),8px)]">
      {activity.decision ? (
        <span className={`inline-flex items-center gap-1 px-4 min-h-[44px] text-xs font-medium rounded-xl ${
          activity.decision === 'approved'
            ? 'bg-oksoft text-okstrong'
            : 'bg-errsoft text-errstrong'
        }`}>
          {activity.decision === 'approved' ? (
            <><Check size={14} /> 已批准 ✓</>
          ) : (
            <><X size={14} /> 已拒绝 ✗</>
          )}
        </span>
      ) : activity.insight?.signal === 'auto_approve' ? (
        <span className="inline-flex items-center gap-1 px-4 min-h-[44px] text-xs font-medium rounded-xl bg-sunken2 text-t3">
          <Check size={14} /> 已自动放行
        </span>
      ) : (
        <>
          <button
            onClick={() => onApprove?.(activity.id)}
            className="inline-flex items-center gap-1 px-4 min-h-[44px] text-xs font-medium rounded-xl bg-oksoft text-okstrong active:scale-[.97] transition-interactive duration-fast"
          >
            <Check size={14} /> 批准
          </button>
          <button
            onClick={() => onReject?.(activity.id)}
            className="inline-flex items-center gap-1 px-4 min-h-[44px] text-xs font-medium rounded-xl bg-errsoft text-errstrong active:scale-[.97] transition-interactive duration-fast"
          >
            <X size={14} /> 拒绝
          </button>
        </>
      )}
      <button
        onClick={() => onViewDetails?.(activity.id)}
        className="inline-flex items-center gap-1 px-4 min-h-[44px] text-xs font-medium rounded-xl bg-sunken2 text-t2 active:scale-[.97] transition-interactive duration-fast ml-auto"
      >
        详情 <ArrowRight size={14} />
      </button>
    </div>
  ) : undefined;

  return (
    <SheetShell
      isOpen={isOpen}
      onClose={onClose}
      ariaLabel="状态详情"
      header={header}
      footer={footer}
    >
      <div className="px-4 py-3 space-y-4">
        {/* Deterministic Verification Results */}
        {assessment && (
          <div className="space-y-2">
            <h4 className="text-xs font-semibold text-t3 uppercase tracking-wide">
              确定性验证
            </h4>
            <div className="grid grid-cols-3 gap-2">
              {/* TypeScript Check */}
              <div className="flex items-center gap-1.5 text-xs">
                <VerificationIcon
                  status={assessment.deterministic.typeCheck.passed ? 'all_pass' : 'has_error'}
                  size={14}
                />
                <span className="text-t2">tsc</span>
                {assessment.deterministic.typeCheck.errorCount > 0 && (
                  <span className="text-err">
                    {assessment.deterministic.typeCheck.errorCount} 错误
                  </span>
                )}
                {assessment.deterministic.typeCheck.passed && (
                  <span className="text-ok">通过</span>
                )}
              </div>

              {/* ESLint Check */}
              <div className="flex items-center gap-1.5 text-xs">
                <VerificationIcon
                  status={
                    assessment.deterministic.lint.errorCount > 0
                      ? 'has_error'
                      : assessment.deterministic.lint.warningCount > 0
                        ? 'has_warning'
                        : 'all_pass'
                  }
                  size={14}
                />
                <span className="text-t2">eslint</span>
                {assessment.deterministic.lint.errorCount > 0 && (
                  <span className="text-err">{assessment.deterministic.lint.errorCount} 错误</span>
                )}
                {assessment.deterministic.lint.warningCount > 0 && assessment.deterministic.lint.errorCount === 0 && (
                  <span className="text-warn">{assessment.deterministic.lint.warningCount} 警告</span>
                )}
                {assessment.deterministic.lint.passed && assessment.deterministic.lint.warningCount === 0 && (
                  <span className="text-ok">通过</span>
                )}
              </div>

              {/* Test Check */}
              <div className="flex items-center gap-1.5 text-xs">
                <VerificationIcon
                  status={
                    assessment.deterministic.tests.failedCount > 0
                      ? 'has_error'
                      : assessment.deterministic.tests.passedCount > 0
                        ? 'all_pass'
                        : 'skipped'
                  }
                  size={14}
                />
                <span className="text-t2">test</span>
                <span className={assessment.deterministic.tests.failedCount > 0 ? 'text-err' : 'text-ok'}>
                  {assessment.deterministic.tests.passedCount}/{assessment.deterministic.tests.passedCount + assessment.deterministic.tests.failedCount}
                </span>
              </div>
            </div>
          </div>
        )}

        {/* Heuristic Analysis */}
        {assessment && (
          <div className="space-y-1.5">
            <h4 className="text-xs font-semibold text-t3 uppercase tracking-wide">
              启发式分析
            </h4>
            <div className="flex gap-4 text-xs text-t3">
              <span>影响 API: <strong className="text-t1">{assessment.heuristic.affectedApiCount}</strong></span>
              <span>间接文件: <strong className="text-t1">{assessment.heuristic.indirectImpactCount}</strong></span>
              <span>置信度: <strong className={assessment.heuristic.hasHighConfidenceImpact ? 'text-warn' : 'text-t1'}>
                {assessment.heuristic.hasHighConfidenceImpact ? '高' : '低'}
              </strong></span>
            </div>
          </div>
        )}

        {/* Affected Files */}
        {activity && (
          <div className="space-y-1.5">
            <h4 className="text-xs font-semibold text-t3 uppercase tracking-wide">
              受影响文件
            </h4>
            <div className="space-y-1">
              {activity.changedFiles.slice(0, 5).map((file) => (
                <div key={file.filePath} className="flex items-center gap-2 text-xs">
                  <span className="text-t2 truncate flex-1 font-mono">
                    {file.filePath}
                  </span>
                  <span className={`px-1.5 py-0.5 rounded text-[10px] font-medium ${IMPACT_BADGE_COLORS[file.changeType === 'added' ? 'direct' : file.changeType === 'modified' ? 'direct' : 'potential'] ?? IMPACT_BADGE_COLORS.direct}`}>
                    {file.changeType ?? 'modified'}
                  </span>
                </div>
              ))}
              {activity.changedFiles.length > 5 && (
                <p className="text-xs text-t3">
                  +{activity.changedFiles.length - 5} 个文件...
                </p>
              )}
            </div>
          </div>
        )}

        {/* 高风险文件 — 状态细条详情（§8.5 点击展开内容） */}
        <div className="space-y-1.5">
          <h4 className="text-xs font-semibold text-t3 uppercase tracking-wide">
            高风险文件
          </h4>
          <div className="-mx-1">
            <MobileImpactList onViewAll={onClose} />
          </div>
        </div>
      </div>
    </SheetShell>
  );
}
