/**
 * SettingsPanel — 设置面板
 * SPEC: §8.2.6a.11 SettingsPanel
 *
 * 包含: 主题设置、模型选择、权限模式、快捷键等
 * P1b（指南 §13 P1 首批替换）：按钮/输入项换用 @/components/ui 基元与 v2 令牌，
 *      分组标题改 Label 风格（11px 大写 tracking-wider text-t3）；
 *      所有设置项功能、事件逻辑与 DOM 结构不动。
 *      说明：面板容器按 §7.7 Dialog 配方直写令牌（rounded-panel + shadow-e4），
 *      未用 Card 基元——cn(twMerge) 无法合并自定义 rounded-panel 与基元 rounded-2xl，
 *      className 覆盖不可靠。
 */

import React, { useCallback, useEffect } from 'react';
import { X, Moon, Sun, Monitor, Keyboard, Shield, Globe, Sparkles } from 'lucide-react';
import { useConfigStore } from '@/store/configStore';
import { useSessionStore } from '@/store/sessionStore';
import { usePermissionStore } from '@/store/permissionStore';
import { useNotificationStore } from '@/store/notificationStore';
import { useModelStore } from '@/store/modelStore';
import { sendSetPermissionMode } from '@/api/stompClient';
import { isSessionBound } from '@/api/dispatch';
import { Button, Kbd, cn } from '@/components/ui';
import type { ThemeConfig, PermissionMode } from '@/types';

/** 分组标题 Label 风格（§3.8 Label：11px / 600 / 大写 / +0.06~0.08em）
 *  颜色取 text-t2 而非任务书字面 text-t3：text-t3 实测对比度 3.89:1 不达 §10.1 AA 红线
 * （≥4.5:1），text-t2 实测 ≥7:1；对比度红线优先于色级偏好（详见提交报告）。 */
const sectionTitleClass =
    'text-[11px] font-semibold uppercase tracking-wider text-t2 mb-3 flex items-center gap-2';

/** 原生 select 复用 Input 基元配方（凹陷井 + 3px accent focus ring，§6.2） */
const selectClass =
    'w-full h-9 px-3 rounded-xl bg-sunken2 shadow-well border border-transparent text-sm text-t1 ' +
    'transition-surface duration-fast focus:outline-none focus:ring-[3px] focus:ring-accent2-ring ' +
    'disabled:opacity-50 disabled:pointer-events-none';

interface SettingsPanelProps {
    onClose: () => void;
}

