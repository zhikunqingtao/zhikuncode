import { getPermissionModeLabel, getPermissionModeDescription } from '@/components/layout/StatusBar';
import { useState } from 'react';
import { McpCapabilityPanel } from './McpCapabilityPanel';
import { PromptsTab } from './PromptsTab';
import { ThemePicker } from '@/components/theme/ThemePicker';
import { useSessionPermissionSelection } from '@/hooks/useSessionPermissionSelection';
import { PERMISSION_MODES } from '@/types';

/** 设置面板 Tab 类型 */
type SettingsTab = 'model' | 'theme' | 'permission' | 'keybindings' | 'mcp' | 'prompts';

interface SettingsTabConfig {
  id: SettingsTab;
  label: string;
  icon: string;
}

const TABS: SettingsTabConfig[] = [
  { id: 'model', label: 'Model', icon: '🤖' },
  { id: 'theme', label: 'Theme', icon: '🎨' },
  { id: 'permission', label: 'Permissions', icon: '🔒' },
  { id: 'keybindings', label: 'Keybindings', icon: '⌨️' },
  { id: 'mcp', label: 'MCP Tools', icon: '🔌' },
  { id: 'prompts', label: 'Prompts', icon: '📝' },
];

/**
 * SettingsPanel — 图形化设置界面。
 *
 * Tab:
 * 1. Model — 模型选择下拉框
 * 2. Theme — 主题切换（亮/暗/系统）
 * 3. Permissions — 权限模式选择
 * 4. Keybindings — 快捷键编辑
 *
 */
export function SettingsPanel() {
  const [activeTab, setActiveTab] = useState<SettingsTab>('model');

  return (
    <div className="settings-panel flex flex-col h-full min-w-0">
      {/* Tab 导航 */}
      <div className="settings-tabs flex shrink-0 overflow-x-auto border-b border-hairline">
        {TABS.map((tab) => (
          <button
            key={tab.id}
            className={`panel-control settings-tab shrink-0 whitespace-nowrap px-4 py-2 text-sm font-medium transition-interactive duration-fast
              focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring
              ${activeTab === tab.id
                ? 'border-b-2 border-accent2 text-accent2-ink dark:text-accent2-ink'
                : 'text-t3 hover:text-t1'
              }`}
            onClick={() => setActiveTab(tab.id)}
          >
            <span className="mr-1">{tab.icon}</span>
            {tab.label}
          </button>
        ))}
      </div>

      {/* Tab 内容 */}
      <div className="settings-content flex-1 overflow-y-auto p-4">
        {activeTab === 'model' && <ModelPicker />}
        {activeTab === 'theme' && <ThemePicker />}
        {activeTab === 'permission' && <PermissionModePicker />}
        {activeTab === 'keybindings' && <KeybindingsEditor />}
        {activeTab === 'mcp' && <McpCapabilityPanel />}
        {activeTab === 'prompts' && <PromptsTab />}
      </div>
    </div>
  );
}

/** 模型选择下拉框 */
function ModelPicker() {
  const [model, setModel] = useState('deepseek-v4.1-flash');
  const models = [
    { id: 'qwen3.8-max-0902', name: 'Qwen 3.8 Max 0902', description: '最强推理' },
    { id: 'qwen3.8-max', name: 'Qwen 3.8 Max（百炼）', description: '百炼订阅' },
    { id: 'qwen3.8-flash', name: 'Qwen 3.8 Flash（百炼）', description: '百炼订阅 · 快速多模态' },
    { id: 'deepseek-v4.1-flash', name: 'DeepSeek V4.1 Flash（百炼）', description: '默认模型 · 百炼订阅 · 多模态' },
    { id: 'deepseek-flash', name: 'DeepSeek V4.1 Flash', description: '高性能 · 快速 · 原生多模态' },
    { id: 'deepseek-v4-pro-0813', name: 'DeepSeek V4 Pro 0813（百炼）', description: '百炼深度推理' },
    { id: 'deepseek-v4-flash-0731', name: 'DeepSeek V4 Flash 0731（百炼）', description: '百炼快速响应' },
    { id: 'kimi-k3', name: 'Kimi K3', description: '长文本理解' },
    { id: 'k3', name: 'Kimi K3（订阅）', description: '1M 上下文 · 最强推理' },
    { id: 'kimi-for-coding', name: 'Kimi K2.8 Preview（订阅）', description: '1M 上下文 · 最强推理' },
    { id: 'kimi-k2.7-code', name: 'Kimi K2.7 Code', description: '长文本理解' },
    { id: 'moonshot-v1-128k', name: 'Moonshot V1 128K', description: '128K上下文' },
    { id: 'glm-5.3', name: 'GLM-5.3', description: '智谱最新' },
    { id: 'bailian/glm-5.3', name: 'GLM-5.3（百炼）', description: '1M 上下文 · 最强推理' },
    { id: 'glm-5.3-flash', name: 'GLM-5.3-Flash', description: '智谱多模态编程模型' },
    { id: 'MiniMax-M3', name: 'MiniMax M3', description: '百万上下文' },
    { id: 'anthropic/claude-opus-4.8', name: 'claude-opus-4.8', description: '1M上下文 · 编程旗舰' },
    { id: 'anthropic/claude-fable-5.1', name: 'claude-fable-5.1', description: '1M上下文 · Mythos级' },
    { id: 'openai/gpt-5.6-sol', name: 'OpenAI GPT-5.6 Sol', description: 'OpenAI 旗舰模型' },
  ];

  return (
    <div className="space-y-4">
      <h3 className=" text-base font-semibold">Model Selection</h3>
      <select
        aria-label="Model Selection"
        value={model}
        onChange={(e) => setModel(e.target.value)}
        className="panel-control w-full p-2 border border-hairline rounded-xl bg-sunken2 shadow-well text-t1
                   transition-surface duration-fast
                   focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring"
      >
        {models.map((m) => (
          <option key={m.id} value={m.id}>
            {m.name} — {m.description}
          </option>
        ))}
      </select>
    </div>
  );
}

