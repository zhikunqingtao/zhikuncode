/**
 * SettingsPanel — 设置面板
 * SPEC: §8.2.6a.11 SettingsPanel
 *
 * 包含: 主题设置、模型选择、权限模式、快捷键等
 */

import React, { useCallback } from 'react';
import { X, Moon, Sun, Monitor, Keyboard, Shield, Globe, Sparkles } from 'lucide-react';
import { useConfigStore } from '@/store/configStore';
import { useSessionStore } from '@/store/sessionStore';
import { usePermissionStore } from '@/store/permissionStore';
import type { ThemeConfig, PermissionMode } from '@/types';

interface SettingsPanelProps {
    onClose: () => void;
}

export const SettingsPanel: React.FC<SettingsPanelProps> = ({ onClose }) => {
    const { theme, setTheme, locale, setLocale } = useConfigStore();
    const { model, setModel, effortValue, setEffort } = useSessionStore();
    const { permissionMode, setPermissionMode } = usePermissionStore();

    const handleThemeChange = useCallback((mode: ThemeConfig['mode']) => {
        setTheme({ mode });
    }, [setTheme]);

    const handlePermissionModeChange = useCallback((mode: PermissionMode) => {
        setPermissionMode(mode);
    }, [setPermissionMode]);

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
            <div className="w-full max-w-2xl mx-4 max-h-[80vh] rounded-xl border border-[var(--border)] 
                            bg-[var(--bg-primary)] shadow-2xl overflow-hidden flex flex-col">
                {/* Header */}
                <div className="px-6 py-4 border-b border-[var(--border)] flex items-center justify-between">
                    <h2 className="text-lg font-semibold text-[var(--text-primary)]">设置</h2>
                    <button
                        onClick={onClose}
                        className="p-2 rounded-lg hover:bg-[var(--bg-hover)] text-[var(--text-muted)]"
                    >
                        <X className="w-5 h-5" />
                    </button>
                </div>

                {/* Content */}
                <div className="flex-1 overflow-y-auto p-6 space-y-8">
                    {/* Theme Section */}
                    <section>
                        <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-3 flex items-center gap-2">
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
                        <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-3 flex items-center gap-2">
                            <Globe className="w-4 h-4" />
                            模型
                        </h3>
                        <select
                            value={model || ''}
                            onChange={(e) => setModel(e.target.value)}
                            className="w-full px-3 py-2 rounded-lg border border-[var(--border)]
                                bg-[var(--bg-secondary)] text-[var(--text-primary)]
                                focus:outline-none focus:ring-2 focus:ring-blue-500"
                        >
                            <option value="qwen3.6-plus">Qwen 3.6 Plus</option>
                            <option value="qwen3.6-max">Qwen 3.6 Max</option>
                            <option value="qwen3.6-turbo">Qwen 3.6 Turbo</option>
                            <option value="claude-3-5-sonnet">Claude 3.5 Sonnet</option>
                            <option value="gpt-4">GPT-4</option>
                            <option value="gpt-4-turbo">GPT-4 Turbo</option>
                        </select>

                        {/* Effort Slider */}
                        <div className="mt-4">
                            <label className="text-sm text-[var(--text-secondary)]">
                                努力程度: {effortValue}
                            </label>
                            <input
                                type="range"
                                min={1}
                                max={5}
                                value={effortValue}
                                onChange={(e) => setEffort(parseInt(e.target.value))}
                                className="w-full mt-2"
                            />
                            <div className="flex justify-between text-xs text-[var(--text-muted)] mt-1">
                                <span>快速</span>
                                <span>平衡</span>
                                <span>深度</span>
                            </div>
                        </div>
                    </section>

                    {/* Permission Section */}
                    <section>
                        <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-3 flex items-center gap-2">
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
                            />
                            <PermissionOption
                                mode="plan"
                                label="计划模式"
                                description="先制定计划再执行"
                                selected={permissionMode === 'plan'}
                                onClick={() => handlePermissionModeChange('plan')}
                            />
                            <PermissionOption
                                mode="accept_edits"
                                label="接受编辑"
                                description="自动接受编辑操作"
                                selected={permissionMode === 'accept_edits'}
                                onClick={() => handlePermissionModeChange('accept_edits')}
                            />
                            <PermissionOption
                                mode="dont_ask"
                                label="无需询问"
                                description="低风险操作不询问"
                                selected={permissionMode === 'dont_ask'}
                                onClick={() => handlePermissionModeChange('dont_ask')}
                            />
                        </div>
                    </section>

                    {/* Language Section */}
                    <section>
                        <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-3 flex items-center gap-2">
                            <Globe className="w-4 h-4" />
                            语言
                        </h3>
                        <select
                            value={locale}
                            onChange={(e) => setLocale(e.target.value)}
                            className="w-full px-3 py-2 rounded-lg border border-[var(--border)]
                                bg-[var(--bg-secondary)] text-[var(--text-primary)]
                                focus:outline-none focus:ring-2 focus:ring-blue-500"
                        >
                            <option value="zh-CN">简体中文</option>
                            <option value="zh-TW">繁體中文</option>
                            <option value="en-US">English</option>
                            <option value="ja-JP">日本語</option>
                        </select>
                    </section>

                    {/* Shortcuts Section */}
                    <section>
                        <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-3 flex items-center gap-2">
                            <Keyboard className="w-4 h-4" />
                            快捷键
                        </h3>
                        <div className="space-y-2 text-sm">
                            <ShortcutItem keys={['Enter']} description="发送消息" />
                            <ShortcutItem keys={['Shift', 'Enter']} description="换行" />
                            <ShortcutItem keys={['/']} description="打开命令面板" />
                            <ShortcutItem keys={['Esc']} description="取消/关闭" />
                            <ShortcutItem keys={['Ctrl', 'C']} description="中断生成" />
                        </div>
                    </section>
                </div>

                {/* Footer */}
                <div className="px-6 py-4 border-t border-[var(--border)] flex justify-end">
                    <button
                        onClick={onClose}
                        className="px-4 py-2 rounded-lg bg-blue-600 hover:bg-blue-700 text-white text-sm"
                    >
                        完成
                    </button>
                </div>
            </div>
        </div>
    );
};