export const SettingsPanel: React.FC<SettingsPanelProps> = ({ onClose }) => {
    const { theme, setTheme, locale, setLocale } = useConfigStore();
    const { sessionId, model, setModel, effortValue, setEffort } = useSessionStore();
    const {
        models: availableModels,
        loaded,
        loading: modelsLoading,
        error: modelsError,
        fetchModels,
    } = useModelStore();
    const { permissionMode } = usePermissionStore();
    const addNotification = useNotificationStore(state => state.addNotification);
    const hasBoundSession = Boolean(sessionId && isSessionBound(sessionId));
    const isMac = navigator.platform.includes('Mac');

    useEffect(() => {
        if (loaded) return;
        void fetchModels();
    }, [loaded, fetchModels]);

    const handleThemeChange = useCallback((mode: ThemeConfig['mode']) => {
        setTheme({ mode });
    }, [setTheme]);

    const handlePermissionModeChange = useCallback((mode: PermissionMode) => {
        if (!hasBoundSession) {
            addNotification({
                key: 'permission-mode-no-session',
                level: 'error',
                message: '请先创建或选择会话，再切换权限模式',
            });
            return;
        }
        if (!sendSetPermissionMode(mode.toUpperCase())) {
            addNotification({
                key: 'permission-mode-send-failed',
                level: 'error',
                message: '权限模式切换发送失败，请检查连接后重试',
            });
        }
    }, [addNotification, hasBoundSession]);

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-overlay2 backdrop-blur-sm">
            <div className="w-full max-w-2xl mx-4 max-h-[80vh] rounded-panel border border-hairline
                            bg-surfacev2 shadow-e4 overflow-hidden flex flex-col">
                {/* Header */}
                <div className="px-6 py-4 border-b border-hairline flex items-center justify-between">
                    <h2 className="text-base font-semibold text-t1">设置</h2>
                    <Button variant="ghost" size="sm" iconOnly onClick={onClose} aria-label="关闭设置">
                        <X className="w-5 h-5" />
                    </Button>
                </div>

                {/* Content */}
                <div className="flex-1 overflow-y-auto p-6 space-y-8">
                    {/* Theme Section */}
                    <section>
                        <h3 className={sectionTitleClass}>
                            <Sun className="w-4 h-4" />
                            主题
                        </h3>
                        <div className="grid grid-cols-4 gap-3">
                            <ThemeOption
                                icon={Sun}
                                label="浅色"
                                selected={theme.mode === 'light'}
                                onClick={() => handleThemeChange('light')}
                            />
                            <ThemeOption
                                icon={Moon}
                                label="深色"
                                selected={theme.mode === 'dark'}
                                onClick={() => handleThemeChange('dark')}
                            />
                            <ThemeOption
                                icon={Monitor}
                                label="跟随系统"
                                selected={theme.mode === 'system'}
                                onClick={() => handleThemeChange('system')}
                            />
                            <ThemeOption
                                icon={Sparkles}
                                label="液态玻璃"
                                selected={theme.mode === 'glass'}
                                onClick={() => handleThemeChange('glass')}
                            />
                        </div>
                    </section>

                    {/* Model Section */}
                    <section>
                        <h3 className={sectionTitleClass}>
                            <Globe className="w-4 h-4" />
                            模型
                        </h3>
                        <select
                            aria-label="模型选择"
                            value={model || ''}
                            onChange={(e) => setModel(e.target.value)}
                            disabled={modelsLoading || availableModels.length === 0}
                            className={selectClass}
                        >
                            {availableModels.length === 0 && (
                                <option value="">
                                    {modelsLoading ? '模型加载中…'
                                        : modelsError ? '模型列表加载失败' : '暂无可用模型'}
                                </option>
                            )}
                            {availableModels.map(availableModel => (
                                <option key={availableModel.id} value={availableModel.id}>
                                    {availableModel.displayName}
                                </option>
                            ))}
                        </select>
                        {modelsError && (
                            <Button
                                variant="ghost"
                                size="sm"
                                onClick={() => void fetchModels()}
                                className="mt-2 text-accent2-strong hover:underline"
                            >
                                重新加载模型列表
                            </Button>
                        )}

                        {/* Effort Slider */}
                        <div className="mt-4">
                            <label htmlFor="settings-effort" className="text-sm text-t2">
                                努力程度: {effortValue}
                            </label>
                            <input
                                id="settings-effort"
                                type="range"
                                min={1}
                                max={5}
                                value={effortValue}
                                onChange={(e) => setEffort(parseInt(e.target.value))}
                                className="w-full mt-2 accent-accent2"
                            />
                            <div className="flex justify-between text-xs text-t2 mt-1">
                                <span>快速</span>
                                <span>平衡</span>
                                <span>深度</span>
                            </div>
                        </div>
                    </section>

                    {/* Permission Section */}
                    <section>
                        <h3 className={sectionTitleClass}>
                            <Shield className="w-4 h-4" />
                            权限模式
                        </h3>
                        <div className="space-y-2">
                            <PermissionOption
                                mode="default"
                                label="默认模式"
                                description="标准权限控制"
                                selected={permissionMode === 'default'}
                                onClick={() => handlePermissionModeChange('default')}
                                disabled={!hasBoundSession}
                            />
                            <PermissionOption
                                mode="plan"
                                label="计划模式"
                                description="先制定计划再执行"
                                selected={permissionMode === 'plan'}
                                onClick={() => handlePermissionModeChange('plan')}
                                disabled={!hasBoundSession}
                            />
                            <PermissionOption
                                mode="accept_edits"
                                label="接受编辑"
                                description="自动接受编辑操作"
                                selected={permissionMode === 'accept_edits'}
                                onClick={() => handlePermissionModeChange('accept_edits')}
                                disabled={!hasBoundSession}
                            />
                            <PermissionOption
                                mode="dont_ask"
                                label="无需询问"
                                description="不弹窗，需要确认的操作自动拒绝"
                                selected={permissionMode === 'dont_ask'}
                                onClick={() => handlePermissionModeChange('dont_ask')}
                                disabled={!hasBoundSession}
                            />
                            <PermissionOption
                                mode="auto_approve"
                                label="完全访问权限"
                                description="自动批准所有工具权限请求，允许请求工作区外文件和公共互联网；仍执行系统安全与部署限制"
                                selected={permissionMode === 'auto_approve'}
                                onClick={() => handlePermissionModeChange('auto_approve')}
                                disabled={!hasBoundSession}
                                warning
                            />
                        </div>
                        {!hasBoundSession && (
                            <p className="mt-2 text-xs text-t3">
                                请先创建或选择会话后再设置权限模式。
                            </p>
                        )}
                    </section>

                    {/* Language Section */}
                    <section>
                        <h3 className={sectionTitleClass}>
                            <Globe className="w-4 h-4" />
                            语言
                        </h3>
                        <select
                            aria-label="语言"
                            value={locale}
                            onChange={(e) => setLocale(e.target.value)}
                            className={selectClass}
                        >
                            <option value="zh-CN">简体中文</option>
                            <option value="zh-TW">繁體中文</option>
                            <option value="en-US">English</option>
                            <option value="ja-JP">日本語</option>
                        </select>
                    </section>

                    {/* Shortcuts Section */}
                    <section>
                        <h3 className={sectionTitleClass}>
                            <Keyboard className="w-4 h-4" />
                            快捷键
                        </h3>
                        <div className="space-y-2 text-sm">
                            <ShortcutItem keys={['Enter']} description="发送消息" />
                            <ShortcutItem keys={['Shift', 'Enter']} description="换行" />
                            <ShortcutItem keys={['/']} description="打开命令面板" />
                            <ShortcutItem keys={[isMac ? '⌘' : 'Ctrl', 'K']} description="全局命令面板" />
                            <ShortcutItem keys={['Esc']} description="取消/关闭" />
                            <ShortcutItem keys={['Ctrl', 'C']} description="中断生成" />
                        </div>
                    </section>
                </div>

                {/* Footer */}
                <div className="px-6 py-4 border-t border-hairline flex justify-end">
                    <Button variant="primary" onClick={onClose}>
                        完成
                    </Button>
                </div>
            </div>
        </div>
    );
};

