import { pageSessionOrder } from '@/utils/pageSessionOrder';
import { selectMergeSourceIds, useSessionMergeStore } from '@/store/sessionMergeStore';
import { GlassMaterial } from '@/components/theme/GlassMaterial';
import { LayoutGroup, motion, useReducedMotion } from 'framer-motion';
/**
 * Sidebar — 左侧边栏组件
 * SPEC: §8.6.2
 *
 * 包含: SessionList, TaskPanel, FileTracker
 */

import { useState, useCallback, useEffect, useMemo, useRef } from 'react';
import { createPortal } from 'react-dom';
import { 
    MessageSquare, 
    CheckCircle2, 
    XCircle, 
    Loader2, 
    FolderTree,
    Folder,
    PanelLeftClose,
    PanelLeftOpen,
    ChevronDown,
    ChevronLeft,
    ChevronRight,
    Trash2,
    Plus,
    Clock,
    ArrowDownUp,
    GitBranch,
    GitMerge,
    GitCommitHorizontal,
    BarChart3,
    FileText,
    ExternalLink,
    Workflow,
    Network,
    Activity
    ,Search
    ,X
    ,CircleDashed
} from 'lucide-react';
import { Chip, Kbd } from '@/components/ui';
import { APISequenceDiagram } from '@/components/visualization/backend/APISequenceDiagram';
import { FileTreePanel } from '@/components/layout/FileTreePanel';
import { AgentDAGChart } from '@/components/visualization/shared/AgentDAGChart';
import { GitTimeline } from '@/components/visualization/shared/GitTimeline';
import { CodeComplexityTreemap } from '@/components/visualization/backend/CodeComplexityTreemap';
import { ChangeImpactGraph } from '@/components/visualization/backend/ChangeImpactGraph';
import { APIContractViewer } from '@/components/visualization/backend/APIContractViewer';
import { CodeDiagramGenerator } from '@/components/visualization/backend/CodeDiagramGenerator';
import { CodePathTracer } from '@/components/visualization/backend/CodePathTracer';
import { useApiContractStore } from '@/store/apiContractStore';
import { useTaskStore } from '@/store/taskStore';
import { useMessageStore } from '@/store/messageStore';
import { useSessionStore } from '@/store/sessionStore';
import { useNotificationStore } from '@/store/notificationStore';
import { useAppUiStore } from '@/store/appUiStore';
import { useFeatureFlagStore } from '@/store/featureFlagStore';
import { ActivityStream } from '@/components/apos/ActivityStream';
import { FeatureFlagPanel } from '@/components/apos/FeatureFlagPanel';
import { SessionFileExplorer } from '@/components/apos/SessionFileExplorer';
import { dispatchNewAuthorizedSessionRequest } from '@/services/authorizedSession';
import { activateSessionCandidate } from '@/services/sessionActivation';
import { generateUUID } from '@/utils/uuid';
import type { TaskState } from '@/types';
import { useWorkbenchViewStore } from '@/store/workbenchViewStore';
import { taskTitle } from '@/utils/workbenchPresentation';
import { normalizeThemeMode, useConfigStore } from '@/store/configStore';
import { useViewportWidth } from '@/hooks/useResponsive';
import { groupSessionsByDirectory, isSessionGenerating, type SessionSummary } from '@/utils/sessionGroups';

export type TabType = 'sessions' | 'tasks' | 'files' | 'sequence' | 'dag' | 'git' | 'complexity' | 'impact' | 'api-docs' | 'diagram' | 'code-path' | 'apos';

/** §7.5 移动端抽屉文字列表 / 主区移动面板的 Tab 中文标签 */
export const SIDEBAR_TAB_LABELS: Record<TabType, string> = {
    sessions: '会话',
    tasks: '任务',
    files: '文件',
    sequence: '序列图',
    dag: 'DAG',
    git: 'Git',
    complexity: '复杂度',
    impact: '影响分析',
    'api-docs': 'API文档',
    diagram: '图表生成',
    'code-path': '代码路径',
    apos: 'Activity',
};

// ═══ Sidebar 宽度配置 ═══
const MIN_WIDTH = 256;
const MAX_WIDTH = 800;
const DEFAULT_WIDTH = 320;
const STORAGE_KEY = 'sidebar-width';
/** 桌面端收起后侧栏保留的固定展开条宽度（图标 + 竖排文字，整条可点击恢复） */
const COLLAPSED_STRIP_WIDTH = 36;

// ═══ §7.5 桌面面板配方常量（移动抽屉文字列表路径不消费） ═══
/** 面板 Label：12px/500/大写/tracking .06em（视觉换肤 §1.6 小标签）。
 *  颜色取 text-t3：t3 终值 #596A60 / #8B99AD 在 surface-2 上对比度 ≥4.5，满足 AA 红线。 */
const PANEL_LABEL_CLASS = 'text-[12px] font-medium uppercase tracking-[.06em] text-t3';

/** 「新建」按钮：主操作实心 accent-strong + 白字（≥4.5:1，与公共 Button primary 一致）。
 *  注意不可用 bg-accent2：--v2-accent 是表面强调色（dark 档刻意取亮如 #7FD4E8），压白字不达标；
 *  strong 档三主题复用 light 深值，专为白字实心底设计（accents.ts §3.4）。 */
const PANEL_NEW_BUTTON_CLASS =
    'w-full flex items-center justify-center gap-2 px-3 py-2 rounded-xl ' +
    'bg-accent2-strong text-white text-sm font-medium shadow-e2 ' +
    'transition-interactive duration-fast hover:bg-accent2-hover active:bg-accent2-active active:scale-[0.98] ' +
    'focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring';

/** 会话卡：surface 底浮在 app 底上（三主题自动适配），hover 泛 accent 微光 + 浮起阴影。
 *  选中视觉不再改卡片本身，由卡内 .session-active-pill 独立层承担（globals.css）。 */
const SESSION_CARD_CLASS =
    'session-card group relative px-3 py-2.5 rounded-[14px] cursor-pointer border border-hairline ' +
    'bg-surfacev2 shadow-e1 transition-interactive duration-fast ' +
    'hover:shadow-raised hover:border-accent2-ring';

/** §7.5 面板头：Label（大写）+ 计数 chip */
function PanelHeader({ label, count, onCollapse, onBack }: { label: string; count?: number; onCollapse?: () => void; onBack?: () => void }) {
    return (
        <div className={`flex items-center justify-between gap-2 flex-shrink-0 ${onBack ? 'min-h-11 px-1 pb-2' : 'px-3 pt-3 pb-2'}`}>
            <div className="flex items-center gap-2 min-w-0">
                {onBack && (
                    <button type="button" onClick={onBack} aria-label="返回"
                        className="panel-control min-w-[44px] min-h-[44px] flex items-center justify-center rounded-xl text-t2 hover:bg-hover2 transition-colors duration-fast">
                        <ChevronLeft className="w-5 h-5" aria-hidden="true" />
                    </button>
                )}
                <span className={PANEL_LABEL_CLASS}>{label}</span>
                {count !== undefined && <Chip variant="accent" className="tabular-nums">{count}</Chip>}
            </div>
            {onCollapse && (
                <button
                    type="button"
                    onClick={onCollapse}
                    aria-label="收起整个对话列表"
                    title="收起整个对话列表"
                    className="panel-control inline-flex items-center gap-1.5 shrink-0 rounded-[10px] px-2 py-1 text-[13px] text-t2
                        hover:bg-hover2 hover:text-t1 focus-visible:outline-none
                        focus-visible:ring-[3px] focus-visible:ring-accent2-ring"
                >
                    <PanelLeftClose className="w-4 h-4" aria-hidden="true" />
                    收起列表
                </button>
            )}
        </div>
    );
}

