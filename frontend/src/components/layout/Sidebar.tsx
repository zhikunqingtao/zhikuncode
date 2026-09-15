import { pageSessionOrder } from '@/utils/pageSessionOrder';
import { GlassMaterial } from '@/components/theme/GlassMaterial';
import { motion, useReducedMotion } from 'framer-motion';
/**
 * Sidebar — 左侧边栏组件
 * SPEC: §8.6.2
 *
 * 包含: SessionList, TaskPanel, FileTracker
 */

import { useState, useCallback, useEffect, useMemo, useRef } from 'react';
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
    ChevronRight,
    Trash2,
    Plus,
    Clock,
    ArrowDownUp,
    GitBranch,
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
import { groupSessionsByDirectory, type SessionSummary } from '@/utils/sessionGroups';

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

/** §7.5 图标轨宽度（w-12 = 48px）：面板内容区宽度 = aside 宽 - 图标轨 */
const RAIL_WIDTH = 48;

// ═══ §7.5 桌面面板配方常量（移动抽屉文字列表路径不消费） ═══
/** 面板 Label：11px/600/大写/tracking-wider（§3.8 Label）。
 *  颜色取 text-t2 而非任务书字面 text-t3：text-t3 实测对比度 <4.5:1 不达 §10.1 AA 红线
 * （同 SettingsPanel P1b 先例：对比度红线优先于色级偏好）。 */
const PANEL_LABEL_CLASS = 'text-[13px] font-semibold uppercase tracking-wider text-t2';

/** 「新建」按钮：accent2-soft 底 + accent 字，rounded-xl；
 *  文字档取 strong（soft 底上基准档不足 4.5:1，同 Chip 基元注释的 §10.1 处理）。 */
const PANEL_NEW_BUTTON_CLASS =
    'w-full flex items-center justify-center gap-2 px-3 py-2 rounded-xl ' +
    'bg-accent2-soft text-accent2-ink dark:text-accent2-ink text-sm font-medium ' +
    'transition-interactive duration-fast active:scale-[0.98] ' +
    'focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring';

/** 列表卡 active：accent2-soft 底 + inset 2px 内嵌左边条。
 *  box-shadow 复合写法——若卡片另有外影，需把外影并列进同一 shadow 列表防止被覆盖。 */
const PANEL_CARD_ACTIVE_CLASS = 'bg-accent2-soft shadow-[inset_2px_0_0_0_var(--v2-accent)]';

