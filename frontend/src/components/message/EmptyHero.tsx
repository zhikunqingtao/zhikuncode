/**
 * EmptyHero — §7.1 空态 Hero（记忆点①）
 *
 * 结构（ui EmptyState hero 变体承载）：
 * 1. 徽章：accent-soft 胶囊 + 呼吸点 +「APOS · N 个智能体已就绪」
 *    —— N 取真实在线数，无数据源时不显数量（前端现无智能体注册表接口，
 *    App 不传 readyAgentCount，即落「APOS · 智能体已就绪」）；
 * 2. 标题：今天想<b>构建</b>什么？（clamp(34px,5.4vw,50px)/300/-0.02em，<b>=650+accent，
 *    移动端固定 34px —— 经 [&_h1]:max-md 覆盖）；
 * 3. 副文案（text-t3 档、1.7 行高，EmptyState hero 内置）；
 * 4. 快捷 chips ×4：icon accent + text-t2，surface+hairline+shadow-e1，
 *    hover 上移 2px 升 e2；点击只「填入模板文本 + 聚焦输入框」，不提交
 *    （CustomEvent 桥，零跨组件耦合，见 services/promptTemplateFill）；
 * 5. kbd 提示条（ui Kbd，text-t4）。
 * 移动端：标题固定 34px、上下留白减半（py-12 → py-6）。
 */

import { FileText, LayoutTemplate, ShieldCheck, Wrench } from 'lucide-react';
import { Button, Chip, EmptyState, Kbd } from '@/components/ui';
import { dispatchPromptTemplateFill } from '@/services/promptTemplateFill';

interface QuickChip {
    label: string;
    icon: typeof LayoutTemplate;
    template: string;
}

const QUICK_CHIPS: QuickChip[] = [
    {
        label: '新建落地页',
        icon: LayoutTemplate,
        template: '帮我新建一个产品落地页：包含 Hero、核心特性与定价区块，输出可直接预览的页面。',
    },
    {
        label: '修复 CI 失败',
        icon: Wrench,
        template: '帮我修复 CI 失败：先定位失败的检查与原因，再给出最小化修复并验证。',
    },
    {
        label: '重构认证模块',
        icon: ShieldCheck,
        template: '帮我重构认证模块：梳理职责边界、拆分可测试单元，保持对外行为不变。',
    },
    {
        label: '生成 API 文档',
        icon: FileText,
        template: '帮我生成 API 文档：扫描接口与类型定义，输出结构化的 Markdown 文档。',
    },
];

export function EmptyHero({ readyAgentCount }: { readyAgentCount?: number }) {
    return (
        <EmptyState
            variant="hero"
            className="min-h-full max-md:py-6 [&_h1]:max-md:text-[34px] [&>p]:text-t2"
            badge={
                <Chip variant="accent" className="h-7 px-3">
                    <span
                        className="h-1.5 w-1.5 rounded-full bg-accent2 animate-accent-pulse"
                        aria-hidden="true"
                    />
                    {readyAgentCount != null
                        ? `APOS · ${readyAgentCount} 个智能体已就绪`
                        : 'APOS · 智能体已就绪'}
                </Chip>
            }
            title={<>今天想<b>构建</b>什么？</>}
            description="描述目标，剩下的交给智能体。代码、测试、文档，一站完成。"
            actions={
                <>
                    {QUICK_CHIPS.map((chip) => (
                        <Button
                            key={chip.label}
                            variant="secondary"
                            size="sm"
                            className="text-t2 hover:-translate-y-0.5"
                            onClick={() => dispatchPromptTemplateFill(chip.template)}
                        >
                            <chip.icon className="h-3.5 w-3.5 text-accent2-ink" aria-hidden="true" />
                            {chip.label}
                        </Button>
                    ))}
                    <div className="mt-3 flex basis-full flex-wrap items-center justify-center gap-x-3 gap-y-1.5 text-[13px] text-t2">
                        <span className="flex items-center gap-1">
                            <Kbd>⌘</Kbd><Kbd>K</Kbd> 命令面板
                        </span>
                        <span aria-hidden="true">·</span>
                        <span className="flex items-center gap-1">
                            <Kbd>⌘</Kbd><Kbd>⏎</Kbd> 发送
                        </span>
                        <span aria-hidden="true">·</span>
                        <span className="flex items-center gap-1">
                            <Kbd>Esc</Kbd> 中断任务
                        </span>
                    </div>
                </>
            }
        />
    );
}
