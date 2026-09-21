import mermaid from 'mermaid';
import { TOKENS, getChartColors } from '@/styles/design-tokens';

/** 图表字族（与 §3.8 sans 栈一致） */
const MERMAID_FONT =
  'Inter, -apple-system, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Noto Sans CJK SC", system-ui, sans-serif';

// 浅色主题（§4.4：全部取自 design-tokens，hairline 实色化边框）
// pie1-8 不含在基底内：由 buildThemeVariables 按当前 accent 动态注入
const lightThemeVariablesBase = {
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
  fontFamily: MERMAID_FONT,
  fontSize: '13px',
};

// 深色主题（§4.4：蓝黑中性面 + Dark 提亮数据色）
const darkThemeVariablesBase = {
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
  fontFamily: MERMAID_FONT,
  fontSize: '13px',
};

/**
 * 组装 themeVariables：基底 + pie1-8 取 getChartColors(mode, accentHex)
 * （chart-1 跟随当前 accent；缺省/未知 accent 回退青瓷默认）。
 */
function buildThemeVariables(mode: 'light' | 'dark', accentHex?: string) {
  const colors = getChartColors(mode, accentHex);
  const base = mode === 'dark' ? darkThemeVariablesBase : lightThemeVariablesBase;
  return {
    ...base,
    pie1: colors[0], pie2: colors[1], pie3: colors[2],
    pie4: colors[3], pie5: colors[4], pie6: colors[5],
    pie7: colors[6], pie8: colors[7],
  };
}

/**
 * 初始化 Mermaid（§4.4）。可重复调用（mermaid.initialize 覆盖式生效），
 * 主题/强调色变化时由调用方重调以注入新色板。
 * @param theme effectiveTheme（'light'|'dark'，由 resolveTheme 解析；Glass→light、System→落类）
 * @param accentHex 当前强调色（theme.accentColor），pie1 跟随
 */
export function initMermaid(theme: 'light' | 'dark', accentHex?: string) {
  mermaid.initialize({
    startOnLoad: false,
    securityLevel: 'strict',
    theme: 'base',
    themeVariables: buildThemeVariables(theme, accentHex),
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
