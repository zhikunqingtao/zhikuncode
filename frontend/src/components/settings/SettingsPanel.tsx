import { useState, useCallback } from 'react';
import { McpCapabilityPanel } from './McpCapabilityPanel';
import { PromptsTab } from './PromptsTab';
import { ThemePicker } from '@/components/theme/ThemePicker';
import { MemoryEditorPanel } from '@/components/memory/MemoryEditorPanel';
import { usePermissionStore } from '@/store/permissionStore';
import { sendSetPermissionMode } from '@/api/stompClient';
import type { PermissionMode } from '@/types';

/** 设置面板 Tab 类型 */
type SettingsTab = 'model' | 'theme' | 'permission' | 'memory' | 'keybindings' | 'mcp' | 'prompts';

interface SettingsTabConfig {
  id: SettingsTab;
  label: string;
  icon: string;
}

const TABS: SettingsTabConfig[] = [
  { id: 'model', label: 'Model', icon: '🤖' },
  { id: 'theme', label: 'Theme', icon: '🎨' },
  { id: 'permission', label: 'Permissions', icon: '🔒' },
  { id: 'memory', label: 'Memory', icon: '🧠' },
  { id: 'keybindings', label: 'Keybindings', icon: '⌨️' },
  { id: 'mcp', label: 'MCP Tools', icon: '🔌' },
  { id: 'prompts', label: 'Prompts', icon: '📝' },
];

/**
 * SettingsPanel — 图形化设置界面。
 *
 * 5 个 Tab:
 * 1. Model — 模型选择下拉框
 * 2. Theme — 主题切换（亮/暗/系统）
 * 3. Permissions — 权限模式选择
 * 4. Memory — 记忆条目管理
 * 5. Keybindings — 快捷键编辑
 *
 */