/** §7.5 面板搜索框：bg-sunken2 + shadow-well + rounded-xl；
 *  右侧 ⌘K Kbd 为装饰性提示（aria-hidden），输入时切换为清除钮。 */
function PanelSearchBox({ value, onChange, placeholder, ariaLabel }: {
    value: string;
    onChange: (next: string) => void;
    placeholder: string;
    ariaLabel: string;
}) {
    return (
        <div className="relative">
            <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-t3 pointer-events-none" />
            <input
                type="text"
                value={value}
                onChange={(e) => onChange(e.target.value)}
                placeholder={placeholder}
                aria-label={ariaLabel}
                className="w-full h-8 pl-8 pr-8 rounded-xl bg-sunken2 shadow-well border border-transparent
                    text-sm text-t1 placeholder:text-t4 transition-surface duration-fast
                    focus:outline-none focus:ring-[3px] focus:ring-accent2-ring"
            />
            {value ? (
                <button
                    onClick={() => onChange('')}
                    aria-label="清除搜索"
                    className="panel-control absolute right-1.5 top-1/2 -translate-y-1/2 p-0.5 rounded-md
                        text-t3 hover:bg-hover2 hover:text-t1 transition-interactive duration-fast"
                >
                    <X className="w-3.5 h-3.5" />
                </button>
            ) : (
                <Kbd aria-hidden="true" className="absolute right-1.5 top-1/2 -translate-y-1/2 pointer-events-none">⌘K</Kbd>
            )}
        </div>
    );
}

export interface SidebarProps {
    className?: string;
    /** 是否在 Drawer 模式下（移动端），渲染 §7.5 文字列表且不显示拖拽手柄 */
    isDrawerMode?: boolean;
    /** 独立窗口模式下的默认 Tab */
    defaultTab?: string;
    /** Drawer 模式下选中 Tab 后的回调（用于自动关闭抽屉） */
    onNavigate?: () => void;
}

