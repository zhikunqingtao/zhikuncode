import mermaid from 'mermaid';
import { TOKENS, CHART_COLORS } from '@/styles/design-tokens';

/** 图表字族（与 §3.8 sans 栈一致） */
const MERMAID_FONT =
  'Inter, -apple-system, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Noto Sans CJK SC", system-ui, sans-serif';

// 浅色主题（§4.4：全部取自 design-tokens，hairline 实色化边框）
const lightThemeVariables = {
  background: TOKENS.light['--v2-bg-app'],
  primaryColor: TOKENS.light['--v2-bg-surface'],
  primaryTextColor: TOKENS.light['--v2-text-1'],
  primaryBorderColor: '#D8DCE4',
  lineColor: TOKENS.light['--v2-text-3'],
  secondaryColor: TOKENS.light['--v2-bg-sunken'],
  tertiaryColor: TOKENS.light['--v2-bg-surface-2'],
  mainBkg: TOKENS.light['--v2-bg-surface'],
  nodeBorder: '#D8DCE4',
  clusterBkg: '#EFF1F480',
  clusterBorder: '#D8DCE4',
  titleColor: TOKENS.light['--v2-text-1'],
  edgeLabelBackground: TOKENS.light['--v2-bg-app'],
  actorBkg: TOKENS.light['--v2-bg-surface'],
  actorBorder: '#D8DCE4',
  actorTextColor: TOKENS.light['--v2-text-1'],
  signalColor: TOKENS.light['--v2-text-2'],
  signalTextColor: TOKENS.light['--v2-text-2'],
  labelBoxBkgColor: TOKENS.light['--v2-bg-sunken'],
  labelBoxBorderColor: '#D8DCE4',
  noteBkgColor: '#FEF9EC',
  noteBorderColor: '#E8D9A0',
  noteTextColor: '#6B5D2E',
  pie1: CHART_COLORS.light[0], pie2: CHART_COLORS.light[1], pie3: CHART_COLORS.light[2],
  pie4: CHART_COLORS.light[3], pie5: CHART_COLORS.light[4], pie6: CHART_COLORS.light[5],
  pie7: CHART_COLORS.light[6], pie8: CHART_COLORS.light[7],
  fontFamily: MERMAID_FONT,
  fontSize: '13px',
};

// 深色主题（§4.4：蓝黑中性面 + Dark 提亮数据色）
const darkThemeVariables = {
  background: TOKENS.dark['--v2-bg-app'],
  primaryColor: TOKENS.dark['--v2-bg-surface'],
  primaryTextColor: TOKENS.dark['--v2-text-1'],
  primaryBorderColor: '#2A3040',
  lineColor: TOKENS.dark['--v2-text-3'],
  secondaryColor: TOKENS.dark['--v2-bg-surface-2'],
  tertiaryColor: '#131720',
  mainBkg: TOKENS.dark['--v2-bg-surface'],
  nodeBorder: '#2A3040',
  clusterBkg: '#1B202980',
  clusterBorder: '#2A3040',
  titleColor: TOKENS.dark['--v2-text-1'],
  edgeLabelBackground: TOKENS.dark['--v2-bg-app'],
  actorBkg: TOKENS.dark['--v2-bg-surface'],
  actorBorder: '#2A3040',
  actorTextColor: TOKENS.dark['--v2-text-1'],
  signalColor: TOKENS.dark['--v2-text-2'],
  signalTextColor: TOKENS.dark['--v2-text-2'],
  labelBoxBkgColor: TOKENS.dark['--v2-bg-surface-2'],
  labelBoxBorderColor: '#2A3040',
  noteBkgColor: '#2A2617',
  noteBorderColor: '#4A4020',
  noteTextColor: '#F0C24E',
  pie1: CHART_COLORS.dark[0], pie2: CHART_COLORS.dark[1], pie3: CHART_COLORS.dark[2],
  pie4: CHART_COLORS.dark[3], pie5: CHART_COLORS.dark[4], pie6: CHART_COLORS.dark[5],
  pie7: CHART_COLORS.dark[6], pie8: CHART_COLORS.dark[7],
  fontFamily: MERMAID_FONT,
  fontSize: '13px',
};

/**
 * 初始化 Mermaid（§4.4）。
 * @param theme effectiveTheme（'light'|'dark'，由 resolveTheme 解析；Glass→light、System→落类）
 */
export function initMermaid(theme: 'light' | 'dark') {
  mermaid.initialize({
    startOnLoad: false,
    securityLevel: 'strict',
    theme: 'base',
    themeVariables: theme === 'dark' ? darkThemeVariables : lightThemeVariables,
    flowchart: { useMaxWidth: true, htmlLabels: true, curve: 'basis' },
    sequence: { useMaxWidth: true, wrap: true },
    gantt: { useMaxWidth: true },
  });
}

export async function renderMermaid(id: string, code: string): Promise<{ svg: string }> {
  const { svg } = await mermaid.render(id, code);
  // 清理 mermaid.render 创建的临时 DOM 节点
  if (typeof document !== 'undefined') {
    const container = document.getElementById(id);
    container?.remove();
  }
  return { svg };
}
