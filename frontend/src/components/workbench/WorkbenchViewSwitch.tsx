import { Check, Code2, LayoutDashboard, Pin } from 'lucide-react';
import { useWorkbenchViewStore } from '@/store/workbenchViewStore';

export function WorkbenchViewSwitch() {
    const mode = useWorkbenchViewStore(state => state.viewMode);
    const defaultMode = useWorkbenchViewStore(state => state.defaultView);
    const setMode = useWorkbenchViewStore(state => state.setViewMode);
    const setDefaultMode = useWorkbenchViewStore(state => state.setDefaultView);

    return (
        <div className="flex items-center gap-1">
            <div
                className="flex items-center rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] p-0.5"
                role="tablist"
                aria-label="工作台视图"
            >
                <button
                    type="button"
                    role="tab"
                    aria-selected={mode === 'simple'}
                    aria-label="简洁工作台"
                    onClick={() => setMode('simple')}
                    className={`flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-xs font-medium transition-colors ${mode === 'simple' ? 'bg-blue-600 text-white' : 'text-[var(--text-secondary)] hover:text-[var(--text-primary)]'}`}
                >
                    <LayoutDashboard className="h-3.5 w-3.5" />
                    <span className="hidden sm:inline">简洁工作台</span>
                </button>
                <button
                    type="button"
                    role="tab"
                    aria-selected={mode === 'development'}
                    aria-label="开发工作台"
                    onClick={() => setMode('development')}
                    className={`flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-xs font-medium transition-colors ${mode === 'development' ? 'bg-blue-600 text-white' : 'text-[var(--text-secondary)] hover:text-[var(--text-primary)]'}`}
                >
                    <Code2 className="h-3.5 w-3.5" />
                    <span className="hidden sm:inline">开发工作台</span>
                </button>
            </div>
            <button
                type="button"
                onClick={() => setDefaultMode(mode)}
                className="rounded-lg p-2 text-[var(--text-secondary)] hover:bg-[var(--bg-hover)] hover:text-[var(--text-primary)]"
                title={defaultMode === mode ? '当前视图已是本机默认' : '将当前视图设为本机默认'}
                aria-label={defaultMode === mode ? '当前视图已是本机默认' : '将当前视图设为本机默认'}
            >
                {defaultMode === mode
                    ? <Check className="h-3.5 w-3.5 text-green-500" />
                    : <Pin className="h-3.5 w-3.5" />}
            </button>
        </div>
    );
}