export function Sidebar({ className = '', isDrawerMode = false, defaultTab, onNavigate }: SidebarProps) {
    const [activeTab, setActiveTab] = useState<TabType>(() => {
        if (defaultTab && ['sessions','tasks','files','sequence','dag','git','complexity','impact','api-docs','diagram','code-path','apos'].includes(defaultTab)) {
            return defaultTab as TabType;
        }
        return 'sessions';
    });
    const themeMode = useConfigStore(s => s.theme.mode);
    const setTheme = useConfigStore(s => s.setTheme);
    const glassMode = themeMode === 'glass';
    const reducedMotion = useReducedMotion();
    const [panelCollapsed, setPanelCollapsed] = useState(() => localStorage.getItem('sidebar-panel-collapsed') === 'true');
    useEffect(() => {
        localStorage.setItem('sidebar-panel-collapsed', String(panelCollapsed));
    }, [panelCollapsed]);
    const { tasks } = useTaskStore();
    const workbenchEnabled = useWorkbenchViewStore(s => s.enabled);
    const viewMode = useWorkbenchViewStore(s => s.viewMode);
    const simpleMode = workbenchEnabled && viewMode === 'simple';

    // ── Auto-Routing 跳转接收端（v1.5 升级项 C Beta） ──
    // VisualizationMessage 点击“查看”时写入 pendingVisualizationTab，本处消费后置空。
    const setMobileNavTab = useAppUiStore((s) => s.setMobileNavTab);
    const pendingVisualizationTab = useAppUiStore((s) => s.pendingVisualizationTab);
    const requestVisualizationTab = useAppUiStore((s) => s.requestVisualizationTab);
    useEffect(() => {
        if (!pendingVisualizationTab) return;
        const valid = ['sessions','tasks','files','sequence','dag','git','complexity','impact','api-docs','diagram','code-path','apos'] as const;
        if ((valid as readonly string[]).includes(pendingVisualizationTab)) {
            setActiveTab(pendingVisualizationTab as TabType);
            setPanelCollapsed(false);
            if (isDrawerMode) {
                setMobileNavTab(pendingVisualizationTab as TabType);
                onNavigate?.();
            }
        }
        requestVisualizationTab(null);
    }, [pendingVisualizationTab, requestVisualizationTab, isDrawerMode, setMobileNavTab, onNavigate]);

    // ── 可拖拽宽度 ──
    // §8.1 职责分离：断点走 useResponsive、像素走 useViewportWidth（rAF 节流，随缩放更新）
    const viewportWidth = useViewportWidth();
    // 动态最大宽度：不超过 800px 且不超过视口 70%
    const getMaxWidth = useCallback(() => Math.min(MAX_WIDTH, Math.floor(viewportWidth * 0.7)), [viewportWidth]);

    const [width, setWidth] = useState(() => {
        if (isDrawerMode) return 280;
        const saved = localStorage.getItem(STORAGE_KEY);
        const maxW = Math.min(MAX_WIDTH, Math.floor(viewportWidth * 0.7));
        return saved ? Math.min(Math.max(Number(saved), MIN_WIDTH), maxW) : DEFAULT_WIDTH;
    });
    const [isDragging, setIsDragging] = useState(false);
    const widthRef = useRef(width);
    widthRef.current = width;

    // 拖拽手柄 — mousedown
    const handleMouseDown = useCallback((e: React.MouseEvent) => {
        e.preventDefault();
        e.stopPropagation();
        const startX = e.clientX;
        const startWidth = widthRef.current;
        const maxW = getMaxWidth();
        setIsDragging(true);

        const handleMouseMove = (ev: MouseEvent) => {
            const newWidth = Math.min(Math.max(startWidth + (ev.clientX - startX), MIN_WIDTH), maxW);
            setWidth(newWidth);
            widthRef.current = newWidth;
        };

        const handleMouseUp = () => {
            document.removeEventListener('mousemove', handleMouseMove);
            document.removeEventListener('mouseup', handleMouseUp);
            document.body.style.cursor = '';
            document.body.style.userSelect = '';
            setIsDragging(false);
            localStorage.setItem(STORAGE_KEY, String(widthRef.current));
        };

        document.addEventListener('mousemove', handleMouseMove);
        document.addEventListener('mouseup', handleMouseUp);
        document.body.style.cursor = 'col-resize';
        document.body.style.userSelect = 'none';
    }, [getMaxWidth]);

    // 双击重置
    const handleDoubleClick = useCallback(() => {
        setWidth(DEFAULT_WIDTH);
        localStorage.setItem(STORAGE_KEY, String(DEFAULT_WIDTH));
    }, []);

    // 新窗口打开
    const handleOpenInNewWindow = useCallback(() => {
        const url = `${window.location.origin}${window.location.pathname}?sidebar=detached&tab=${activeTab}`;
        window.open(url, 'zhikun-sidebar', 'width=600,height=800,menubar=no,toolbar=no');
    }, [activeTab]);

    const aposEnabled = useFeatureFlagStore((s) => s.flags.APOS_ACTIVITY_STREAM);

    const allTabs: { id: TabType; label: string; icon: typeof MessageSquare }[] = [
        { id: 'sessions', label: simpleMode ? '任务' : '会话', icon: MessageSquare },
        { id: 'tasks', label: '任务', icon: CheckCircle2 },
        { id: 'files', label: '文件', icon: FolderTree },
        { id: 'sequence', label: '序列图', icon: ArrowDownUp },
        { id: 'dag', label: 'DAG', icon: GitBranch },
        { id: 'git', label: 'Git', icon: GitCommitHorizontal },
        { id: 'complexity', label: '复杂度', icon: BarChart3 },
        { id: 'impact', label: '影响分析', icon: GitBranch },
        { id: 'api-docs', label: 'API文档', icon: FileText },
        { id: 'diagram', label: '图表生成', icon: Workflow },
        { id: 'code-path', label: '代码路径', icon: Network },
        ...(aposEnabled ? [{ id: 'apos' as TabType, label: 'Activity', icon: Activity }] : []),
    ];
    const tabs = simpleMode
        ? allTabs.filter(tab => tab.id === 'sessions')
        : allTabs;

    useEffect(() => {
        if (simpleMode && activeTab !== 'sessions') {
            setActiveTab('sessions');
        }
    }, [activeTab, simpleMode]);

    // §7.5 移动端抽屉：选中 nav-item → 记录主区移动面板 Tab 并自动关闭抽屉
    const handleDrawerTabSelect = useCallback((tab: TabType) => {
        setActiveTab(tab);
        setMobileNavTab(tab);
        onNavigate?.();
    }, [setMobileNavTab, onNavigate]);

    // Drawer 模式不使用动态宽度；桌面端收起后保留固定展开条（COLLAPSED_STRIP_WIDTH）
    const sidebarStyle = isDrawerMode ? undefined : { width: `${panelCollapsed ? COLLAPSED_STRIP_WIDTH : width}px` };
    const sidebarWidthClass = isDrawerMode ? 'w-full' : '';

    return (
        <motion.aside
            className={`app-sidebar ${isDrawerMode ? '' : 'glass-surface'} ${sidebarWidthClass} h-full bg-app2 ${isDrawerMode ? '' : 'border-r border-hairline'} flex flex-col relative z-10 overflow-hidden ${className}`}
            style={sidebarStyle}
            animate={isDrawerMode ? undefined : { width: panelCollapsed ? COLLAPSED_STRIP_WIDTH : width }}
            transition={{ duration: glassMode && !reducedMotion && !isDragging ? .24 : 0, ease: [.2, .8, .2, 1] }}
        >
            {!isDrawerMode && <GlassMaterial interactive />}
            {isDrawerMode ? (
                /* §7.5 移动端抽屉 = 文字列表：图标 + 文字 + badge，38px 行高、rounded-xl、
                   active = accent2-soft 底 + accent 字 + 600 字重；选中后自动关闭抽屉 */
                <nav aria-label="主导航" className="flex-1 overflow-y-auto flex flex-col gap-1 p-3">
                    {tabs.map((tab) => {
                        const isActive = activeTab === tab.id;
                        const badge = tab.id === 'tasks' && tasks.size > 0 ? tasks.size : null;
                        return (
                            <button
                                key={tab.id}
                                title={tab.label}
                                aria-current={isActive ? 'page' : undefined}
                                onClick={() => handleDrawerTabSelect(tab.id)}
                                className={`panel-control flex items-center gap-3 min-h-11 shrink-0 px-3 rounded-xl text-sm
                                    transition-interactive duration-fast ${
                                    isActive
                                        ? 'bg-surfacev2 text-accent2-ink font-semibold shadow-raised'
                                        : 'text-t2 hover:bg-hover2 hover:text-t1'
                                }`}
                            >
                                <tab.icon className="w-4 h-4 shrink-0" />
                                <span className="flex-1 text-left truncate">{tab.label}</span>
                                {badge !== null && (
                                    <span className="min-w-[20px] h-5 px-1.5 inline-flex items-center justify-center
                                        rounded-full bg-accent2-soft text-accent2-ink text-[13px] font-semibold tabular-nums">
                                        {badge}
                                    </span>
                                )}
                            </button>
                        );
                    })}
                    {/* 新窗口打开入口保留 */}
                    {!simpleMode && (
                        <button
                            onClick={handleOpenInNewWindow}
                            title="在新窗口中打开侧边栏"
                            className="panel-control flex items-center gap-3 min-h-11 shrink-0 px-3 rounded-xl text-sm
                                text-t3 hover:bg-hover2 hover:text-t1 transition-interactive duration-fast"
                        >
                            <ExternalLink className="w-4 h-4 shrink-0" />
                            <span className="flex-1 text-left truncate">新窗口打开</span>
                        </button>
                    )}
                </nav>
            ) : panelCollapsed ? (
                /* 收起后的固定展开条：左侧常驻窄条（图标 + 竖排文字），整条可点击恢复 */
                <button
                    type="button"
                    onClick={() => setPanelCollapsed(false)}
                    aria-label="展开侧栏列表"
                    title="展开侧栏列表"
                    className="flex h-full w-full flex-col items-center gap-2 pt-3 text-t3
                        transition-interactive duration-fast hover:bg-hover2 hover:text-t1
                        focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent2-ink"
                >
                    <PanelLeftOpen className="w-4 h-4 shrink-0" aria-hidden="true" />
                    <span aria-hidden="true" className="text-xs tracking-wider [writing-mode:vertical-lr]">展开列表</span>
                </button>
            ) : (
                /* §7.5 PC：图标轨已从前台隐藏，面板直接呈现（默认会话列表）；
                   Tab 面板能力保留——可视化跳转等内部路径切入其他面板时，
                   顶部提供"返回会话列表"出口 */
                <div className="flex flex-1 min-h-0 flex-col">
                    {activeTab !== 'sessions' && (
                        <div className="shrink-0 border-b border-hairline px-1.5 py-1">
                            <button
                                type="button"
                                onClick={() => setActiveTab('sessions')}
                                className="panel-control inline-flex min-h-9 items-center gap-1 rounded-[10px] px-2 text-[13px] text-t2
                                    hover:bg-hover2 hover:text-t1 focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring"
                            >
                                <ChevronLeft className="w-4 h-4" aria-hidden="true" />
                                返回会话列表
                            </button>
                        </div>
                    )}
                    {/* 面板：236–320px（沿用现有 width state + 拖拽调宽 + detached 窗口逻辑） */}
                    <div className="flex-1 min-w-0 overflow-y-auto">
                        <SidebarTabContent activeTab={activeTab} width={width} onCollapse={() => setPanelCollapsed(true)} />
                    </div>
                </div>
            )}

            {isDrawerMode && (
                <div className="shrink-0 border-t border-hairline p-4 pb-[max(16px,env(safe-area-inset-bottom))]">
                    <label className="flex items-center gap-3 text-sm text-t2">
                        <span>外观</span>
                        <select
                            aria-label="外观主题"
                            value={normalizeThemeMode(themeMode)}
                            onChange={event => setTheme({ mode: event.target.value as 'light' | 'dark' | 'glass' })}
                            className="panel-control min-h-11 min-w-0 flex-1 rounded-[10px] border border-hairline bg-surfacev2 px-3 text-sm text-t1 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent2-ring"
                        >
                            <option value="light">浅色</option>
                            <option value="dark">深色</option>
                            <option value="glass">液态玻璃</option>
                        </select>
                    </label>
                </div>
            )}

            {/* 拖拽手柄 — 仅桌面端 */}
            {!isDrawerMode && !panelCollapsed && (
                <div
                    className={`absolute right-0 top-0 bottom-0 w-1 cursor-col-resize
                        transition-colors z-30
                        ${isDragging ? 'bg-accent2' : 'hover:bg-accent2-soft'}`}
                    onMouseDown={handleMouseDown}
                    onDoubleClick={handleDoubleClick}
                >
                    {/* 增大可点击区域到 8px */}
                    <div className="absolute -left-2 -right-2 top-0 bottom-0" />
                </div>
            )}
        </motion.aside>
    );
}

// ═══ Sidebar Tab 内容渲染器 — 桌面侧栏 / 移动主区面板共用（§7.5） ═══
export interface SidebarTabContentProps {
    /** Mobile session panel folds back navigation into its own title/count row. */
    onBack?: () => void;
    onSessionActivated?: () => void;
    onCollapse?: () => void;
    activeTab: TabType;
    /** FileTreePanel 等需要的宽度参考（桌面=侧栏宽，移动主区=视口宽） */
    width?: number;
}

