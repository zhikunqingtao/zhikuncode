/**
 * /design 画廊（指南 §6.4）：DEV 门控、零架构侵入。
 * 内容：主题切换（light/dark/glass）+ 强调色 6 色 + 12 基元 × 关键状态 + 令牌速览。
 * 仅开发环境经 main.tsx 懒加载进入；生产构建静态消除，不进 dist。
 */
import { useEffect, useState, type ReactNode } from 'react';
import {
    AlertTriangle,
    Check,
    Inbox,
    Plus,
    Search,
    Send,
    Sparkles,
    Zap,
} from 'lucide-react';
import {
    Button,
    Card,
    Chip,
    Dialog,
    EmptyState,
    Input,
    Kbd,
    Progress,
    Spinner,
    Tabs,
    Textarea,
    Toggle,
} from '@/components/ui';
import { ACCENT_PRESETS, applyAccent, DEFAULT_ACCENT_HEX } from '@/theme/accents';

/* ===== 主题与强调色（值取自指南 §3.4 终值表，与 ThemePicker 共用 @/theme/accents 同一写入机制） ===== */

type ThemeName = 'light' | 'dark' | 'glass';
const THEMES: { name: ThemeName; label: string }[] = [
    { name: 'light', label: 'Light' },
    { name: 'dark', label: 'Dark' },
    { name: 'glass', label: 'Glass' },
];

function applyTheme(theme: ThemeName) {
    const root = document.documentElement;
    root.classList.remove('light', 'dark', 'glass');
    root.classList.add(theme);
}

/* ===== 布局小件 ===== */

function Section({ title, children }: { title: string; children: ReactNode }) {
    return (
        <section className="mb-10">
            <h2 className="mb-4 text-[11px] font-semibold uppercase tracking-[0.08em] text-t2">
                {title}
            </h2>
            {children}
        </section>
    );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
    return (
        <div className="mb-3 flex flex-wrap items-center gap-3">
            <span className="w-28 shrink-0 text-xs text-t2">{label}</span>
            {children}
        </div>
    );
}

function Swatch({ cls, name }: { cls: string; name: string }) {
    return (
        <div className="flex w-24 flex-col gap-1">
            <div className={`h-10 rounded-lg border border-hairline ${cls}`} />
            <span className="truncate font-mono text-[11px] text-t2">{name}</span>
        </div>
    );
}

/* ===== 画廊主体 ===== */