// Theme Option Component（自定义选项钮：布局为纵向卡片，非 Button 基元形态；配色与 ThemePicker 一致）
function ThemeOption({
    icon: Icon,
    label,
    selected,
    onClick,
}: {
    icon: typeof Sun;
    label: string;
    selected: boolean;
    onClick: () => void;
}) {
    return (
        <button
            onClick={onClick}
            className={cn(
                'flex flex-col items-center gap-2 p-4 rounded-lg border transition-interactive duration-fast',
                selected
                    ? 'border-accent2 bg-accent2-soft'
                    : 'border-hairline hover:border-accent2-ring hover:bg-hover2'
            )}
        >
            <Icon className={cn('w-5 h-5', selected ? 'text-accent2-strong' : 'text-t2')} />
            <span className="text-sm text-t1">
                {label}
            </span>
        </button>
    );
}

// Permission Option Component
// 注意：label 直接子元素必须是本 button（auto-approve e2e 依赖 getByText(label).locator('..') 定位按钮）
function PermissionOption({
    label,
    description,
    selected,
    onClick,
    disabled,
    warning = false,
}: {
    mode: PermissionMode;
    label: string;
    description: string;
    selected: boolean;
    onClick: () => void;
    disabled: boolean;
    warning?: boolean;
}) {
    return (
        <button
            onClick={onClick}
            disabled={disabled}
            className={cn(
                'w-full px-4 py-3 rounded-lg border text-left transition-interactive duration-fast',
                disabled && 'cursor-not-allowed opacity-50',
                selected
                    ? warning ? 'border-warn bg-warnsoft' : 'border-accent2 bg-accent2-soft'
                    : warning ? 'border-warn hover:bg-warnsoft'
                        : 'border-hairline hover:border-accent2-ring hover:bg-hover2'
            )}
        >
            {/* 选中态经 border + soft 底 + （warning 时）warn 边双编码；文字保持 t1 以满足 §10.1 对比度 */}
            <div className="font-medium text-t1">
                {label}
            </div>
            <div className="text-sm text-t2">{description}</div>
        </button>
    );
}

// Shortcut Item Component
function ShortcutItem({ keys, description }: { keys: string[]; description: string }) {
    return (
        <div className="flex items-center justify-between py-1">
            <span className="text-t2">{description}</span>
            <div className="flex items-center gap-1">
                {keys.map((key, index) => (
                    <React.Fragment key={key}>
                        <Kbd>{key}</Kbd>
                        {index < keys.length - 1 && <span className="text-t3">+</span>}
                    </React.Fragment>
                ))}
            </div>
        </div>
    );
}

export default SettingsPanel;