export function SidebarTabContent({ activeTab, width = 280, onCollapse, onSessionActivated, onBack }: SidebarTabContentProps) {
    const { tasks, clearTasks } = useTaskStore();
    const workbenchEnabled = useWorkbenchViewStore(s => s.enabled);
    const viewMode = useWorkbenchViewStore(s => s.viewMode);
    const simpleMode = workbenchEnabled && viewMode === 'simple';

    return (
        <>
            {activeTab === 'sessions' && (simpleMode ? <SimpleTaskList onBack={onBack} onCollapse={onCollapse} onSessionActivated={onSessionActivated} /> : <SessionList onBack={onBack} onCollapse={onCollapse} onSessionActivated={onSessionActivated} />)}
            {activeTab === 'tasks' && <TaskPanel tasks={tasks} onClear={clearTasks} />}
            {activeTab === 'files' && <FileTreePanel sidebarWidth={width} />}
            {activeTab === 'sequence' && <APISequenceDiagram />}
            {activeTab === 'dag' && (
                <div className="h-full">
                    <AgentDAGChart />
                </div>
            )}
            {activeTab === 'git' && (
                <div className="h-full">
                    <GitTimeline repoPath="." />
                </div>
            )}
            {activeTab === 'complexity' && (
                <div className="h-full">
                    <CodeComplexityTreemap />
                </div>
            )}
            {activeTab === 'impact' && (
                <div className="h-full">
                    <ChangeImpactGraph />
                </div>
            )}
            {activeTab === 'api-docs' && (
                <ApiDocsTab />
            )}
            {activeTab === 'diagram' && (
                <div className="h-full">
                    <CodeDiagramGenerator />
                </div>
            )}
            {activeTab === 'code-path' && (
                <div className="h-full">
                    <CodePathTracer />
                </div>
            )}
            {activeTab === 'apos' && (
                <div className="grid grid-rows-[auto_1fr_auto] h-full overflow-hidden">
                    <SessionFileExplorer />
                    <ActivityStream />
                    <div className="max-h-[180px] overflow-y-auto flex-shrink-0">
                        <FeatureFlagPanel />
                    </div>
                </div>
            )}
        </>
    );
}

// ═══ API Docs Tab — 自动加载 ═══
function ApiDocsTab() {
    const fetchOpenApiSpec = useApiContractStore(s => s.fetchOpenApiSpec);
    useEffect(() => {
        fetchOpenApiSpec('merged');
    }, [fetchOpenApiSpec]);
    return (
        <div className="h-full">
            <APIContractViewer />
        </div>
    );
}

// ═══ 删除会话二次确认验证码（ZHIKUN_DELETE_CONFIRM_CODE） ═══
// 模块级缓存：是否要求删除验证码只需询问一次后端（GET /api/config/delete-confirm），
// fetch 失败时按"不需要"处理（保持原有删除行为），但失败结果不缓存——
// 下次组件挂载会重新请求，避免瞬时网络失败被永久缓存为"不需要验证码"。
// 验证码值本身永不返回前端。
let deleteConfirmRequiredPromise: Promise<boolean> | null = null;
function fetchDeleteConfirmRequired(): Promise<boolean> {
    if (!deleteConfirmRequiredPromise) {
        const request = fetch('/api/config/delete-confirm')
            .then(resp => {
                // HTTP 错误（如 502/503）同样按失败处理：抛错走下方 catch 清缓存路径，
                // 避免错误响应被当作"不需要验证码"永久缓存（用户将永远看不到验证码输入框）。
                if (!resp.ok) {
                    throw new Error('delete-confirm config HTTP ' + resp.status);
                }
                return resp.json() as { required?: unknown };
            })
            .then(data => Boolean(data?.required))
            .catch(() => {
                // 失败路径允许重试：仅当模块级缓存仍是本次请求时才清空，
                // 避免误清后续调用方新发起的请求；成功路径的缓存语义不变。
                if (deleteConfirmRequiredPromise === request) {
                    deleteConfirmRequiredPromise = null;
                }
                return false;
            });
        deleteConfirmRequiredPromise = request;
    }
    return deleteConfirmRequiredPromise;
}

/** 仅供测试：清空模块级缓存，使下次挂载重新请求 delete-confirm 配置。 */
export function resetDeleteConfirmRequiredCacheForTest(): void {
    deleteConfirmRequiredPromise = null;
}

function useDeleteConfirmRequired(): boolean {
    const [required, setRequired] = useState(false);
    useEffect(() => {
        let cancelled = false;
        void fetchDeleteConfirmRequired().then(value => { if (!cancelled) setRequired(value); });
        return () => { cancelled = true; };
    }, []);
    return required;
}

/** 删除确认气泡定位：优先显示在按钮上方，空间不足时放到下方；水平右对齐按钮右缘且不超出左右边缘（各留 8px）。 */
function deletePopoverStyle(rect: DOMRect, withCodeInput: boolean): React.CSSProperties {
    const estimatedHeight = withCodeInput ? 220 : 148;
    const showAbove = rect.top >= estimatedHeight + 8;
    // 气泡宽 256px：right ≤ innerWidth - 264 保证左缘 ≥ 8px；
    // 视口极窄时 Math.min 结果可能小于 8，外层 Math.max(8, ...) 兜底防右溢出。
    return {
        width: 256,
        right: Math.max(8, Math.min(window.innerWidth - rect.right, window.innerWidth - 264)),
        ...(showAbove
            ? { bottom: window.innerHeight - rect.top + 8 }
            : { top: rect.bottom + 8 }),
    };
}

