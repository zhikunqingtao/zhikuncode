/**
 * 内部折叠标记剥离 — 前端兜底
 *
 * 后端上下文折叠机制的标记（[skeleton]、[collapsed]、[content compressed by system] 等）
 * 可能被 LLM 模仿输出。后端 QueryEngine 已在落库前清洗，但流式期间与错误路径
 * （message_complete 不带 committedMessages）展示的是未清洗的原始文本，
 * 此工具与后端清洗规则对齐，供前端终结路径与展示组件兜底剥离。
 */

// 前缀标记：每行行首、支持连续多个、大小写不敏感
const PREFIX_RE = /^\s*(?:\[(?:skeleton|collapsed|summary-collapsed|content compressed by system|content truncated by system|tool result cleared[^\]]*|final)\]\s*)+/gim;

// 后缀标记：结尾锚定的 "... [collapsed: N chars]" 等截断尾巴、支持连续多个
const SUFFIX_RE = /(?:\.\.\.\s*\[(?:collapsed|summary-collapsed|content truncated by system)[^\]]*\]\s*)+$/i;

/**
 * 剥离文本中的内部折叠/截断标记，返回 trim 后的结果。
 * 空输入安全返回原值。
 */
export function stripInternalMarkers(text: string): string {
    if (!text) return text;
    return text.replace(PREFIX_RE, '').replace(SUFFIX_RE, '').trim();
}
