/**
 * zkMonaco — Monaco zk 主题注册与生效主题解析（指南 §4.2/§4.5）。
 *
 * - `ensureZkMonacoThemes(monaco)`：注册 zk-light / zk-dark 两个主题
 *   （供 @monaco-editor/react 的 `beforeMount` 使用）。可重复调用：
 *   每次按当前 store 的 accentColor 重新 defineTheme，accent 变化时编辑器跟随。
 * - 首次注册后订阅 configStore 的 theme.mode / theme.accentColor：
 *   变化时重注册两主题并 `monaco.editor.setTheme(...)` 热切换全部已挂载编辑器。
 * - `getEffectiveTheme()`：以 documentElement 已应用的类为准（dark→dark，其余含 glass→light），
 *   与 ThemeProvider/resolveTheme 语义一致；`zkMonacoTheme()` 返回当前应使用的主题名。
 */
import type * as MonacoNS from 'monaco-editor';
import { getMonacoZkThemes, resolveTheme } from './design-tokens';
import { useConfigStore } from '@/store/configStore';

let monacoInstance: typeof MonacoNS | null = null;
let storeSubscribed = false;

/** 按当前 store 的 accent 注册/覆盖 zk-light / zk-dark（monaco 实例由 loader 提供，单例） */
export function ensureZkMonacoThemes(monaco: typeof MonacoNS): void {
    monacoInstance = monaco;
    const accentHex = useConfigStore.getState().theme.accentColor;
    monaco.editor.defineTheme('zk-light', getMonacoZkThemes('light', accentHex) as MonacoNS.editor.IStandaloneThemeData);
    monaco.editor.defineTheme('zk-dark', getMonacoZkThemes('dark', accentHex) as MonacoNS.editor.IStandaloneThemeData);
    subscribeThemeChanges();
}

/**
 * 订阅 theme.mode / theme.accentColor：变化时以新 accent 重注册两主题，
 * 再 setTheme 到当前生效主题名——同名 setTheme 会重新应用刚覆盖的定义，
 * 使全部已挂载编辑器即时切换（无需重建编辑器实例）。
 */
function subscribeThemeChanges(): void {
    if (storeSubscribed) return;
    storeSubscribed = true;
    useConfigStore.subscribe(
        (s) => s.theme,
        (theme, prevTheme) => {
            if (!monacoInstance) return;
            if (theme.mode === prevTheme.mode && theme.accentColor === prevTheme.accentColor) return;
            ensureZkMonacoThemes(monacoInstance);
            monacoInstance.editor.setTheme(resolveTheme(theme.mode) === 'dark' ? 'zk-dark' : 'zk-light');
        },
    );
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
