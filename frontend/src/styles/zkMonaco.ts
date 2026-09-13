/**
 * zkMonaco — Monaco zk 主题注册与生效主题解析（指南 §4.2/§4.5）。
 *
 * - `ensureZkMonacoThemes(monaco)`：幂等注册 zk-light / zk-dark 两个主题
 *   （供 @monaco-editor/react 的 `beforeMount` 使用）。
 * - `getEffectiveTheme()`：以 documentElement 已应用的类为准（dark→dark，其余含 glass→light），
 *   与 ThemeProvider/resolveTheme 语义一致；`zkMonacoTheme()` 返回当前应使用的主题名。
 */
import type * as MonacoNS from 'monaco-editor';
import { MONACO_ZK_THEMES } from './design-tokens';

let registered = false;

/** 幂等注册 zk-light / zk-dark（monaco 实例由 loader 提供，单例） */
export function ensureZkMonacoThemes(monaco: typeof MonacoNS): void {
    if (registered) return;
    monaco.editor.defineTheme('zk-light', MONACO_ZK_THEMES['zk-light'] as MonacoNS.editor.IStandaloneThemeData);
    monaco.editor.defineTheme('zk-dark', MONACO_ZK_THEMES['zk-dark'] as MonacoNS.editor.IStandaloneThemeData);
    registered = true;
}

/** 当前生效主题（DOM 类为准；glass 无 dark 类 → light；system 由应用方落类） */
export function getEffectiveTheme(): 'light' | 'dark' {
    if (typeof document === 'undefined') return 'light';
    return document.documentElement.classList.contains('dark') ? 'dark' : 'light';
}

/** 当前应使用的 Monaco zk 主题名 */
export function zkMonacoTheme(): 'zk-light' | 'zk-dark' {
    return getEffectiveTheme() === 'dark' ? 'zk-dark' : 'zk-light';
}
