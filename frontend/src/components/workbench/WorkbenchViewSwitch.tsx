import { useId } from 'react';
import { GlassSelection } from '@/components/theme/GlassSelection';
import { Check, Code2, LayoutDashboard, Pin } from 'lucide-react';
import { useWorkbenchViewStore } from '@/store/workbenchViewStore';

export function WorkbenchViewSwitch() {
    const glassId = useId();
    const mode = useWorkbenchViewStore(state => state.viewMode);
    const defaultMode = useWorkbenchViewStore(state => state.defaultView);
    const setMode = useWorkbenchViewStore(state => state.setViewMode);
    const setDefaultMode = useWorkbenchViewStore(state => state.setDefaultView);

    return (
        <div className="flex items-center gap-1">
            <div
                className="glass-segments flex shrink-0 items-center rounded-lg border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-surface)] p-0.5"
                role="tablist"
                aria-label="工作台视图"
            >
                <button
                    type="button"
                    role="tab"
                    aria-selected={mode === 'simple'}
                    aria-label="简洁工作台"
                    title="简洁工作台"
                    onClick={() => setMode('simple')}
                    className={`panel-control flex items-center gap-1.5 rounded-md px-2 lg:px-2.5 py-1.5 text-[13px] font-medium transition-colors ${mode === 'simple' ? 'bg-blue-600 text-white' : 'text-[var(--v2-text-2)] hover:text-[var(--v2-text-1)]'}`}
                >
                    {mode === 'simple' && <GlassSelection id={glassId} />}
                    <LayoutDashboard className="h-3.5 w-3.5 shrink-0" />
                    <span className="hidden whitespace-nowrap lg:inline">简洁工作台</span>
                </button>
                <button
                    type="button"
                    role="tab"
                    aria-selected={mode === 'development'}
                    aria-label="开发工作台"
                    title="开发工作台"
                    onClick={() => setMode('development')}
                    className={`panel-control flex items-center gap-1.5 rounded-md px-2 lg:px-2.5 py-1.5 text-[13px] font-medium transition-colors ${mode === 'development' ? 'bg-blue-600 text-white' : 'text-[var(--v2-text-2)] hover:text-[var(--v2-text-1)]'}`}
                >
                    {mode === 'development' && <GlassSelection id={glassId} />}
                    <Code2 className="h-3.5 w-3.5 shrink-0" />
                    <span className="hidden whitespace-nowrap lg:inline">开发工作台</span>
                </button>
            </div>
            <button
                type="button"
                onClick={() => setDefaultMode(mode)}
                className="panel-control hidden md:inline-flex shrink-0 rounded-lg p-2 text-[var(--v2-text-2)] hover:bg-[var(--v2-bg-hover)] hover:text-[var(--v2-text-1)]"
                title={defaultMode === mode ? '当前视图已是本机默认' : '将当前视图设为本机默认'}
                aria-label={defaultMode === mode ? '当前视图已是本机默认' : '将当前视图设为本机默认'}
            >
                {defaultMode === mode
                    ? <Check className="h-3.5 w-3.5 text-ok" />
                    : <Pin className="h-3.5 w-3.5" />}
            </button>
        </div>
    );
}
