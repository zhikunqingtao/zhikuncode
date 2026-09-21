import { useEffect, useMemo, useRef } from 'react';

/**
 * TerminalOutput — 使用 ANSI 安全渲染终端输出。
 *
 * 轻量实现: 不引入 xterm.js（延迟加载策略），
 * 使用 <pre> + ANSI-to-HTML 转换渲染 BashTool 输出。
 *
 * 完整版使用 xterm.js:
 * - Terminal({ convertEol: true, scrollback: 5000 })
 * - FitAddon 自适应容器
 * - WebLinksAddon 可点击 URL
 *
 */
interface TerminalOutputProps {
  /** 终端输出内容 (可能含 ANSI 转义序列) */
  content: string;
  /** 最大显示行数 */
  maxLines?: number;
  /** 自定义 CSS 类 */
  className?: string;
}

import { ANSI_CODE_TO_KEY, getXtermPalette, resolveTheme } from '@/styles/design-tokens';
import { useConfigStore } from '@/store/configStore';

/** 当前主题+强调色下的 ANSI 颜色表（§4.3 xterm zk 色板，accent 动态派生） */
function ansiColors(mode: 'light' | 'dark', accentHex?: string): Record<string, string> {
    const palette = getXtermPalette(mode, accentHex);
    const map: Record<string, string> = {};
    for (const [code, key] of Object.entries(ANSI_CODE_TO_KEY)) {
        map[code] = palette[key];
    }
    return map;
}

/**
 * 将 ANSI 转义序列转换为 HTML span。
 */
function ansiToHtml(text: string, mode: 'light' | 'dark', accentHex?: string): string {
  const colors = ansiColors(mode, accentHex);
  return text
    // 替换 ANSI 颜色代码
    .replace(/\x1b\[(\d+)m/g, (_, code) => {
      if (code === '0') return '</span>';
      if (code === '1') return '<span style="font-weight:bold">';
      if (code === '3') return '<span style="font-style:italic">';
      if (code === '4') return '<span style="text-decoration:underline">';
      const color = colors[code];
      return color ? `<span style="color:${color}">` : '';
    })
    // 清除其他 ANSI 转义
    .replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '');
}

export function TerminalOutput({
  content,
  maxLines = 5000,
  className = '',
}: TerminalOutputProps) {
  const containerRef = useRef<HTMLPreElement>(null);

  // 主题模式 + 强调色订阅：变化时按新色板重渲染（glass 归一为 light）
  const themeMode = useConfigStore(s => s.theme.mode);
  const accentColor = useConfigStore(s => s.theme.accentColor);
  const mode = resolveTheme(themeMode);

  // 截断超长输出
  const lines = content.split('\n');
  const truncated = lines.length > maxLines;
  const displayContent = truncated
    ? lines.slice(0, maxLines).join('\n') + `\n... [truncated ${lines.length - maxLines} lines]`
    : content;

  // ANSI → HTML 随内容/主题/强调色记忆重算
  const html = useMemo(
    () => ansiToHtml(displayContent, mode, accentColor),
    [displayContent, mode, accentColor]
  );

  // 自动滚动到底部
  useEffect(() => {
    if (containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight;
    }
  }, [content]);

  return (
    <pre
      ref={containerRef}
      className={`terminal-output bg-sunken2 text-t1 p-3 rounded-md
        font-mono text-[13px] leading-5 overflow-auto min-h-[100px] max-h-[500px]
        selection:bg-accent2-soft ${className}`}
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
}

export default TerminalOutput;