export function SettingsPanel() {
  const [activeTab, setActiveTab] = useState<SettingsTab>('model');

  return (
    <div className="settings-panel flex flex-col h-full">
      {/* Tab 导航 */}
      <div className="settings-tabs flex border-b border-gray-200 dark:border-gray-700">
        {TABS.map((tab) => (
          <button
            key={tab.id}
            className={`settings-tab px-4 py-2 text-sm font-medium transition-colors
              ${activeTab === tab.id
                ? 'border-b-2 border-blue-500 text-blue-600 dark:text-blue-400'
                : 'text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300'
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
        {activeTab === 'memory' && <MemoryManager />}
        {activeTab === 'keybindings' && <KeybindingsEditor />}
        {activeTab === 'mcp' && <McpCapabilityPanel />}
        {activeTab === 'prompts' && <PromptsTab />}
      </div>
    </div>
  );
}

/** 模型选择下拉框 */
function ModelPicker() {
  const [model, setModel] = useState('qwen3.7-max');
  const models = [
    { id: 'qwen3.7-max', name: 'Qwen 3.7 Max', description: '最强推理' },
    { id: 'qwen3.7-plus', name: 'Qwen 3.7 Plus', description: '均衡性能' },
    { id: 'deepseek-v4-pro', name: 'DeepSeek V4 Pro', description: '深度推理' },
    { id: 'deepseek-v4-flash', name: 'DeepSeek V4 Flash', description: '快速响应' },
    { id: 'kimi-k3', name: 'Kimi K3', description: '长文本理解' },
    { id: 'kimi-k2.7-code', name: 'Kimi K2.7 Code', description: '长文本理解' },
    { id: 'moonshot-v1-128k', name: 'Moonshot V1 128K', description: '128K上下文' },
    { id: 'glm-5.2', name: 'GLM-5.2', description: '智谱最新' },
    { id: 'glm-5v-turbo', name: 'GLM-5V-Turbo', description: '智谱多模态编程模型' },
    { id: 'MiniMax-M3', name: 'MiniMax M3', description: '百万上下文' },
    { id: 'anthropic/claude-opus-4.8', name: 'claude-opus-4.8', description: '1M上下文 · 编程旗舰' },
    { id: 'anthropic/claude-fable-5', name: 'claude-fable-5', description: '1M上下文 · Mythos级' },
    { id: 'openai/gpt-5.6-sol', name: 'OpenAI GPT-5.6 Sol', description: 'OpenAI 旗舰模型' },
    { id: 'google/gemini-3.5-flash', name: 'Google Gemini 3.5 Flash', description: 'Google 快速响应' },
  ];

  return (
    <div className="space-y-4">
      <h3 className="text-lg font-semibold">Model Selection</h3>
      <select
        value={model}
        onChange={(e) => setModel(e.target.value)}
        className="w-full p-2 border rounded dark:bg-gray-800 dark:border-gray-600"
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
  const { permissionMode, setPermissionMode } = usePermissionStore();
  const modes: { id: PermissionMode; name: string; description: string }[] = [
    { id: 'default', name: 'Default', description: 'Ask for each destructive operation' },
    { id: 'plan', name: 'Plan', description: 'Read-only operations auto-allowed' },
    { id: 'accept_edits', name: 'Accept Edits', description: 'File edits auto-allowed' },
    { id: 'dont_ask', name: "Don't Ask", description: 'No prompts, write operations auto-denied' },
  ];

  const handleChange = (mode: PermissionMode) => {
    setPermissionMode(mode);
    // 同步到后端，后端枚举使用大写值
    sendSetPermissionMode(mode.toUpperCase());
  };

  return (
    <div className="space-y-4">
      <h3 className="text-lg font-semibold">Permission Mode</h3>
      {modes.map((m) => (
        <label
          key={m.id}
          className={`flex items-center p-3 rounded border cursor-pointer transition-colors
            ${permissionMode === m.id
              ? 'border-blue-500 bg-blue-50 dark:bg-blue-900/20'
              : 'border-gray-200 dark:border-gray-700 hover:bg-gray-50 dark:hover:bg-gray-800'
            }`}
        >
          <input
            type="radio"
            name="permission-mode"
            value={m.id}
            checked={permissionMode === m.id}
            onChange={() => handleChange(m.id)}
            className="mr-3"
          />
          <div>
            <div className="font-medium">{m.name}</div>
            <div className="text-sm text-gray-500">{m.description}</div>
          </div>
        </label>
      ))}
    </div>
  );
}

/** 记忆条目管理 — 集成 MemoryEditorPanel */
function MemoryManager() {
  const [activeFile, setActiveFile] = useState<'zhikun.md' | 'zhikun.local.md'>('zhikun.md');
  const [globalContent, setGlobalContent] = useState('');
  const [localContent, setLocalContent] = useState('');

  const handleSave = useCallback(async (content: string) => {
    // Save memory file via backend API (not yet connected)
    if (activeFile === 'zhikun.md') {
      setGlobalContent(content);
    } else {
      setLocalContent(content);
    }
  }, [activeFile]);

  return (
    <div className="space-y-4">
      <h3 className="text-lg font-semibold">Memory Manager</h3>
      <p className="text-sm text-gray-500">
        管理跨会话持久化的项目记忆文件。
      </p>
      {/* 文件切换 */}
      <div className="flex gap-2">
        <button
          onClick={() => setActiveFile('zhikun.md')}
          className={`px-3 py-1.5 min-h-10 rounded text-sm transition-colors ${
            activeFile === 'zhikun.md'
              ? 'bg-blue-600 text-white'
              : 'bg-gray-100 dark:bg-gray-800 text-gray-600 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-gray-700'
          }`}
        >
          🌐 zhikun.md (全局)
        </button>
        <button
          onClick={() => setActiveFile('zhikun.local.md')}
          className={`px-3 py-1.5 min-h-10 rounded text-sm transition-colors ${
            activeFile === 'zhikun.local.md'
              ? 'bg-blue-600 text-white'
              : 'bg-gray-100 dark:bg-gray-800 text-gray-600 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-gray-700'
          }`}
        >
          📁 zhikun.local.md (项目)
        </button>
      </div>
      {/* 编辑器 */}
      <div className="h-[400px]">
        <MemoryEditorPanel
          workingDir="."
          initialContent={activeFile === 'zhikun.md' ? globalContent : localContent}
          fileName={activeFile}
          onSave={handleSave}
        />
      </div>
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
      <h3 className="text-lg font-semibold">Keyboard Shortcuts</h3>
      <table className="w-full text-sm">
        <thead>
          <tr className="text-left border-b dark:border-gray-700">
            <th className="pb-2 pr-4">Key</th>
            <th className="pb-2 pr-4">Action</th>
            <th className="pb-2">Context</th>
          </tr>
        </thead>
        <tbody>
          {defaultBindings.map((binding, index) => (
            <tr
              key={index}
              className="border-b border-gray-100 dark:border-gray-800"
            >
              <td className="py-2 pr-4">
                <kbd className="px-2 py-1 bg-gray-100 dark:bg-gray-700 rounded text-xs font-mono">
                  {binding.key}
                </kbd>
              </td>
              <td className="py-2 pr-4 font-mono text-xs">{binding.action}</td>
              <td className="py-2 text-gray-500">{binding.context}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default SettingsPanel;