export default function DesignGallery() {
    const [theme, setTheme] = useState<ThemeName>('light');
    const [accentHex, setAccentHex] = useState<string>(DEFAULT_ACCENT_HEX);
    const [dialogOpen, setDialogOpen] = useState(false);
    const [toggleOn, setToggleOn] = useState(true);

    useEffect(() => applyTheme(theme), [theme]);
    // glass 按 resolveTheme 语义归一为 light；dark 档写 dark soft/ring
    useEffect(() => applyAccent(accentHex, theme === 'dark' ? 'dark' : 'light'), [accentHex, theme]);

    return (
        <div data-design-gallery className="min-h-screen bg-app2 text-t1">
            {/* 顶栏：主题 + 强调色 */}
            <header className="sticky top-0 z-10 border-b border-hairline bg-surfacev2">
                <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-4 px-6 py-3">
                    <div className="flex items-center gap-2">
                        <span className="flex h-6 w-6 items-center justify-center rounded-md bg-accent2 text-white">
                            <Zap className="h-3.5 w-3.5" aria-hidden="true" />
                        </span>
                        <span className="text-sm font-semibold">zhikuncode /design 画廊</span>
                        <Chip variant="accent">P1a · DEV only</Chip>
                    </div>
                    <div className="ml-auto flex flex-wrap items-center gap-4">
                        <div className="inline-flex items-center gap-1 rounded-2xl bg-sunken2 p-1 shadow-well">
                            {THEMES.map((t) => (
                                <button
                                    key={t.name}
                                    type="button"
                                    onClick={() => setTheme(t.name)}
                                    aria-pressed={theme === t.name}
                                    className={`h-7 rounded-xl px-3 text-xs font-medium transition-interactive duration-fast focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring ${
                                        theme === t.name
                                            ? 'bg-surfacev2 text-t1 shadow-e1'
                                            : 'text-t2 hover:text-t1'
                                    }`}
                                >
                                    {t.label}
                                </button>
                            ))}
                        </div>
                        <div className="flex items-center gap-1.5" role="radiogroup" aria-label="强调色">
                            {ACCENT_PRESETS.map((a) => (
                                <button
                                    key={a.hex}
                                    type="button"
                                    role="radio"
                                    aria-checked={accentHex === a.hex}
                                    title={a.label}
                                    onClick={() => setAccentHex(a.hex)}
                                    className="flex h-6 w-6 items-center justify-center rounded-full transition-interactive duration-fast focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring"
                                    style={{ backgroundColor: a.hex }}
                                >
                                    {accentHex === a.hex && (
                                        <Check className="h-3.5 w-3.5 text-white" aria-hidden="true" />
                                    )}
                                </button>
                            ))}
                        </div>
                    </div>
                </div>
            </header>

            <main className="mx-auto max-w-5xl px-6 py-8">
                {/* ===== 令牌速览 ===== */}
                <Section title="令牌 · 色板 Tokens / Colors">
                    <div className="flex flex-wrap gap-3">
                        <Swatch cls="bg-app2" name="app2" />
                        <Swatch cls="bg-surfacev2" name="surfacev2" />
                        <Swatch cls="bg-surface2" name="surface2" />
                        <Swatch cls="bg-sunken2" name="sunken2" />
                        <Swatch cls="bg-hover2" name="hover2" />
                        <Swatch cls="bg-active2" name="active2" />
                        <Swatch cls="bg-accent2" name="accent2" />
                        <Swatch cls="bg-accent2-strong" name="accent2-strong" />
                        <Swatch cls="bg-accent2-soft" name="accent2-soft" />
                        <Swatch cls="bg-ok" name="ok" />
                        <Swatch cls="bg-oksoft" name="oksoft" />
                        <Swatch cls="bg-warn" name="warn" />
                        <Swatch cls="bg-warnsoft" name="warnsoft" />
                        <Swatch cls="bg-err" name="err" />
                        <Swatch cls="bg-errsoft" name="errsoft" />
                    </div>
                    <div className="mt-4 flex flex-wrap gap-5">
                        {([
                            ['--v2-text-1', 'text-t1 正文标题'],
                            ['--v2-text-2', 'text-t2 次级'],
                            ['--v2-text-3', 'text-t3 辅助（实测 4.67:1，过 WCAG AA 4.5:1）'],
                            ['--v2-text-4', 'text-t4 占位/装饰'],
                        ] as const).map(([v, label]) => (
                            <span key={v} className="flex items-center gap-2 text-xs text-t2">
                                <i aria-hidden="true" className="h-4 w-4 rounded-full border border-hairline" style={{ background: `var(${v})` }} />
                                {label}
                            </span>
                        ))}
                    </div>
                </Section>

                <Section title="令牌 · 阴影 / 圆角 / 字阶">
                    <div className="flex flex-wrap gap-4">
                        {(['shadow-e1', 'shadow-e2', 'shadow-e3', 'shadow-e4', 'shadow-well', 'shadow-soft', 'shadow-soft-sm'] as const).map((s) => (
                            <div key={s} className="flex w-24 flex-col gap-1">
                                <div className={`h-12 rounded-xl bg-surfacev2 ${s}`} />
                                <span className="font-mono text-[11px] text-t2">{s}</span>
                            </div>
                        ))}
                    </div>
                    <div className="mt-4 flex flex-wrap items-end gap-4">
                        <div className="h-12 w-12 rounded-md bg-accent2-soft" title="rounded-md 6" />
                        <div className="h-12 w-12 rounded-lg bg-accent2-soft" title="rounded-lg 8" />
                        <div className="h-12 w-12 rounded-xl bg-accent2-soft" title="rounded-xl 12" />
                        <div className="h-12 w-12 rounded-2xl bg-accent2-soft" title="rounded-2xl 16" />
                        <div className="h-12 w-12 rounded-panel bg-accent2-soft" title="rounded-panel 20" />
                        <div className="h-12 w-20 rounded-full bg-accent2-soft" title="rounded-full" />
                    </div>
                    <div className="mt-6 space-y-2">
                        <p className="text-[clamp(34px,5.4vw,50px)] font-light leading-[1.15] tracking-[-0.02em]">
                            Hero <b>300</b> 细字重
                        </p>
                        <p className="text-xl font-semibold">Title-1 · 20px / 650</p>
                        <p className="text-base font-semibold">Title-2 · 16px / 600</p>
                        <p className="text-sm leading-relaxed text-t2">Body · 14px / 1.6–1.75 正文样例</p>
                        <p className="text-xs text-t2">Aux · 12px 辅助说明（字号下限）</p>
                        <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-t2">
                            Label · 11px 大写分区
                        </p>
                        <p className="text-[32px] font-semibold tabular-nums tracking-[-0.02em]">
                            128,456.00
                        </p>
                    </div>
                </Section>

                {/* ===== 12 基元 ===== */}
                <Section title="01 · Button">
                    <Row label="variant">
                        <Button variant="primary">Primary</Button>
                        <Button variant="secondary">Secondary</Button>
                        <Button variant="ghost">Ghost</Button>
                        <Button variant="danger">Danger</Button>
                    </Row>
                    <Row label="size">
                        <Button size="sm">Small</Button>
                        <Button size="md">Medium</Button>
                        <Button size="lg">Large</Button>
                        <Button size="md" iconOnly aria-label="添加">
                            <Plus className="h-4 w-4" aria-hidden="true" />
                        </Button>
                    </Row>
                    <Row label="states">
                        <Button variant="primary" loading>
                            提交中
                        </Button>
                        <Button variant="secondary" disabled>
                            Disabled
                        </Button>
                        <Button variant="primary">
                            <Send className="h-4 w-4" aria-hidden="true" />
                            带图标
                        </Button>
                    </Row>
                </Section>

                <Section title="02 · Card">
                    <div className="grid gap-4 md:grid-cols-3">
                        <Card className="p-4">
                            <p className="text-base font-semibold">静息卡片</p>
                            <p className="mt-1 text-xs text-t2">surfacev2 · e2 · hairline</p>
                        </Card>
                        <Card interactive className="p-4">
                            <p className="text-base font-semibold">可点卡片</p>
                            <p className="mt-1 text-xs text-t2">hover 升 shadow-e3</p>
                        </Card>
                        <Card selected className="p-4">
                            <p className="text-base font-semibold">选中卡片</p>
                            <p className="mt-1 text-xs text-t2">accent2-soft + 2px 内嵌条</p>
                        </Card>
                    </div>
                </Section>

                <Section title="03 · Input / 04 · Textarea">
                    <div className="grid max-w-xl gap-3">
                        <Input placeholder="默认输入框（sunken 井 + 3px focus ring）" />
                        <Input error defaultValue="错误态：err 边 + err ring" aria-label="错误示例" />
                        <Input disabled placeholder="禁用态" />
                        <Textarea placeholder="多行输入 Textarea…" />
                        <Textarea error defaultValue="Textarea 错误态" aria-label="错误多行示例" />
                    </div>
                </Section>

                <Section title="05 · Chip">
                    <Row label="variant">
                        <Chip variant="accent">accent</Chip>
                        <Chip variant="ok">
                            <Check className="h-3 w-3" aria-hidden="true" /> ok
                        </Chip>
                        <Chip variant="warn">
                            <AlertTriangle className="h-3 w-3" aria-hidden="true" /> warn
                        </Chip>
                        <Chip variant="err">err</Chip>
                        <Chip variant="neutral">neutral</Chip>
                    </Row>
                    <Row label="selected">
                        <Chip selected>实底白字</Chip>
                        <Chip variant="accent">对比 soft</Chip>
                    </Row>
                </Section>

                <Section title="06 · Toggle">
                    <Row label="controlled">
                        <Toggle checked={toggleOn} onCheckedChange={setToggleOn} aria-label="受控开关" />
                        <span className="text-xs tabular-nums text-t2">{toggleOn ? 'ON' : 'OFF'}</span>
                    </Row>
                    <Row label="states">
                        <Toggle defaultChecked={false} aria-label="默认关" />
                        <Toggle defaultChecked aria-label="默认开" />
                        <Toggle disabled aria-label="禁用" />
                        <Toggle disabled defaultChecked aria-label="禁用开" />
                    </Row>
                </Section>

                <Section title="07 · Progress">
                    <div className="grid max-w-xl gap-3">
                        <Progress value={3} max={10} start={3} end={10} aria-label="核验进度" />
                        <Progress value={65} max={100} aria-label="交付进度 65%" />
                        <Progress indeterminate aria-label="不定进度" />
                    </div>
                </Section>

                <Section title="08 · Tabs">
                    <Tabs
                        items={[
                            { value: 'overview', label: '概览', content: <p className="text-sm text-t2">方向键 ←/→ 切换，Home/End 首尾。</p> },
                            { value: 'files', label: '文件', content: <p className="text-sm text-t2">文件面板内容。</p> },
                            { value: 'disabled', label: '禁用', disabled: true, content: null },
                            { value: 'logs', label: '日志', content: <p className="text-sm text-t2">日志面板内容。</p> },
                        ]}
                    />
                </Section>

                <Section title="09 · Dialog">
                    <Button variant="primary" onClick={() => setDialogOpen(true)}>
                        打开对话框
                    </Button>
                    <Dialog
                        open={dialogOpen}
                        onOpenChange={setDialogOpen}
                        title="焦点归还链演示"
                        className="p-5"
                    >
                        <p className="px-5 text-sm leading-relaxed text-t2">
                            Esc / 遮罩点击 / 右上角关闭按钮走同一 close handler；关闭后焦点归还触发按钮。
                        </p>
                        <div className="flex justify-end gap-2 px-5 pb-5 pt-4">
                            <Button variant="ghost" onClick={() => setDialogOpen(false)}>
                                取消
                            </Button>
                            <Button variant="primary" onClick={() => setDialogOpen(false)}>
                                确认
                            </Button>
                        </div>
                    </Dialog>
                </Section>

                <Section title="10 · Kbd">
                    <Row label="shortcuts">
                        <span className="flex items-center gap-1.5 text-sm text-t2">
                            <Kbd>⌘</Kbd>
                            <Kbd>K</Kbd> 命令面板
                        </span>
                        <span className="flex items-center gap-1.5 text-sm text-t2">
                            <Kbd>⌘</Kbd>
                            <Kbd>⏎</Kbd> 发送
                        </span>
                        <span className="flex items-center gap-1.5 text-sm text-t2">
                            <Kbd>Esc</Kbd> 中断
                        </span>
                    </Row>
                </Section>

                <Section title="11 · EmptyState">
                    <Card className="mb-4">
                        <EmptyState
                            variant="hero"
                            badge={
                                <Chip variant="accent" className="h-7 px-3">
                                    <span className="h-1.5 w-1.5 rounded-full bg-accent2 animate-accent-pulse" aria-hidden="true" />
                                    APOS · 智能体已就绪
                                </Chip>
                            }
                            title={
                                <>
                                    今天想<b>构建</b>什么？
                                </>
                            }
                            description="描述目标，剩下的交给智能体。代码、测试、文档，一站完成。"
                            actions={
                                <>
                                    <Button variant="secondary" size="sm">
                                        <Sparkles className="h-3.5 w-3.5 text-accent2" aria-hidden="true" />
                                        新建落地页
                                    </Button>
                                    <Button variant="secondary" size="sm">
                                        修复 CI 失败
                                    </Button>
                                    <Button variant="secondary" size="sm">
                                        生成 API 文档
                                    </Button>
                                </>
                            }
                        />
                    </Card>
                    <Card>
                        <EmptyState
                            variant="compact"
                            icon={<Inbox aria-hidden="true" />}
                            title="暂无交付物"
                            description="任务完成后，产出的文件会显示在这里。"
                        />
                    </Card>
                </Section>

                <Section title="12 · Spinner">
                    <Row label="sizes">
                        <Spinner size="sm" />
                        <Spinner size="md" />
                        <Spinner size="lg" />
                        <span className="flex items-center gap-2 text-sm text-t2">
                            <Spinner size="sm" /> 运行中 · 00:42
                        </span>
                    </Row>
                </Section>

                <Section title="辅助 · 搜索输入示例">
                    <div className="relative max-w-xs">
                        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-t4" aria-hidden="true" />
                        <Input className="pl-9" placeholder="搜索会话…" />
                    </div>
                </Section>
            </main>
        </div>
    );
}