// Session List Component — 从后端 API 获取会话列表
function SessionList({ onCollapse, onSessionActivated, onBack }: { onCollapse?: () => void; onSessionActivated?: () => void; onBack?: () => void }) {
    const mergeSourceIds = useSessionMergeStore(selectMergeSourceIds);
    const [sessions, setSessions] = useState<SessionSummary[]>([]);
    const [loading, setLoading] = useState(true);
    const [hasMore, setHasMore] = useState(false);
    const [nextCursor, setNextCursor] = useState<string | null>(null);
    const [query, setQuery] = useState('');
    const [collapsedFolders, setCollapsedFolders] = useState<Set<string>>(() => {
        try {
            const saved: unknown = JSON.parse(localStorage.getItem('session-collapsed-folders') ?? '[]');
            return new Set(Array.isArray(saved) ? saved.filter((key): key is string => typeof key === 'string') : []);
        } catch { return new Set(); }
    });
    useEffect(() => {
        localStorage.setItem('session-collapsed-folders', JSON.stringify([...collapsedFolders]));
    }, [collapsedFolders]);
    const toggleFolder = (key: string) => setCollapsedFolders(previous => {
        const next = new Set(previous);
        if (next.has(key)) next.delete(key); else next.add(key);
        return next;
    });
    const currentSessionId = useSessionStore(s => s.sessionId);
    const currentStatus = useSessionStore(s => s.status);
    const currentMessages = useMessageStore(s => s.messages);
    const reducedMotion = useReducedMotion();
    const simpleMode = useWorkbenchViewStore(s => s.enabled && s.viewMode === 'simple');
    const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

    // ── 删除二次确认气泡：点击垃圾桶不再立即删除，先在按钮附近弹出确认气泡 ──
    const deleteConfirmRequired = useDeleteConfirmRequired();
    const [confirmingDelete, setConfirmingDelete] = useState<{ sessionId: string; rect: DOMRect } | null>(null);
    const [deleteCode, setDeleteCode] = useState('');
    const [deleteCodeError, setDeleteCodeError] = useState<string | null>(null);
    // 删除请求 in-flight 标记：防止「确认删除」双击/Enter 连发重复 DELETE
    const [deleteSubmitting, setDeleteSubmitting] = useState(false);

    // 气泡打开期间按 Escape 关闭
    useEffect(() => {
        if (!confirmingDelete) return;
        const handler = (event: KeyboardEvent) => {
            if (event.key === 'Escape') {
                event.stopPropagation();
                setConfirmingDelete(null);
            }
        };
        document.addEventListener('keydown', handler);
        return () => document.removeEventListener('keydown', handler);
    }, [confirmingDelete]);

    // §7.5 面板搜索框：搜索已下推服务端（GET /api/sessions?query=，匹配标题与首条用户消息全文），
    // 客户端不再二次过滤，避免把服务端匹配到的结果过滤掉。
    const filteredSessions = sessions;

    const sessionGroups = useMemo(() => groupSessionsByDirectory(filteredSessions, true), [filteredSessions]);

    const loadedCountRef = useRef(50);
    const requestVersionRef = useRef(0);
    const queryRef = useRef('');
    const firstPageReadyRef = useRef(false);
    const pendingRequestRef = useRef<number | null>(null);
    const [listPending, setListPending] = useState(false);

    const handleQueryChange = (value: string) => {
        if (value === queryRef.current) return;
        queryRef.current = value;
        ++requestVersionRef.current;
        pendingRequestRef.current = null;
        firstPageReadyRef.current = false;
        loadedCountRef.current = 50;
        setSessions([]);
        setHasMore(false);
        setNextCursor(null);
        setListPending(true);
        setQuery(value);
    };

    // 刷新时保留已加载范围，防止较早的文件夹在实时更新后消失。
    // 加载会话列表
    const fetchSessions = useCallback(async (cursor?: string | null) => {
        if (query !== queryRef.current) return;
        if (cursor && (!firstPageReadyRef.current || pendingRequestRef.current !== null)) return;
        const requestVersion = ++requestVersionRef.current;
        pendingRequestRef.current = requestVersion;
        setListPending(true);
        try {
            const params = new URLSearchParams({ limit: String(cursor ? 50 : loadedCountRef.current) });
            if (cursor) params.set('cursor', cursor);
            const q = query.trim();
            if (q) params.set('query', q);
            const resp = await fetch(`/api/sessions?${params}`);
            if (!resp.ok) return;
            const data = await resp.json();
            if (requestVersion !== requestVersionRef.current) return;
            loadedCountRef.current = cursor ? loadedCountRef.current + data.sessions.length : Math.max(50, data.sessions.length);
            if (cursor) {
                setSessions(prev => {
                    const merged = [...new Map([...prev, ...data.sessions].map(session => [session.id, session])).values()];
                    return q ? merged : pageSessionOrder.order(merged);
                });
            } else {
                // 搜索保留服务端顺序；普通列表刷新继续使用页面内稳定排序。
                setSessions(q ? data.sessions : pageSessionOrder.order(data.sessions, 'front'));
                firstPageReadyRef.current = true;
            }
            setHasMore(data.hasMore);
            setNextCursor(data.nextCursor);
        } catch (e) {
            console.warn('[SessionList] Failed to fetch sessions:', e);
        } finally {
            if (requestVersion === requestVersionRef.current) {
                pendingRequestRef.current = null;
                setListPending(false);
                setLoading(false);
            }
        }
    }, [query]);

    // 初始加载 + 搜索词变化防抖（250ms）重新拉取
    useEffect(() => {
        loadedCountRef.current = 50;
        const timer = window.setTimeout(() => { void fetchSessions(); }, query.trim() ? 250 : 0);
        return () => window.clearTimeout(timer);
    }, [fetchSessions, query]);

    // 兜底轮询（60s，防止 WS 推送丢失）
    useEffect(() => {
        pollRef.current = setInterval(() => fetchSessions(), 60000);
        return () => { if (pollRef.current) clearInterval(pollRef.current); };
    }, [fetchSessions]);

    // WebSocket 推送驱动的即时刷新
    useEffect(() => {
        const handler = () => fetchSessions();
        window.addEventListener('session-list-updated', handler);
        return () => window.removeEventListener('session-list-updated', handler);
    }, [fetchSessions]);

    // 切换会话
    const handleSwitchSession = useCallback(async (sessionId: string) => {
        if (sessionId === currentSessionId) {
            onSessionActivated?.();
            return;
        }
        const result = await activateSessionCandidate(sessionId);
        if (result.status === 'activated') {
            onSessionActivated?.();
            // 切走后原会话可能仍在后台生成：刷新列表拿到服务端 running 标记
            window.dispatchEvent(new Event('session-list-updated'));
        }
        if (result.status === 'failed') {
            useNotificationStore.getState().addNotification({
                key: `session-switch-failed-${generateUUID()}`,
                level: 'error',
                message: `切换会话失败：${result.error.message}`,
            });
        }
    }, [currentSessionId, onSessionActivated]);

    // 新建会话
    const handleNewSession = useCallback(() => {
        dispatchNewAuthorizedSessionRequest();
    }, []);

    // 点击垃圾桶：不立即删除，弹出确认气泡（rect 用于气泡定位）
    const openDeleteConfirm = useCallback((e: React.MouseEvent, sessionId: string) => {
        e.stopPropagation();
        setConfirmingDelete({ sessionId, rect: e.currentTarget.getBoundingClientRect() });
        setDeleteCode('');
        setDeleteCodeError(null);
    }, []);

    // 删除会话（确认气泡中的「确认删除」/验证码 Enter 触发）
    const executeDeleteSession = useCallback(async (sessionId: string, code?: string) => {
        if (deleteSubmitting) return; // in-flight：忽略重复触发
        setDeleteSubmitting(true);
        const closePopover = () => setConfirmingDelete(null);
        try {
            const response = await fetch(`/api/sessions/${sessionId}`, {
                method: 'DELETE',
                ...(deleteConfirmRequired ? { headers: { 'X-Delete-Confirm-Code': code ?? '' } } : {}),
            });
            if (response.status === 403 && deleteConfirmRequired) {
                // 验证码错误：气泡保持打开，红字提示并等待重新输入
                setDeleteCodeError('验证码错误，请重新输入');
                setDeleteCode('');
                return;
            }
            if (!response.ok) {
                useNotificationStore.getState().addNotification({ key: `delete-${sessionId}`, level: 'error',
                    message: response.status === 409 ? '会话正在执行或合并，暂不能删除。' : '删除失败，请稍后重试。' });
                closePopover();
                return;
            }
            setSessions(prev => prev.filter(s => s.id !== sessionId));
            // 如果删除的是当前会话，清除状态
            if (sessionId === currentSessionId) {
                useMessageStore.getState().clearMessages();
                useSessionStore.getState().resumeSession('');
            }
            closePopover();
        } catch (e) {
            console.error('[SessionList] Failed to delete session:', e);
            closePopover();
        } finally {
            // 无论成功/403/409/异常都恢复可点击状态（403 重试路径依赖此复位）
            setDeleteSubmitting(false);
        }
    }, [currentSessionId, deleteConfirmRequired, deleteSubmitting]);

    // 格式化时间
    const formatTime = (isoStr: string) => {
        try {
            const date = new Date(isoStr);
            const now = new Date();
            const diffMs = now.getTime() - date.getTime();
            const diffMin = Math.floor(diffMs / 60000);
            if (diffMin < 1) return '刚刚';
            if (diffMin < 60) return `${diffMin} 分钟前`;
            const diffHour = Math.floor(diffMin / 60);
            if (diffHour < 24) return `${diffHour} 小时前`;
            const diffDay = Math.floor(diffHour / 24);
            if (diffDay < 7) return `${diffDay} 天前`;
            return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' });
        } catch { return ''; }
    };

    if (loading) {
        return (
            <div className="flex flex-col h-full">
                {onBack && <PanelHeader label="会话" onBack={onBack} />}
                <div className="p-4 flex justify-center">
                    <Loader2 className="w-5 h-5 animate-spin text-t3" />
                </div>
            </div>
        );
    }

    return (
        <div className="flex flex-col h-full">
            {/* §7.5 面板头：Label + 计数 chip */}
            <PanelHeader onBack={onBack} label={simpleMode ? '任务' : '会话'} count={filteredSessions.length} onCollapse={onCollapse} />

            {/* 新建按钮 + 搜索框（§7.5 配方） */}
            <div className="px-2 pb-2 space-y-2 border-b border-hairline flex-shrink-0">
                <button
                    onClick={handleNewSession}
                    className={PANEL_NEW_BUTTON_CLASS}
                >
                    <Plus className="w-4 h-4" />
                    {simpleMode ? '新建任务' : '新建会话'}
                </button>
                <PanelSearchBox
                    value={query}
                    onChange={handleQueryChange}
                    placeholder={simpleMode ? '搜索任务' : '搜索会话'}
                    ariaLabel={simpleMode ? '搜索任务' : '搜索会话'}
                />
            </div>

            {/* 会话列表（LayoutGroup 保证选中 pill 跨文件夹分组 section 滑动稳定） */}
            <LayoutGroup>
            <div className="flex-1 overflow-y-auto p-2 space-y-2">
                {filteredSessions.length === 0 ? (
                    <div className="p-4 text-center text-t2 text-sm">
                        {query
                            ? (simpleMode ? '无匹配任务' : '无匹配会话')
                            : (simpleMode ? '暂无任务记录' : '暂无会话记录')}
                    </div>
                ) : (
                    sessionGroups.map(group => {
                        // 搜索时临时展开匹配组，不改用户保存的折叠状态。
                        const expanded = Boolean(query.trim()) || !collapsedFolders.has(group.directory);
                        return (
                            <section key={group.directory} aria-label={group.directory || '未关联文件夹'} className="mb-1">
                                <button
                                    type="button"
                                    onClick={() => toggleFolder(group.directory)}
                                    aria-expanded={expanded}
                                    aria-label={`${expanded ? '收起' : '展开'}文件夹 ${group.directory || group.name}`}
                                    title={group.directory || group.name}
                                    className="panel-control w-full flex items-center gap-2 px-2 py-1.5 rounded-[10px] text-t2 hover:bg-hover2
                                        focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring"
                                >
                                    {expanded ? <ChevronDown className="w-3.5 h-3.5 shrink-0" /> : <ChevronRight className="w-3.5 h-3.5 shrink-0" />}
                                    <Folder className="w-4 h-4 shrink-0" />
                                    <span className="min-w-0 flex-1 truncate text-left text-sm font-medium text-t1">{group.name}</span>
                                    <span className="text-[13px] tabular-nums">{group.sessions.length}</span>
                                </button>
                                {expanded && <div className="mt-1.5 space-y-2">
                                    {group.sessions.map(session => {
                                        const folder = session.workingDirectory.split(/[\\/]/).filter(Boolean).at(-1);
                                        const displayTitle = simpleMode
                                            ? taskTitle(
                                                session.title,
                                                session.id === currentSessionId ? currentMessages : [],
                                                session.workingDirectory,
                                                session.goalPreview,
                                            )
                                            : session.title || taskTitle(null, [], session.workingDirectory, session.goalPreview);
                                        // 仅"运行中"需要在列表项展示状态（当前会话取实时 store 状态，
                                        // 其余会话取服务端 running 标记）；其他状态不展示。
                                        const generating = isSessionGenerating(session, currentSessionId, currentStatus);
                                        const merging = !!session.mergeOperationId || mergeSourceIds.includes(session.id);
                                        const isActive = session.id === currentSessionId;
                                        return (
                                            <div
                                                key={session.id}
                                                onClick={() => { void handleSwitchSession(session.id); }}
                                                data-active={isActive}
                                                title={`${session.id} · ${session.workingDirectory}`}
                                                className={SESSION_CARD_CLASS}
                                            >
                                                {/* 选中「荧光浮起」pill：所有 active 视觉在此独立层，切换会话时
                                                    framer-motion 对同一 layoutId 做 FLIP 魔法滑动 */}
                                                {isActive && (
                                                    <motion.span
                                                        layoutId="session-active-pill"
                                                        aria-hidden="true"
                                                        className="session-active-pill"
                                                        transition={reducedMotion ? { duration: 0 } : { type: 'spring', stiffness: 480, damping: 32, mass: 0.6 }}
                                                    />
                                                )}
                                                <div className="relative flex items-start justify-between gap-1">
                                                    <div className="flex-1 min-w-0">
                                                        <div className="flex items-center gap-2">
                                                            <div className={`min-w-0 flex-1 truncate text-sm ${isActive ? 'font-semibold' : 'font-medium'} text-t1`}>
                                                                {displayTitle}
                                                            </div>
                                                            {generating && (
                                                                <span role="status"
                                                                    className="inline-flex shrink-0 items-center gap-1 rounded-full border border-accent2-ring bg-accent2-soft px-2 py-0.5 text-[13px] font-medium leading-5 text-accent2-ink">
                                                                    <Loader2 aria-hidden="true" className="h-3 w-3 animate-spin" />
                                                                    运行中
                                                                </span>
                                                            )}
                                                            {merging && <span role="status" className="text-xs text-accent2-ink whitespace-nowrap">合并中</span>}
                                                        </div>
                                                        {!simpleMode && (
                                                            <div className={`mt-1 flex items-center gap-1.5 text-[13px] ${isActive ? 'text-t2' : 'text-t3'}`}>
                                                                <span className="truncate">{session.model}</span>
                                                                <span aria-hidden="true">·</span>
                                                                <span className="whitespace-nowrap tabular-nums">{session.messageCount} 条</span>
                                                                <span aria-hidden="true">·</span>
                                                                <span className="inline-flex items-center gap-1 whitespace-nowrap tabular-nums">
                                                                    <Clock className="w-3 h-3" aria-hidden="true" />
                                                                    {formatTime(session.updatedAt)}
                                                                </span>
                                                            </div>
                                                        )}
                                                        {simpleMode && (
                                                            <div className={`mt-1 flex items-center gap-1.5 text-[13px] ${isActive ? 'text-t2' : 'text-t3'}`} title={session.workingDirectory}>
                                                                {folder && <span className="truncate">文件夹：{folder}</span>}
                                                                {folder && <span aria-hidden="true">·</span>}
                                                                <span className="inline-flex items-center gap-1 whitespace-nowrap tabular-nums">
                                                                    <Clock className="w-3 h-3" aria-hidden="true" />
                                                                    {formatTime(session.updatedAt)}
                                                                </span>
                                                            </div>
                                                        )}
                                                    </div>
                                                    {/* 操作区：可见性由 globals.css 按指针能力切换
                                                        （触屏常驻可见；鼠标设备 hover/聚焦显示） */}
                                                    <div className="card-actions flex shrink-0 items-start gap-0.5 transition-interactive duration-fast">
                                                        <button
                                                            title="合并为新会话" aria-label="合并为新会话"
                                                            disabled={generating || merging}
                                                            className="panel-control p-1 rounded text-t3 hover:text-t1 disabled:opacity-30"
                                                            onClick={e => { e.stopPropagation(); useSessionMergeStore.getState().openDialog(session); }}>
                                                            <GitMerge className="w-3.5 h-3.5" />
                                                        </button>
                                                        <button
                                                            disabled={merging}
                                                            onClick={(e) => openDeleteConfirm(e, session.id)}
                                                            className="panel-control p-1 rounded
                                                                hover:bg-errsoft text-t3 hover:text-err
                                                                transition-interactive duration-fast"
                                                            title={simpleMode ? '删除任务' : '删除会话'}
                                                            aria-label={simpleMode ? '删除任务' : '删除会话'}
                                                        >
                                                            <Trash2 className="w-3.5 h-3.5" />
                                                        </button>
                                                    </div>
                                                </div>
                                            </div>
                                        );
                                    })}
                                </div>}
                            </section>
                        );
                    })
                )}

                {/* 加载更多 */}
                {hasMore && (
                    <button
                        disabled={listPending}
                        onClick={() => fetchSessions(nextCursor)}
                        className="panel-control w-full py-2 text-[13px] text-t2 hover:text-t1 transition-interactive duration-fast"
                    >
                        加载更多...
                    </button>
                )}
            </div>
            </LayoutGroup>

            {/* 删除二次确认气泡（portal 到 body，按垃圾桶位置定位） */}
            {confirmingDelete && createPortal(
                <div
                    className="fixed inset-0 z-40"
                    onClick={(e) => { e.stopPropagation(); setConfirmingDelete(null); }}
                >
                    <div
                        role="dialog"
                        aria-label={simpleMode ? '确认删除任务' : '确认删除会话'}
                        className="fixed z-50 rounded-[14px] border border-hairline bg-surfacev2 p-3 shadow-raised"
                        style={deletePopoverStyle(confirmingDelete.rect, deleteConfirmRequired)}
                        onClick={(e) => e.stopPropagation()}
                    >
                        <p className="text-sm font-medium text-t1">{simpleMode ? '确定删除该任务？' : '确定删除该会话？'}</p>
                        <p className="mt-1 text-[13px] text-t3">删除后不可恢复</p>
                        {deleteConfirmRequired && (
                            <div className="mt-2">
                                <input
                                    autoFocus
                                    type="text"
                                    value={deleteCode}
                                    onChange={(e) => { setDeleteCode(e.target.value); setDeleteCodeError(null); }}
                                    onKeyDown={(e) => {
                                        if (e.key === 'Enter' && deleteCode) {
                                            e.preventDefault();
                                            void executeDeleteSession(confirmingDelete.sessionId, deleteCode);
                                        }
                                    }}
                                    placeholder="请输入验证码"
                                    aria-label="删除验证码"
                                    className="h-8 w-full rounded-[10px] border border-transparent bg-sunken2 px-2.5 text-sm text-t1 shadow-well
                                        placeholder:text-t4 focus:outline-none focus:ring-[3px] focus:ring-accent2-ring"
                                />
                                {deleteCodeError && <p className="mt-1 text-[13px] text-err">{deleteCodeError}</p>}
                            </div>
                        )}
                        <div className="mt-3 flex justify-end gap-2">
                            <button
                                type="button"
                                disabled={deleteSubmitting}
                                onClick={(e) => { e.stopPropagation(); setConfirmingDelete(null); }}
                                className="panel-control rounded-[10px] px-3 py-1.5 text-[13px] text-t2
                                    transition-interactive duration-fast hover:bg-hover2 hover:text-t1 disabled:opacity-40"
                            >
                                取消
                            </button>
                            <button
                                type="button"
                                disabled={deleteSubmitting || (deleteConfirmRequired && !deleteCode)}
                                onClick={(e) => { e.stopPropagation(); void executeDeleteSession(confirmingDelete.sessionId, deleteCode); }}
                                className="panel-control rounded-[10px] bg-err px-3 py-1.5 text-[13px] font-medium text-white shadow-e1
                                    transition-interactive duration-fast dark:text-app2 disabled:opacity-40"
                            >
                                确认删除
                            </button>
                        </div>
                    </div>
                </div>,
                document.body
            )}
        </div>
    );
}