// Theme Option Component
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
            className={`flex flex-col items-center gap-2 p-4 rounded-lg border transition-all
                ${selected
                    ? 'border-blue-500 bg-blue-500/10'
                    : 'border-[var(--border)] hover:border-blue-500/50 hover:bg-[var(--bg-hover)]'
                }`}
        >
            <Icon className={`w-5 h-5 ${selected ? 'text-blue-500' : 'text-[var(--text-secondary)]'}`} />
            <span className={`text-sm ${selected ? 'text-blue-500' : 'text-[var(--text-primary)]'}`}>
                {label}
            </span>
        </button>
    );
}

// Permission Option Component
function PermissionOption({
    label,
    description,
    selected,
    onClick,
}: {
    mode: PermissionMode;
    label: string;
    description: string;
    selected: boolean;
    onClick: () => void;
}) {
    return (
        <button
            onClick={onClick}
            className={`w-full px-4 py-3 rounded-lg border text-left transition-all
                ${selected
                    ? 'border-blue-500 bg-blue-500/10'
                    : 'border-[var(--border)] hover:border-blue-500/50 hover:bg-[var(--bg-hover)]'
                }`}
        >
            <div className={`font-medium ${selected ? 'text-blue-500' : 'text-[var(--text-primary)]'}`}>
                {label}
            </div>
            <div className="text-sm text-[var(--text-muted)]">{description}</div>
        </button>
    );
}

// Shortcut Item Component
function ShortcutItem({ keys, description }: { keys: string[]; description: string }) {
    return (
        <div className="flex items-center justify-between py-1">
            <span className="text-[var(--text-secondary)]">{description}</span>
            <div className="flex items-center gap-1">
                {keys.map((key, index) => (
                    <React.Fragment key={key}>
                        <kbd className="px-2 py-0.5 bg-[var(--bg-secondary)] border border-[var(--border)]
                            rounded text-xs text-[var(--text-primary)]">
                            {key}
                        </kbd>
                        {index < keys.length - 1 && <span className="text-[var(--text-muted)]">+</span>}
                    </React.Fragment>
                ))}
            </div>
        </div>
    );
}

export default SettingsPanel;
