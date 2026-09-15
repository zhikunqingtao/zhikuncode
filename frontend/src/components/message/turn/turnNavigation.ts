import type { Turn } from '@/store/selectors/turnProjection';
import { buildTurnTaskSections, splitTurnLayers } from '@/store/selectors/turnSections';
import { sectionExpandKey } from '@/store/turnViewStore';

export interface TurnNavigationEntry {
    key: string;
    title: string;
    turnIndex: number;
    expandKey?: string;
}

/** 有任务的轮使用任务锚点；无任务的轮保留轮次入口，混合历史也可导航。 */
export function buildTurnNavigation(turns: Turn[]): TurnNavigationEntry[] {
    let ordinal = 0;
    return turns.flatMap(turn => {
        if (turn.instruction) ordinal++;
        const { sections } = buildTurnTaskSections(splitTurnLayers(turn).process);
        if (sections.length) return sections.map(section => ({
            key: sectionExpandKey(turn.index, section.index),
            expandKey: sectionExpandKey(turn.index, section.index),
            title: section.title,
            turnIndex: turn.index,
        }));
        return [{ key: `turn-${turn.index}`, title: turn.instruction ? `第 ${ordinal} 轮` : '会话上下文', turnIndex: turn.index }];
    });
}
