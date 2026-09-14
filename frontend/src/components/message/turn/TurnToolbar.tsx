/**
 * TurnToolbar — 轮次分组视图顶部工具条（仅 compact/balanced 路径显示）
 *
 * 右侧：密度三档 segmented control（简洁/平衡/详细，复用 ui Tabs 的 sunken 槽样式）
 * + 「大纲」切换按钮（Wave 3，可选，列表图标，打开轮次大纲抽屉/Sheet）
 * + 「全部展开」「全部折叠」ghost 按钮（v2 token，克制）。
 * 移动端压缩为图标 + 短文案（max-md 隐藏文字标签），位于消息区上方，
 * 不与 App header 的 sidebar trigger 同层，天然不重叠。
 */

import React, { useCallback } from 'react';
import { ChevronsDownUp, ChevronsUpDown, List } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Tabs } from '@/components/ui/Tabs';
import { useTurnViewStore, type TurnDensity } from '@/store/turnViewStore';

const DENSITY_ITEMS: Array<{ value: TurnDensity; label: React.ReactNode }> = [
    { value: 'compact', label: '简洁' },
    { value: 'balanced', label: '平衡' },
    { value: 'detailed', label: '详细' },
];

export interface TurnToolbarProps {
    density: TurnDensity;
    sessionId: string | null;
    /** 当前全部轮次 index（expandAll/collapseAll 的入参） */
    turnIndexes: number[];
    /**
     * 大纲抽屉开关态（Wave 3）。与 onToggleOutline 成对提供且有轮次时
     * 才渲染「大纲」切换按钮；toolbar 本身仅在分组路径挂载，天然满足「仅分组路径显示」。
     */
    outlineOpen?: boolean;
    onToggleOutline?: () => void;
}

const TurnToolbar: React.FC<TurnToolbarProps> = ({
    density,
    sessionId,
    turnIndexes,
    outlineOpen,
    onToggleOutline,
}) => {
    const hasTurns = turnIndexes.length > 0;

    const handleDensityChange = useCallback((value: string) => {
        useTurnViewStore.getState().setDensity(value as TurnDensity, sessionId ?? undefined);
    }, [sessionId]);

    const handleExpandAll = useCallback(() => {
        if (!sessionId || !hasTurns) return;
        useTurnViewStore.getState().expandAll(sessionId, turnIndexes);
    }, [sessionId, hasTurns, turnIndexes]);

    const handleCollapseAll = useCallback(() => {
        if (!sessionId || !hasTurns) return;
        useTurnViewStore.getState().collapseAll(sessionId, turnIndexes);
    }, [sessionId, hasTurns, turnIndexes]);

    return (
        <div
            className="turn-toolbar flex shrink-0 items-center justify-end gap-1.5 border-b border-hairline bg-app2 px-3 py-1.5"
            data-testid="turn-toolbar"
        >
            <Tabs
                aria-label="消息密度"
                className="w-auto"
                items={DENSITY_ITEMS}
                value={density}
                onValueChange={handleDensityChange}
            />
            {onToggleOutline && hasTurns && (
                <Button
                    variant="ghost"
                    size="sm"
                    onClick={onToggleOutline}
                    aria-label="轮次大纲"
                    aria-pressed={outlineOpen ?? false}
                    title="轮次大纲"
                    data-testid="turn-outline-toggle"
                >
                    <List className="h-3.5 w-3.5" />
                    <span className="max-md:hidden">大纲</span>
                </Button>
            )}
            <Button
                variant="ghost"
                size="sm"
                onClick={handleExpandAll}
                disabled={!sessionId || !hasTurns}
                aria-label="全部展开"
                title="全部展开"
                data-testid="turn-expand-all"
            >
                <ChevronsUpDown className="h-3.5 w-3.5" />
                <span className="max-md:hidden">全部展开</span>
            </Button>
            <Button
                variant="ghost"
                size="sm"
                onClick={handleCollapseAll}
                disabled={!sessionId || !hasTurns}
                aria-label="全部折叠"
                title="全部折叠"
                data-testid="turn-collapse-all"
            >
                <ChevronsDownUp className="h-3.5 w-3.5" />
                <span className="max-md:hidden">全部折叠</span>
            </Button>
        </div>
    );
};

export default React.memo(TurnToolbar);