/** 权限模式选择 */
function PermissionModePicker() {
  const { permissionMode, selectMode, pending, disabled, message } = useSessionPermissionSelection();
  const modes = PERMISSION_MODES.map(id => ({
    id, name: getPermissionModeLabel(id), description: getPermissionModeDescription(id),
  }));

  return (
    <div className="space-y-4">
      <h3 className=" text-base font-semibold">权限</h3>
      {(pending || message) && <p role="status">{pending ? '正在切换' : message}</p>}
      {modes.map((m) => (
        <label
          key={m.id}
          className={`flex items-center p-3 rounded-xl border cursor-pointer transition-interactive duration-fast
            ${permissionMode === m.id
              ? 'border-accent2 bg-accent2-soft'
              : 'border-hairline hover:bg-hover2'
            }`}
        >
          <input
            type="radio"
            name="permission-mode"
            value={m.id}
            checked={permissionMode === m.id}
            disabled={disabled}
            onChange={() => selectMode(m.id)}
            className="mr-3 accent-accent2"
          />
          <div>
            <div className="font-medium">{m.name}</div>
            <div className="text-sm text-t3">{m.description}</div>
          </div>
        </label>
      ))}
    </div>
  );
}

/** 快捷键编辑 */
function KeybindingsEditor() {
  const isMac = navigator.platform.includes('Mac');
  const defaultBindings = [
    { key: 'Enter', action: 'chat:submit', context: 'Chat' },
    { key: 'Escape', action: 'chat:cancel', context: 'Chat' },
    { key: 'Ctrl+C', action: 'app:interrupt', context: 'Global' },
    { key: isMac ? '⌘+K' : 'Ctrl+K', action: 'chat:modelPicker', context: 'Chat' },
    { key: isMac ? '⌘+Shift+P' : 'Ctrl+Shift+P', action: 'app:quickOpen', context: 'Global' },
    { key: 'Shift+Tab', action: 'chat:cycleMode', context: 'Chat' },
    { key: isMac ? '⌘+Shift+T' : 'Ctrl+Shift+T', action: 'app:toggleTodos', context: 'Global' },
    { key: 'Tab', action: 'autocomplete:accept', context: 'Autocomplete' },
    { key: 'PageUp', action: 'scroll:pageUp', context: 'Scroll' },
    { key: 'PageDown', action: 'scroll:pageDown', context: 'Scroll' },
  ];

  return (
    <div className="space-y-4">
      <h3 className=" text-base font-semibold">Keyboard Shortcuts</h3>
      <table className="w-full text-sm">
        <thead>
          <tr className="text-left border-b border-hairline">
            <th className="pb-2 pr-4">Key</th>
            <th className="pb-2 pr-4">Action</th>
            <th className="pb-2">Context</th>
          </tr>
        </thead>
        <tbody>
          {defaultBindings.map((binding, index) => (
            <tr
              key={index}
              className="border-b border-hairline"
            >
              <td className="py-2 pr-4">
                <kbd className="px-2 py-1 bg-sunken2 border border-hairline rounded text-[13px] font-mono">
                  {binding.key}
                </kbd>
              </td>
              <td className="py-2 pr-4 font-mono text-[13px]">{binding.action}</td>
              <td className="py-2 text-t3">{binding.context}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default SettingsPanel;
