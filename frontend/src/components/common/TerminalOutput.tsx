import { useEffect, useRef } from 'react';

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

import { XTERM_ANSI, ANSI_CODE_TO_KEY } from '@/styles/design-tokens';
import { getEffectiveTheme } from '@/styles/zkMonaco';

/** 当前主题下的 ANSI 颜色表（§4.3 xterm zk 色板，替代原写死深色表） */
function ansiColors(): Record<string, string> {
    const palette = XTERM_ANSI[getEffectiveTheme()];
    const map: Record<string, string> = {};
    for (const [code, key] of Object.entries(ANSI_CODE_TO_KEY)) {
        map[code] = palette[key];
    }
    return map;
}

/**
 * 将 ANSI 转义序列转换为 HTML span。
 */
function ansiToHtml(text: string): string {
  const colors = ansiColors();
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

  // 截断超长输出
  const lines = content.split('\n');
  const truncated = lines.length > maxLines;
  const displayContent = truncated
    ? lines.slice(0, maxLines).join('\n') + `\n... [truncated ${lines.length - maxLines} lines]`
    : content;

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
      dangerouslySetInnerHTML={{ __html: ansiToHtml(displayContent) }}
    />
  );
}

export default TerminalOutput;
