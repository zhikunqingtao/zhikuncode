package com.aicodeassistant.keybinding;

import java.util.List;

/**
 * 和弦解析结果 — 多键组合如 "ctrl+x ctrl+k" 的解析状态。
 * <p>
 * 对应源码: src/keybindings/resolver.ts ChordResolveResult
 *
 * @see <a href="SPEC §4.8.1">和弦序列</a>
 */
public sealed interface ChordResolveResult {

    /** 完整匹配 — 返回动作 */
    record Match(String action) implements ChordResolveResult {}

    /** 无匹配 — 正常输入 */
    record None() implements ChordResolveResult {}

    /** 匹配到 null 解绑 */
    record Unbound() implements ChordResolveResult {}

    /** 前缀匹配 — 等待后续按键 */
    record ChordStarted(List<ParsedKeystroke> pending) implements ChordResolveResult {}

    /** 和弦取消 — Escape 或无效键 */
    record ChordCancelled() implements ChordResolveResult {}
}