type WorkbenchTaskGroup = 'ACTION_REQUIRED' | 'RUNNING' | 'REVIEWABLE' | 'OTHER';
interface WorkbenchTaskItem {
    sessionId: string;
    title: string;
    folderName: string;
    status: WorkbenchTaskGroup;
    updatedAt: string;
    pendingCount: number;
    hint: string;
}
interface WorkbenchTaskGroupView { status: WorkbenchTaskGroup; label: string; tasks: WorkbenchTaskItem[]; }

/** 简洁模式任务导航只消费服务端权威分组，不从消息数或客户端状态推断。 */
function SimpleTaskList({ onCollapse, onSessionActivated, onBack }: { onCollapse?: () => void; onSessionActivated?: () => void; onBack?: () => void }) {
    const [groups, setGroups] = useState<WorkbenchTaskGroupView[]>([]);
    const [query, setQuery] = useState('');
    const [loading, setLoading] = useState(true);
    const [collapsed, setCollapsed] = useState<Set<WorkbenchTaskGroup>>(new Set(['OTHER']));
    const currentSessionId = useSessionStore(state => state.sessionId);
    const reducedMotion = useReducedMotion();
    const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
    const deleteConfirmRequired = useDeleteConfirmRequired();

    const fetchTasks = useCallback(async (search = query) => {
        try {
            const params = new URLSearchParams(); if (search.trim()) params.set('query', search.trim());
            const response = await fetch(`/api/workbench/tasks${params.size ? `?${params}` : ''}`);
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const data = await response.json() as { groups?: WorkbenchTaskGroupView[] };
            setGroups(Array.isArray(data.groups) ? data.groups.map(group => ({ ...group, tasks: pageSessionOrder.order(group.tasks.map(task => ({ ...task, id: task.sessionId })), 'front') })) : []);
        } catch (error) { console.warn('[SimpleTaskList] Failed to fetch tasks:', error); }
        finally { setLoading(false); }
    }, [query]);

    useEffect(() => {
        const timer = window.setTimeout(() => { void fetchTasks(query); }, query ? 250 : 0);
        return () => window.clearTimeout(timer);
    }, [fetchTasks, query]);
    useEffect(() => {
        pollRef.current = setInterval(() => { void fetchTasks(query); }, 30000);
        const handler = () => { void fetchTasks(query); };
        window.addEventListener('session-list-updated', handler);
        return () => { if (pollRef.current) clearInterval(pollRef.current); window.removeEventListener('session-list-updated', handler); };
    }, [fetchTasks, query]);

    const switchTask = async (sessionId: string) => {
        if (sessionId === currentSessionId) {
            onSessionActivated?.();
            return;
        }
        const result = await activateSessionCandidate(sessionId);
        if (result.status === 'activated') onSessionActivated?.();
        if (result.status === 'failed') useNotificationStore.getState().addNotification({ key: `task-switch-${generateUUID()}`, level: 'error', message: `切换任务失败：${result.error.message}` });
    };
    const deleteTask = async (event: React.MouseEvent, sessionId: string) => {
        event.stopPropagation();
        if (!window.confirm('确定删除这个任务及其本地记录吗？')) return;
        let code = '';
        if (deleteConfirmRequired) {
            const input = window.prompt('请输入删除验证码');
            if (input === null) return;
            code = input;
        }
        const response = await fetch(`/api/sessions/${sessionId}`, {
            method: 'DELETE',
            ...(deleteConfirmRequired ? { headers: { 'X-Delete-Confirm-Code': code } } : {}),
        });
        if (response.status === 403 && deleteConfirmRequired) {
            useNotificationStore.getState().addNotification({ key: `delete-${sessionId}`, level: 'error', message: '验证码错误，删除失败。' });
            return;
        }
        if (response.ok) void fetchTasks(query);
    };
    const iconFor = (status: WorkbenchTaskGroup) => status === 'ACTION_REQUIRED' ? XCircle
        : status === 'RUNNING' ? Loader2 : status === 'REVIEWABLE' ? CheckCircle2 : CircleDashed;
    const toneFor = (status: WorkbenchTaskGroup) => status === 'ACTION_REQUIRED' ? 'text-warn'
        : status === 'RUNNING' ? 'text-accent2-ink' : status === 'REVIEWABLE' ? 'text-ok' : 'text-t3';
    const totalTasks = groups.reduce((n, group) => n + group.tasks.length, 0);
    const formatTime = (value: string) => {
        const diff = Date.now() - Date.parse(value); const minutes = Math.floor(diff / 60000);
        if (minutes < 1) return '刚刚'; if (minutes < 60) return `${minutes} 分钟前`;
        const hours = Math.floor(minutes / 60); if (hours < 24) return `${hours} 小时前`;
        return `${Math.floor(hours / 24)} 天前`;
    };

    return <div className="flex h-full flex-col">
        {/* §7.5 面板头：Label + 计数 chip */}
        <PanelHeader onBack={onBack} label="任务" count={totalTasks} onCollapse={onCollapse} />
        <div className="space-y-2 border-b border-hairline px-2 pb-2 flex-shrink-0">
            <button onClick={() => dispatchNewAuthorizedSessionRequest()} className={PANEL_NEW_BUTTON_CLASS}><Plus className="h-4 w-4" />新建任务</button>
            <PanelSearchBox value={query} onChange={setQuery} placeholder="搜索任务或文件夹" ariaLabel="搜索任务或文件夹" />
        </div>
        <LayoutGroup>
        <div className="flex-1 overflow-y-auto p-2">
            {loading && <div className="flex justify-center p-4"><Loader2 className="h-5 w-5 animate-spin text-t3" /></div>}
            {!loading && groups.every(group => group.tasks.length === 0) && <p className="p-4 text-center text-sm text-t2">没有匹配的任务</p>}
            {groups.map(group => {
                if (group.tasks.length === 0) return null;
                const collapsedGroup = collapsed.has(group.status); const GroupIcon = iconFor(group.status);
                return <section key={group.status} className="mb-3"><button onClick={() => setCollapsed(previous => { const next = new Set(previous); next.has(group.status) ? next.delete(group.status) : next.add(group.status); return next; })} className="panel-control flex w-full items-center gap-2 px-2 py-1.5 text-[13px] font-medium text-t2"><span>{collapsedGroup ? <ChevronRight className="h-3.5 w-3.5" /> : <ChevronDown className="h-3.5 w-3.5" />}</span><GroupIcon className={`h-3.5 w-3.5 ${toneFor(group.status)} ${group.status === 'RUNNING' ? 'animate-spin' : ''}`} />{group.label}<span className="ml-auto text-t2 tabular-nums whitespace-nowrap shrink-0">{group.tasks.length}</span></button>
                    {!collapsedGroup && <div className="space-y-2">{group.tasks.map(task => {
                        const isActive = task.sessionId === currentSessionId;
                        return (
                        <div
                            key={task.sessionId}
                            onClick={() => { void switchTask(task.sessionId); }}
                            data-active={isActive}
                            className={SESSION_CARD_CLASS}
                        >
                            {/* 选中 pill：同 SessionList，layoutId 共享元素承载魔法滑动 */}
                            {isActive && (
                                <motion.span
                                    layoutId="session-active-pill"
                                    aria-hidden="true"
                                    className="session-active-pill"
                                    transition={reducedMotion ? { duration: 0 } : { type: 'spring', stiffness: 480, damping: 32, mass: 0.6 }}
                                />
                            )}
                            <div className="relative flex items-start gap-2">
                                <div className="min-w-0 flex-1">
                                    <p className={`truncate text-sm ${isActive ? 'font-semibold' : 'font-medium'} text-t1`}>{task.title}</p>
                                    <p className={`mt-1 flex items-center gap-1.5 text-[13px] ${isActive ? 'text-t2' : 'text-t3'}`}>
                                        <span className="truncate">{task.folderName}{task.hint ? ` · ${task.hint}` : ''}</span>
                                        <span aria-hidden="true">·</span>
                                        <span className="inline-flex items-center gap-1 whitespace-nowrap tabular-nums">
                                            <Clock className="h-3 w-3" aria-hidden="true" />
                                            {formatTime(task.updatedAt)}
                                        </span>
                                    </p>
                                </div>
                                <button onClick={event => { void deleteTask(event, task.sessionId); }} className="card-actions panel-control rounded p-1 text-t3 hover:bg-errsoft hover:text-err transition-interactive duration-fast" aria-label={`删除任务 ${task.title}`}><Trash2 className="h-3.5 w-3.5" /></button>
                            </div>
                        </div>
                        );
                    })}</div>}
                </section>;
            })}
        </div>
        </LayoutGroup>
    </div>;
}

