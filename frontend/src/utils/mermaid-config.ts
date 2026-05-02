import mermaid from 'mermaid';

// 浅色主题配置
const lightThemeVariables = {
  primaryColor: '#3b82f6',      // blue-500
  primaryTextColor: '#1e293b',  // slate-800
  primaryBorderColor: '#93c5fd', // blue-300
  lineColor: '#64748b',         // slate-500
  secondaryColor: '#f1f5f9',    // slate-100
  tertiaryColor: '#e2e8f0',     // slate-200
  background: '#ffffff',
  mainBkg: '#f8fafc',           // slate-50
  nodeBorder: '#cbd5e1',        // slate-300
  clusterBkg: '#f1f5f9',
  titleColor: '#0f172a',        // slate-900
  edgeLabelBackground: '#ffffff',
};

// 深色主题配置
const darkThemeVariables = {
  primaryColor: '#3b82f6',
  primaryTextColor: '#e2e8f0',
  primaryBorderColor: '#1e40af',
  lineColor: '#94a3b8',
  secondaryColor: '#1e293b',
  tertiaryColor: '#334155',
  background: '#0f172a',
  mainBkg: '#1e293b',
  nodeBorder: '#475569',
  clusterBkg: '#1e293b',
  titleColor: '#f1f5f9',
  edgeLabelBackground: '#1e293b',
};

export function initMermaid(isDark: boolean) {
  mermaid.initialize({
    startOnLoad: false,
    securityLevel: 'strict',
    theme: 'base',
    themeVariables: isDark ? darkThemeVariables : lightThemeVariables,
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
