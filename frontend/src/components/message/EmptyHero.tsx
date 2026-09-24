/**
 * EmptyHero — §7.1 空态 Hero（记忆点①）
 *
 * 结构（ui EmptyState hero 变体承载）：
 * 1. 徽章：现有品牌 Logo + zhikuncode；
 * 2. 标题：今天想<b>构建</b>什么？（clamp(34px,5.4vw,50px)/300/-0.02em，<b>=650+accent，
 *    移动端固定 34px —— 经 [&_h1]:max-md 覆盖）；
 * 3. 副文案（text-t3 档、1.7 行高，EmptyState hero 内置）；
 * 4. 快捷 chips ×4：icon accent + text-t2，surface+hairline+shadow-e1，
 *    hover 上移 2px 升 e2；点击只「填入模板文本 + 聚焦输入框」，不提交
 *    （CustomEvent 桥，零跨组件耦合，见 services/promptTemplateFill）；
 * 5. kbd 提示条（ui Kbd，text-t4；仅 ≥768px 显示，手机触屏无快捷键意义）。
 * 移动端：标题固定 34px、上下留白减半（py-12 → py-6）。
 */

import { ChartNoAxesCombined, FileSpreadsheet, Gamepad2, TrainFront } from 'lucide-react';
import { BrandLogo } from '@/components/ui/BrandLogo';
import { Button, Chip, EmptyState, Kbd } from '@/components/ui';
import { dispatchPromptTemplateFill } from '@/services/promptTemplateFill';

interface QuickChip {
    label: string;
    icon: typeof Gamepad2;
    template: string;
}

const QUICK_CHIPS: QuickChip[] = [
    {
        label: '生成一个王者荣耀',
        icon: Gamepad2,
        template: '你可以开发一个类似王者荣耀的游戏么？我希望页面越逼真越好，帮我开发一个这样的游戏，跟王者荣耀越像越好，最终需要正常能玩没有明显功能 bug，你在开始动手前所有不确定的问题都要先跟我确认，给我我多个独立方案让我选择。但是在你开始写代码以后，就不要再问我了，都要自动选择能正常落地而且能尽量逼真效果的方案，不要选择注册网站单独购买等方案',
    },
    {
        label: '帮我做一个12306网站动画',
        icon: TrainFront,
        template: "帮我做一个动态html，可视化展示'你后补成功的那一刻，12306后台发送了什么'，要覆盖候补成功的12306后台完整流程，要展示各种数学原理和12306对应系统的架构，最关键的是一定要动态可视化，页面自己动，有很多惊艳漂亮的可视化动画",
    },
    {
        label: '我想投资黄金',
        icon: ChartNoAxesCombined,
        template: '我想做黄金投资，但是不太懂怎么具体操作，也不知道该怎么监控行情，你能帮我每日可以随时跟踪国际国内市场的金价以及银行的积存金的价格。同时监控国家对黄金回购的频率及整体金额趋势。你不是很确定的要先跟我沟通确认',
    },
    {
        label: '帮我做一个宇树科技Excel',
        icon: FileSpreadsheet,
        template: '请以2026年8月30日为资料截止时间，优先使用上海证券交易所披露的招股说明书、上市公告和宇树科技官方公开资料，对宇树科技的经营与财务表现进行分析，并生成一份可编辑的Excel分析底稿。请至少保留资料来源、原始数据、核心计算、趋势图表和分析结论；统一单位与报告期口径，不得把预测数据写成已经发生的事实。除文件外，请简要说明你的分析步骤和仍需人工核验的内容。',
    },
];

export function EmptyHero() {
    return (
        <EmptyState
            variant="hero"
            className="min-h-full max-md:py-6 [&_h1]:max-md:text-[34px] [&>p]:text-t2"
            badge={
                <Chip variant="accent" className="h-7 px-3">
                    <BrandLogo className="h-5 w-5" />
                    zhikuncode
                </Chip>
            }
            title={<>今天想<b>构建</b>什么？</>}
            description="下达指令，剩下的一切交给zhikuncode"
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
                    <div className="mt-3 hidden basis-full flex-wrap items-center justify-center gap-x-3 gap-y-1.5 text-[13px] text-t2 md:flex">
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
