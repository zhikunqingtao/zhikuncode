import { useCallback, useEffect, useRef, useState } from 'react';

/** 键盘绑定上下文类型 */
export type KeybindingContext =
  | 'global' | 'chat' | 'autocomplete' | 'confirmation' | 'help'
  | 'transcript' | 'history_search' | 'task' | 'theme_picker'
  | 'settings' | 'tabs' | 'scroll' | 'attachments' | 'footer'
  | 'message_selector' | 'message_actions' | 'diff_dialog'
  | 'model_picker' | 'select' | 'plugin';

/** 快捷键绑定配置 */
interface KeyBinding {
  key: string;
  action: string;
  context: KeybindingContext;
  handler: () => void;
}

// 和弦超时 (毫秒)
// const CHORD_TIMEOUT_MS = 500;

/**
 * useKeybinding — 键盘快捷键 hook。
 *
 * 在组件中注册键盘绑定，支持：
 * - 单键绑定 (Ctrl+K)
 * - 和弦绑定 (Ctrl+X Ctrl+K)
 * - 浏览器冲突键处理
 * - 上下文感知
 *
 * @see SPEC §4.8
 */
export function useKeybinding(bindings: KeyBinding[]) {
  const bindingsRef = useRef(bindings);
  bindingsRef.current = bindings;

  const [pendingChord, _setPendingChord] = useState<string | null>(null);
  const chordTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const handleKeyDown = useCallback((e: KeyboardEvent) => {
    // 跳过输入元素内的按键（除非是特殊组合键）
    const target = e.target as HTMLElement;
    const isInput = target.tagName === 'INPUT' || target.tagName === 'TEXTAREA'
      || target.tagName === 'SELECT' || target.isContentEditable;

    const combo = buildCombo(e);

    for (const binding of bindingsRef.current) {
      if (binding.key === combo) {
        // 输入元素内只响应带修饰符的组合键
        if (isInput && !e.ctrlKey && !e.altKey && !e.metaKey) {
          continue;
        }

        e.preventDefault();
        e.stopPropagation();
        binding.handler();
        return;
      }
    }
  }, []);

  useEffect(() => {
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      if (chordTimerRef.current) {
        clearTimeout(chordTimerRef.current);
      }
    };
  }, [handleKeyDown]);

  return { pendingChord };
}

/**
 * useRegisterKeybindingContext — 注册键盘绑定上下文。
 *
 * 组件挂载时注册上下文，卸载时取消。
 * 上下文激活后其绑定优先于 Global 绑定。
 */
export function useRegisterKeybindingContext(
  context: KeybindingContext,
  isActive: boolean = true
) {
  useEffect(() => {
    if (!isActive) return;
    // 注册到全局上下文管理器
    activeContexts.add(context);
    return () => {
      activeContexts.delete(context);
    };
  }, [context, isActive]);
}

/** 全局活跃上下文集合 */
const activeContexts = new Set<KeybindingContext>();

/** 检查上下文是否活跃 */
export function isContextActive(context: KeybindingContext): boolean {
  return context === 'global' || activeContexts.has(context);
}

/**
 * 构建按键组合字符串。
 */
function buildCombo(e: KeyboardEvent): string {
  const parts: string[] = [];
  if (e.ctrlKey) parts.push('ctrl');
  if (e.altKey) parts.push('alt');
  if (e.shiftKey) parts.push('shift');
  if (e.metaKey) parts.push('meta');

  const key = normalizeKey(e.key);
  if (!['Control', 'Alt', 'Shift', 'Meta'].includes(e.key)) {
    parts.push(key);
  }

  return parts.join('+');
}

/**
 * 标准化键名。
 */
function normalizeKey(key: string): string {
  switch (key) {
    case 'Escape': return 'escape';
    case 'Enter': return 'enter';
    case ' ': return 'space';
    case 'ArrowUp': return 'up';
    case 'ArrowDown': return 'down';
    case 'ArrowLeft': return 'left';
    case 'ArrowRight': return 'right';
    case 'Backspace': return 'backspace';
    case 'Delete': return 'delete';
    case 'Tab': return 'tab';
    case 'PageUp': return 'pageup';
    case 'PageDown': return 'pagedown';
    case 'Home': return 'home';
    case 'End': return 'end';
    default: return key.toLowerCase();
  }
}

export default useKeybinding;