// Task Panel Component
function TaskPanel({ tasks, onClear }: { tasks: Map<string, TaskState>; onClear: () => void }) {
    const [expandedTasks, setExpandedTasks] = useState<Set<string>>(new Set());

    const toggleTask = useCallback((taskId: string) => {
        setExpandedTasks(prev => {
            const next = new Set(prev);
            if (next.has(taskId)) {
                next.delete(taskId);
            } else {
                next.add(taskId);
            }
            return next;
        });
    }, []);

    const getStatusIcon = (status: string) => {
        switch (status) {
            case 'completed':
                return <CheckCircle2 className="w-4 h-4 text-ok" />;
            case 'failed':
                return <XCircle className="w-4 h-4 text-err" />;
            case 'running':
                return <Loader2 className="w-4 h-4 text-accent2-ink animate-spin" />;
            default:
                return <div className="w-4 h-4 rounded-full border-2 border-t3" />;
        }
    };

    if (tasks.size === 0) {
        return (
            <div className="p-4 text-center text-t2 text-sm">
                暂无运行中的任务
            </div>
        );
    }

    return (
        <div className="flex flex-col h-full">
            {/* §7.5 面板头：Label + 计数 chip + 清除钮 */}
            <div className="flex items-center justify-between gap-2 px-3 pt-3 pb-2 flex-shrink-0">
                <span className={PANEL_LABEL_CLASS}>任务</span>
                <div className="flex items-center gap-1">
                    <Chip variant="accent" className="tabular-nums">{tasks.size}</Chip>
                    <button
                        onClick={onClear}
                        className="panel-control p-1.5 rounded-[10px] hover:bg-hover2 text-t3 hover:text-t1
                            transition-interactive duration-fast"
                        title="清除已完成任务"
                    >
                        <Trash2 className="w-3.5 h-3.5" />
                    </button>
                </div>
            </div>

            <div className="flex-1 overflow-y-auto p-2 pt-0">
            {Array.from(tasks.entries()).map(([taskId, task]) => (
                <div key={taskId} className="mb-1">
                    <button
                        onClick={() => toggleTask(taskId)}
                        className="panel-control w-full px-3 py-2 rounded-xl hover:bg-hover2 flex items-center gap-2
                            transition-interactive duration-fast"
                    >
                        {expandedTasks.has(taskId) ? (
                            <ChevronDown className="w-4 h-4 text-t3" />
                        ) : (
                            <ChevronRight className="w-4 h-4 text-t3" />
                        )}
                        {getStatusIcon(task.status)}
                        <span className="flex-1 text-left text-sm text-t1 truncate">
                            {task.agentName || taskId.slice(0, 8)}
                        </span>
                    </button>

                    {expandedTasks.has(taskId) && (
                        <div className="ml-9 mt-1 space-y-1">
                            {task.progress !== undefined && (
                                <div className="h-1.5 bg-sunken2 rounded-full overflow-hidden">
                                    <div
                                        className="h-full bg-accent2 transition-[width]"
                                        style={{ width: `${(task.progress as number) * 100}%` }}
                                    />
                                </div>
                            )}
                            {task.result !== undefined && task.result !== null && (
                                <div className="text-[13px] text-t2 p-2 bg-sunken2 rounded-[10px]">
                                    {typeof task.result === 'string' ? task.result : JSON.stringify(task.result).slice(0, 100)}
                                </div>
                            )}
                        </div>
                    )}
                </div>
            ))}
            </div>
        </div>
    );
}
