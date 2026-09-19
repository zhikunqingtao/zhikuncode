import { useState } from 'react';
import { X, Sun, Moon, Sparkles, Blocks, CircleHelp, ChevronRight, Brain } from 'lucide-react';
import { SheetShell } from '@/components/apos/MobileBottomSheet';
import { ModelChip, PermissionModeChip, MobileChoice } from './PromptComposerChips';
import { useTurnViewStore, type TurnDensity } from '@/store/turnViewStore';
import { useSessionStore } from '@/store/sessionStore';
import { useDialogStore } from '@/store/dialogStore';
import { useConfigStore } from '@/store/configStore';

const action = 'min-h-11 rounded-[10px] px-1.5 text-sm text-t2 hover:bg-hover2 active:bg-hover2 transition-interactive duration-fast focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent2-ink';

/** 消息密度三档（描述沿用原 MobileDensitySwitch 文案；选项面板为上下列表） */
const DENSITY_OPTIONS: { value: TurnDensity; label: string; description: string }[] = [
    { value: 'compact', label: '简洁', description: '问题、过程与回复默认折叠' },
    { value: 'balanced', label: '平衡', description: '按任务查看执行摘要' },
    { value: 'detailed', label: '详细', description: '查看完整过程与任务导航' },
];

export function MobileComposerNavigation() {
    // 工作台切换已从前台隐藏（默认开发工作台），一级入口改为模型切换；
    // 紧凑裸排形态：顺序与桌面 composer-row 统一——状态（权限项前置）、权限、模型、密度；更多固定末尾。
    const [panel, setPanel] = useState<'more' | null>(null);
    const density = useTurnViewStore(s => s.density);
    const sessionId = useSessionStore(s => s.sessionId);
    const open = panel !== null;
    const openDialog = useDialogStore(s => s.openDialog);
    const { theme, setTheme } = useConfigStore();
    const dialog = (type: 'mcp' | 'keybindings' | 'memory') => { setPanel(null); openDialog(type); };
    return <>
        <nav aria-label="手机会话操作" className="mobile-composer-navigation flex shrink-0 items-center justify-between gap-0.5 overflow-x-auto px-2 pb-1">
            <PermissionModeChip mobile />
            <ModelChip mobile />
            <MobileChoice label="密度" showCurrent value={density} options={DENSITY_OPTIONS} onChange={value => useTurnViewStore.getState().setDensity(value as TurnDensity, sessionId ?? undefined)} />
            <button type="button" className={action} aria-haspopup="dialog" aria-expanded={open} onClick={() => setPanel('more')}>更多</button>
        </nav>
        <SheetShell isOpen={open} onClose={() => setPanel(null)} ariaLabel="更多操作" header={<div className="flex items-center justify-between px-4"><h2 className="text-xl font-semibold">更多操作</h2><button className={action} aria-label="关闭更多操作" onClick={() => setPanel(null)}><X size={20} /></button></div>}>
            <div className="space-y-4 p-4">
                {panel === 'more' && <>
                    <section>
                        <h3 className="mb-2 text-[13px] font-medium text-t2">外观</h3>
                        <div className="grid grid-cols-3 gap-2">{(['light', 'dark', 'glass'] as const).map((mode, i) => {
                            const Icon = [Sun, Moon, Sparkles][i];
                            const selected = theme.mode === mode;
                            return <button key={mode} className={`flex min-h-[72px] flex-col items-center justify-center gap-2 rounded-[14px] border text-sm transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent2-ink ${selected ? 'border-accent2-ink bg-accent2-soft text-accent2-ink' : 'border-hairline bg-surface2 text-t2 hover:bg-hover2'}`} aria-pressed={selected} onClick={() => setTheme({ mode })}><Icon size={20} aria-hidden="true" /><span>{['浅色', '深色', '液态玻璃'][i]}</span></button>;
                        })}</div>
                    </section>
                    <div className="overflow-hidden rounded-[14px] border border-hairline bg-surface2 divide-y divide-[var(--v2-border-hairline)]">
                        {[
                            { label: '记忆', icon: Brain, run: () => dialog('memory') },
                            { label: 'MCP 管理', icon: Blocks, run: () => dialog('mcp') },
                            { label: '帮助与快捷键', icon: CircleHelp, run: () => dialog('keybindings') },
                        ].map(({ label, icon: Icon, run }) => <button key={label} className="flex min-h-12 w-full items-center gap-3 px-3 py-3 text-left text-sm text-t1 hover:bg-hover2 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-accent2-ink" onClick={run}><Icon size={20} className="text-t2" aria-hidden="true" /><span className="flex-1">{label}</span><ChevronRight size={16} className="text-t3" aria-hidden="true" /></button>)}
                    </div>
                </>}
            </div>
        </SheetShell>
    </>;
}
