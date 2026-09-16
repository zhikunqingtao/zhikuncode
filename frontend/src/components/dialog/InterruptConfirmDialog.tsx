/**
 * InterruptConfirmDialog — 停止任务二次确认（防误点）
 *
 * 三形态统一（§8.1）：桌面 / 平板走 Dialog 基元，手机走 SheetShell 底部抽屉（§8.4）。
 * Esc / 遮罩点击 / 下滑关闭 / 右上角关闭按钮一律等价于「取消」（onClose），
 * 只有「确认停止」按钮才会触发 onConfirm → 真正的中断逻辑。
 */

import { Button, Dialog } from '@/components/ui';
import { SheetShell } from '@/components/apos/MobileBottomSheet';
import { useResponsive } from '@/hooks/useResponsive';

export interface InterruptConfirmDialogProps {
    open: boolean;
    /** 取消出口：Esc / 遮罩 / 关闭按钮 / 下滑 全部走这里，不触发中断 */
    onClose: () => void;
    /** 确认出口：唯一会真正中断生成的路径 */
    onConfirm: () => void;
}

const TITLE = '停止当前任务';
const DESCRIPTION = '停止后 AI 会立即中断当前生成，已经产出的内容会保留。确定要停止吗？';

export function InterruptConfirmDialog({
    open,
    onClose,
    onConfirm,
}: InterruptConfirmDialogProps) {
    const { isMobile } = useResponsive();

    /* 手机形态：底部抽屉，按钮全宽纵向堆叠（≥44px 命中区）+ 安全区 padding */
    if (isMobile) {
        return (
            <SheetShell
                isOpen={open}
                onClose={onClose}
                ariaLabel={TITLE}
                header={
                    <div className="px-4 pb-3">
                        <h3 className="text-t1 text-base font-semibold">{TITLE}</h3>
                    </div>
                }
                footer={
                    <div className="flex flex-col gap-2 border-t border-hairline px-4 pt-3 pb-[max(env(safe-area-inset-bottom),8px)]">
                        <Button variant="ghost" className="w-full" onClick={onClose}>
                            取消
                        </Button>
                        <Button variant="danger" className="w-full" onClick={onConfirm}>
                            确认停止
                        </Button>
                    </div>
                }
            >
                <p className="px-4 pb-4 text-sm leading-relaxed text-t2">{DESCRIPTION}</p>
            </SheetShell>
        );
    }

    /* 桌面 / 平板形态：居中 Dialog（max-md: 前缀兜底窄视口下的全宽纵向堆叠） */
    return (
        <Dialog open={open} onClose={onClose} title={TITLE}>
            <p className="px-5 text-sm leading-relaxed text-t2">{DESCRIPTION}</p>
            <div className="flex justify-end gap-2 px-5 pb-5 pt-4 max-md:flex-col max-md:px-4 max-md:pb-4">
                <Button variant="ghost" className="max-md:w-full" onClick={onClose}>
                    取消
                </Button>
                <Button variant="danger" className="max-md:w-full" onClick={onConfirm}>
                    确认停止
                </Button>
            </div>
        </Dialog>
    );
}

export default InterruptConfirmDialog;
