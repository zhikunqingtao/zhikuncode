/**
 * §7.1 空态快捷 chips → 输入框模板填充事件桥（零跨组件耦合）
 *
 * 空态 Hero 的快捷 chips 只做「填入模板文本 + 聚焦输入框」，不自动提交。
 * EmptyHero 与 PromptInput 不互引：前者 dispatch CustomEvent，
 * 后者（usePromptState）监听并写入草稿，两侧均可独立存在。
 */

export const PROMPT_TEMPLATE_FILL_EVENT = 'zhikun:prompt-template-fill';

export interface PromptTemplateFillDetail {
    text: string;
}

export function dispatchPromptTemplateFill(text: string): void {
    window.dispatchEvent(
        new CustomEvent<PromptTemplateFillDetail>(PROMPT_TEMPLATE_FILL_EVENT, {
            detail: { text },
        }),
    );
}