/** §7.5 面板头：Label（大写）+ 计数 chip */
function PanelHeader({ label, count, onCollapse }: { label: string; count?: number; onCollapse?: () => void }) {
    return (
        <div className="flex items-center justify-between gap-2 px-3 pt-3 pb-2 flex-shrink-0">
            <div className="flex items-center gap-2 min-w-0">
                <span className={PANEL_LABEL_CLASS}>{label}</span>
                {count !== undefined && <Chip variant="accent" className="tabular-nums">{count}</Chip>}
            </div>
            {onCollapse && (
                <button
                    type="button"
                    onClick={onCollapse}
                    aria-label="收起整个对话列表"
                    title="收起整个对话列表"
                    className="panel-control inline-flex items-center gap-1.5 shrink-0 rounded-lg px-2 py-1 text-[13px] text-t2
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
    const defaultView = useWorkbenchViewStore(s => s.defaultView);
    const setDefaultView = useWorkbenchViewStore(s => s.setDefaultView);
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

    // Drawer 模式不使用动态宽度
    const sidebarStyle = isDrawerMode ? undefined : { width: `${panelCollapsed ? RAIL_WIDTH : width}px` };
    const sidebarWidthClass = isDrawerMode ? 'w-full' : '';

    return (
        <motion.aside
            className={`app-sidebar ${isDrawerMode ? '' : 'glass-surface'} ${sidebarWidthClass} h-full bg-surface2 ${isDrawerMode ? '' : 'border-r border-hairline'} flex flex-col relative z-10 ${className}`}
            style={sidebarStyle}
            animate={isDrawerMode ? undefined : { width: panelCollapsed ? RAIL_WIDTH : width }}
            transition={{ duration: glassMode && !reducedMotion && !isDragging ? .24 : 0, ease: [.2, .8, .2, 1] }}
        >
            {!isDrawerMode && <GlassMaterial />}
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
                                        ? 'bg-accent2-soft text-accent2-ink font-semibold'
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
            ) : (
                /* §7.5 PC 混合方案：[48px 图标轨][面板]；面板宽度 state/拖拽/detached 逻辑保留 */
                <div className="flex flex-1 min-h-0">
                    {/* 图标轨：48px，12 Tab；active = accent2-soft 底 + accent 图标，命中区 40px */}
                    <nav
                        aria-label="侧边栏导航"
                        className="sidebar-rail w-12 flex-shrink-0 flex flex-col items-center gap-1 py-2
                            border-r border-hairline bg-surface2 overflow-y-auto"
                    >
                        <button
                            type="button"
                            onClick={() => setPanelCollapsed(value => !value)}
                            aria-label={panelCollapsed ? '展开侧栏列表' : '收起侧栏列表'}
                            title={panelCollapsed ? '展开侧栏列表' : '收起侧栏列表'}
                            aria-expanded={!panelCollapsed}
                            className="panel-control w-10 h-10 shrink-0 flex items-center justify-center rounded-xl text-t2 hover:bg-hover2
                                focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring"
                        >
                            {panelCollapsed ? <PanelLeftOpen className="w-4 h-4" /> : <PanelLeftClose className="w-4 h-4" />}
                        </button>
                        {tabs.map((tab) => {
                            const isActive = activeTab === tab.id;
                            return (
                                <button
                                    key={tab.id}
                                    onClick={() => { setActiveTab(tab.id); setPanelCollapsed(false); }}
                                    title={tab.label}
                                    aria-label={tab.label}
                                    aria-current={isActive ? 'page' : undefined}
                                    className={`panel-control w-10 h-10 flex items-center justify-center rounded-xl
                                        transition-interactive duration-fast
                                        focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring ${
                                        isActive
                                            ? 'bg-accent2-soft text-accent2-ink'
                                            : 'text-t3 hover:bg-hover2 hover:text-t1'
                                    }`}
                                >
                                    <tab.icon className="w-4 h-4" />
                                </button>
                            );
                        })}
                        {/* 新窗口打开按钮（detached 入口保留） */}
                        {!simpleMode && (
                            <button
                                onClick={handleOpenInNewWindow}
                                className="panel-control mt-auto w-10 h-10 flex items-center justify-center rounded-xl
                                    text-t3 hover:bg-hover2 hover:text-t1
                                    transition-interactive duration-fast
                                    focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring"
                                title="在新窗口中打开侧边栏"
                                aria-label="在新窗口中打开侧边栏"
                            >
                                <ExternalLink className="w-4 h-4" />
                            </button>
                        )}
                    </nav>

                    {/* 面板：236–320px（沿用现有 width state + 拖拽调宽 + detached 窗口逻辑）；
                        内容区宽 = aside 宽 - 图标轨宽（FileTreePanel 等依此计算树宽） */}
                    <div className="flex-1 min-w-0 overflow-y-auto" hidden={panelCollapsed}>
                        <SidebarTabContent activeTab={activeTab} width={Math.max(width - RAIL_WIDTH, MIN_WIDTH - RAIL_WIDTH)} onCollapse={() => setPanelCollapsed(true)} />
                    </div>
                </div>
            )}

            {isDrawerMode && (
                <div className="shrink-0 border-t border-hairline p-4 pb-[max(16px,env(safe-area-inset-bottom))]">
                    {workbenchEnabled && (
                        <button
                            type="button"
                            onClick={() => setDefaultView(viewMode)}
                            className="panel-control mb-3 min-h-11 w-full rounded-[10px] border border-hairline px-3 text-left text-sm text-t2 hover:bg-hover2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent2-ring"
                        >
                            {defaultView === viewMode ? '当前工作台已设为默认' : '将当前工作台设为默认'}
                        </button>
                    )}
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
                        ${isDragging ? 'bg-blue-500/70' : 'hover:bg-blue-500/50'}`}
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
    onSessionActivated?: () => void;
    onCollapse?: () => void;
    activeTab: TabType;
    /** FileTreePanel 等需要的宽度参考（桌面=侧栏宽，移动主区=视口宽） */
    width?: number;
}

export function SidebarTabContent({ activeTab, width = 280, onCollapse, onSessionActivated }: SidebarTabContentProps) {
    const { tasks, clearTasks } = useTaskStore();
    const workbenchEnabled = useWorkbenchViewStore(s => s.enabled);
    const viewMode = useWorkbenchViewStore(s => s.viewMode);
    const simpleMode = workbenchEnabled && viewMode === 'simple';

    return (
        <>
            {activeTab === 'sessions' && (simpleMode ? <SimpleTaskList onCollapse={onCollapse} onSessionActivated={onSessionActivated} /> : <SessionList onCollapse={onCollapse} onSessionActivated={onSessionActivated} />)}
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

// Session List Component — 从后端 API 获取会话列表
function SessionList({ onCollapse, onSessionActivated }: { onCollapse?: () => void; onSessionActivated?: () => void }) {
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
    const currentMessages = useMessageStore(s => s.messages);
    const simpleMode = useWorkbenchViewStore(s => s.enabled && s.viewMode === 'simple');
    const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

    // §7.5 面板搜索框：客户端过滤已加载会话（标题/模型/ID/目录），不改请求逻辑
    const filteredSessions = useMemo(() => {
        const q = query.trim().toLowerCase();
        if (!q) return sessions;
        return sessions.filter(s =>
            (s.title ?? '').toLowerCase().includes(q)
            || s.model.toLowerCase().includes(q)
            || s.id.toLowerCase().includes(q)
            || s.workingDirectory.toLowerCase().includes(q));
    }, [sessions, query]);

    const sessionGroups = useMemo(() => groupSessionsByDirectory(filteredSessions, true), [filteredSessions]);

    const loadedCountRef = useRef(50);
    const requestVersionRef = useRef(0);

    // 刷新时保留已加载范围，防止较早的文件夹在实时更新后消失。
    // 加载会话列表
    const fetchSessions = useCallback(async (cursor?: string | null) => {
        const requestVersion = ++requestVersionRef.current;
        try {
            const params = new URLSearchParams({ limit: String(cursor ? 50 : loadedCountRef.current) });
            if (cursor) params.set('cursor', cursor);
            const resp = await fetch(`/api/sessions?${params}`);
            if (!resp.ok) return;
            const data = await resp.json();
            if (requestVersion !== requestVersionRef.current) return;
            loadedCountRef.current = cursor ? loadedCountRef.current + data.sessions.length : Math.max(50, data.sessions.length);
            if (cursor) {
                setSessions(prev => pageSessionOrder.order([...new Map([...prev, ...data.sessions].map(session => [session.id, session])).values()]));
            } else {
                // 'front'：无 cursor 刷新（首载/轮询/WS）发现的新会话排到最前
                setSessions(pageSessionOrder.order(data.sessions, 'front'));
            }
            setHasMore(data.hasMore);
            setNextCursor(data.nextCursor);
        } catch (e) {
            console.warn('[SessionList] Failed to fetch sessions:', e);
        } finally {
            if (requestVersion === requestVersionRef.current) setLoading(false);
        }
    }, []);

    // 初始加载 + 兆底轮询（60s，防止 WS 推送丢失）
    useEffect(() => {
        fetchSessions();
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
        if (result.status === 'activated') onSessionActivated?.();
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

    // 删除会话
    const handleDeleteSession = useCallback(async (e: React.MouseEvent, sessionId: string) => {
        e.stopPropagation();
        try {
            await fetch(`/api/sessions/${sessionId}`, { method: 'DELETE' });
            setSessions(prev => prev.filter(s => s.id !== sessionId));
            // 如果删除的是当前会话，清除状态
            if (sessionId === currentSessionId) {
                useMessageStore.getState().clearMessages();
                useSessionStore.getState().resumeSession('');
            }
        } catch (e) {
            console.error('[SessionList] Failed to delete session:', e);
        }
    }, [currentSessionId]);

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
            <div className="p-4 flex justify-center">
                <Loader2 className="w-5 h-5 animate-spin text-t3" />
            </div>
        );
    }

    return (
        <div className="flex flex-col h-full">
            {/* §7.5 面板头：Label + 计数 chip */}
            <PanelHeader label={simpleMode ? '任务' : '会话'} count={filteredSessions.length} onCollapse={onCollapse} />

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
                    onChange={setQuery}
                    placeholder={simpleMode ? '搜索任务' : '搜索会话'}
                    ariaLabel={simpleMode ? '搜索任务' : '搜索会话'}
                />
            </div>

            {/* 会话列表 */}
            <div className="flex-1 overflow-y-auto p-2 space-y-1">
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
                            <section key={group.directory} aria-label={group.directory || '未关联文件夹'}>
                                <button
                                    type="button"
                                    onClick={() => toggleFolder(group.directory)}
                                    aria-expanded={expanded}
                                    aria-label={`${expanded ? '收起' : '展开'}文件夹 ${group.directory || group.name}`}
                                    title={group.directory || group.name}
                                    className="panel-control w-full flex items-center gap-2 px-2 py-2 rounded-lg text-t2 hover:bg-hover2
                                        focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring"
                                >
                                    {expanded ? <ChevronDown className="w-3.5 h-3.5 shrink-0" /> : <ChevronRight className="w-3.5 h-3.5 shrink-0" />}
                                    <Folder className="w-4 h-4 shrink-0" />
                                    <span className="min-w-0 flex-1 text-left">
                                        <span className="block truncate text-sm font-medium text-t1">{group.name}</span>
                                        {group.directory && <span className="block truncate text-[13px]">{group.directory}</span>}
                                    </span>
                                    <span className="text-[13px] tabular-nums">{group.sessions.length}</span>
                                </button>
                                {expanded && <div className="ml-2 border-l border-hairline pl-1 space-y-1">
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
                                        return (
                                            <div
                                                key={session.id}
                                                onClick={() => { void handleSwitchSession(session.id); }}
                                                className={`group px-3 py-2.5 rounded-xl cursor-pointer border border-transparent
                                                    transition-interactive duration-fast
                                                    ${session.id === currentSessionId
                                                        ? PANEL_CARD_ACTIVE_CLASS
                                                        : 'hover:bg-hover2'}`}
                                            >
                                                <div className="flex items-start justify-between gap-1">
                                                    <div className="flex-1 min-w-0">
                                                        <div className="text-sm font-medium text-t1 truncate">
                                                            {displayTitle}
                                                        </div>
                                                        {!simpleMode && <div className="flex items-center gap-2 mt-1">
                                                            <span className="text-[13px] text-t2 truncate">
                                                                {session.model} · {session.id.slice(0, 8)}
                                                            </span>
                                                            <span className="text-[13px] text-t2 tabular-nums whitespace-nowrap shrink-0">
                                                                {session.messageCount} 条消息
                                                            </span>
                                                        </div>}
                                                        {simpleMode && folder && (
                                                            <div className="mt-1 truncate text-[13px] text-t2" title={session.workingDirectory}>
                                                                文件夹：{folder}
                                                            </div>
                                                        )}
                                                        <div className="flex items-center gap-1 mt-1 text-[13px] text-t2">
                                                            <Clock className="w-3 h-3" />
                                                            {formatTime(session.updatedAt)}
                                                        </div>
                                                    </div>
                                                    <button
                                                        onClick={(e) => handleDeleteSession(e, session.id)}
                                                        className="panel-control p-1 rounded opacity-0 group-hover:opacity-100
                                                            hover:bg-errsoft text-t3 hover:text-err
                                                            transition-interactive duration-fast"
                                                        title={simpleMode ? '删除任务' : '删除会话'}
                                                        aria-label={simpleMode ? '删除任务' : '删除会话'}
                                                    >
                                                        <Trash2 className="w-3.5 h-3.5" />
                                                    </button>
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
                        onClick={() => fetchSessions(nextCursor)}
                        className="panel-control w-full py-2 text-[13px] text-t2 hover:text-t1 transition-interactive duration-fast"
                    >
                        加载更多...
                    </button>
                )}
            </div>
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
function SimpleTaskList({ onCollapse, onSessionActivated }: { onCollapse?: () => void; onSessionActivated?: () => void }) {
    const [groups, setGroups] = useState<WorkbenchTaskGroupView[]>([]);
    const [query, setQuery] = useState('');
    const [loading, setLoading] = useState(true);
    const [collapsed, setCollapsed] = useState<Set<WorkbenchTaskGroup>>(new Set(['OTHER']));
    const currentSessionId = useSessionStore(state => state.sessionId);
    const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

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
        const response = await fetch(`/api/sessions/${sessionId}`, { method: 'DELETE' });
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
        <PanelHeader label="任务" count={totalTasks} onCollapse={onCollapse} />
        <div className="space-y-2 border-b border-hairline px-2 pb-2 flex-shrink-0">
            <button onClick={() => dispatchNewAuthorizedSessionRequest()} className={PANEL_NEW_BUTTON_CLASS}><Plus className="h-4 w-4" />新建任务</button>
            <PanelSearchBox value={query} onChange={setQuery} placeholder="搜索任务或文件夹" ariaLabel="搜索任务或文件夹" />
        </div>
        <div className="flex-1 overflow-y-auto p-2">
            {loading && <div className="flex justify-center p-4"><Loader2 className="h-5 w-5 animate-spin text-t3" /></div>}
            {!loading && groups.every(group => group.tasks.length === 0) && <p className="p-4 text-center text-sm text-t2">没有匹配的任务</p>}
            {groups.map(group => {
                if (group.tasks.length === 0) return null;
                const collapsedGroup = collapsed.has(group.status); const GroupIcon = iconFor(group.status);
                return <section key={group.status} className="mb-3"><button onClick={() => setCollapsed(previous => { const next = new Set(previous); next.has(group.status) ? next.delete(group.status) : next.add(group.status); return next; })} className="panel-control flex w-full items-center gap-2 px-2 py-1.5 text-[13px] font-medium text-t2"><span>{collapsedGroup ? <ChevronRight className="h-3.5 w-3.5" /> : <ChevronDown className="h-3.5 w-3.5" />}</span><GroupIcon className={`h-3.5 w-3.5 ${toneFor(group.status)} ${group.status === 'RUNNING' ? 'animate-spin' : ''}`} />{group.label}<span className="ml-auto text-t2 tabular-nums whitespace-nowrap shrink-0">{group.tasks.length}</span></button>
                    {!collapsedGroup && <div className="space-y-1">{group.tasks.map(task => <div key={task.sessionId} onClick={() => { void switchTask(task.sessionId); }} className={`group cursor-pointer rounded-xl border border-transparent px-3 py-2.5 transition-interactive duration-fast ${task.sessionId === currentSessionId ? PANEL_CARD_ACTIVE_CLASS : 'hover:bg-hover2'}`}><div className="flex items-start gap-2"><div className="min-w-0 flex-1"><p className="truncate text-sm font-medium text-t1">{task.title}</p><p className="mt-1 truncate text-[13px] text-t2">{task.folderName} · {task.hint}</p><p className="mt-1 flex items-center gap-1 text-[13px] text-t2"><Clock className="h-3 w-3" />{formatTime(task.updatedAt)}</p></div><button onClick={event => { void deleteTask(event, task.sessionId); }} className="panel-control rounded p-1 text-t3 opacity-0 hover:bg-errsoft hover:text-err group-hover:opacity-100 transition-interactive duration-fast" aria-label={`删除任务 ${task.title}`}><Trash2 className="h-3.5 w-3.5" /></button></div></div>)}</div>}
                </section>;
            })}
        </div>
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
                        className="panel-control p-1.5 rounded-lg hover:bg-hover2 text-t3 hover:text-t1
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
                                <div className="text-[13px] text-t2 p-2 bg-sunken2 rounded-lg">
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
