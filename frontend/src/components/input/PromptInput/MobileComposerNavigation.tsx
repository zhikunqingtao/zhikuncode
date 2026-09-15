import { useState } from 'react';
import { X, Sun, Moon, Sparkles, Blocks, CircleHelp, ChevronRight } from 'lucide-react';
import { SheetShell } from '@/components/apos/MobileBottomSheet';
import { ModelChip, PermissionModeChip, MobileSelectionLabel } from './PromptComposerChips';
import { useTurnViewStore } from '@/store/turnViewStore';
import { DensitySwitch } from './DensitySwitch';
import { useDialogStore } from '@/store/dialogStore';
import { useWorkbenchViewStore } from '@/store/workbenchViewStore';
import { useConfigStore } from '@/store/configStore';

const action = 'min-h-11 rounded-[10px] px-1 text-sm text-t2 hover:bg-hover2 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent2-ink';
export function MobileComposerNavigation() {
    const [panel, setPanel] = useState<'more' | 'workbench' | 'density' | null>(null);
    const density = useTurnViewStore(s => s.density);
    const densityLabel = { compact: '简洁', balanced: '平衡', detailed: '详细' }[density];
    const open = panel !== null;
    const title = panel === 'workbench' ? '工作台' : panel === 'density' ? '消息密度' : '更多操作';
    const openDialog = useDialogStore(s => s.openDialog);
    const { enabled, viewMode, defaultView, setViewMode, setDefaultView } = useWorkbenchViewStore();
    const workbenchLabel = viewMode === 'simple' ? '简洁' : '开发';
    const { theme, setTheme } = useConfigStore();
    const dialog = (type: 'mcp' | 'keybindings') => { setPanel(null); openDialog(type); };
    return <>
        <nav aria-label="手机会话操作" className="mobile-composer-navigation flex shrink-0 items-center justify-between gap-0 overflow-x-auto px-2 pb-1">
            {enabled && <button className={action} aria-label={`工作台：${workbenchLabel}，点击切换`} aria-haspopup="dialog" aria-expanded={panel === 'workbench'} onClick={() => setPanel('workbench')}><MobileSelectionLabel label="工作台" value={workbenchLabel} /></button>}
            <button className={action} aria-label={`消息密度：${densityLabel}，点击切换`} aria-haspopup="dialog" aria-expanded={panel === 'density'} onClick={() => setPanel('density')}><MobileSelectionLabel label="密度" value={densityLabel} /></button>
            <PermissionModeChip mobile />
            <button className={action} aria-haspopup="dialog" aria-expanded={panel === 'more'} onClick={() => setPanel('more')}>更多</button>
        </nav>
        <SheetShell isOpen={open} onClose={() => setPanel(null)} ariaLabel={title} header={<div className="flex items-center justify-between px-4"><h2 className="text-xl font-semibold">{title}</h2><button className={action} aria-label={`关闭${title}`} onClick={() => setPanel(null)}><X size={20} /></button></div>}>
            <div className="space-y-4 p-4">
                {panel === 'workbench' && enabled && <section><div className="flex gap-2">{(['simple', 'development'] as const).map(mode => <button key={mode} className={action} aria-pressed={viewMode === mode} onClick={() => setViewMode(mode)}>{mode === 'simple' ? '简洁工作台' : '开发工作台'}{viewMode === mode ? ' ✓' : ''}</button>)}</div><button className={action} disabled={viewMode === defaultView} onClick={() => setDefaultView(viewMode)}>{viewMode === defaultView ? '已是默认工作台' : '设为默认工作台'}</button></section>}
                {panel === 'more' && <>
                    <section aria-label="模型选择" className="grid"><ModelChip mobile /></section>
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
                            { label: 'MCP 管理', icon: Blocks, run: () => dialog('mcp') },
                            { label: '帮助与快捷键', icon: CircleHelp, run: () => dialog('keybindings') },
                        ].map(({ label, icon: Icon, run }) => <button key={label} className="flex min-h-12 w-full items-center gap-3 px-3 py-3 text-left text-sm text-t1 hover:bg-hover2 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-accent2-ink" onClick={run}><Icon size={20} className="text-t2" aria-hidden="true" /><span className="flex-1">{label}</span><ChevronRight size={16} className="text-t3" aria-hidden="true" /></button>)}
                    </div>
                </>}
                {panel === 'density' && <DensitySwitch />}
            </div>
        </SheetShell>
    </>;
}
