package com.aicodeassistant.tool.bash.ast;

import java.util.List;

/**
 * 安全分析结果 — 唯一权威定义。
 * <p>
 * 对齐源码 ast.ts ParseForSecurityResult 联合类型。
 * <p>
 * BashTool 安全解析的最终输出，回答唯一问题：
 * "能否为此命令字符串中的每个简单命令生成可信的 argv[]？"
 * <ul>
 *   <li>Simple → 可安全提取命令列表，交权限管线判定</li>
 *   <li>TooComplex → 含 DANGEROUS_TYPES 或预检查失败，需用户确认</li>
 *   <li>ParseUnavailable → 解析器未加载或命令过长 (&gt;10000字符)</li>
 * </ul>
 *
 * @see <a href="SPEC §3.2.3c.2">AST 安全分析</a>
 */
public sealed interface ParseForSecurityResult {

    /**
     * 解析成功且安全分析通过 — 所有命令均可提取可信 argv[]。
     * <p>
     * 下游代码将对每个 SimpleCommandNode.argv[0] 匹配权限规则 (§3.4.2 PermissionRule)。
     *
     * @param commands 提取的简单命令列表（叶子节点）
     */
    record Simple(List<BashAstNode.SimpleCommandNode> commands) implements ParseForSecurityResult {}

    /**
     * 解析成功但安全分析拒绝深入 — 含危险/复杂结构，需用户确认。
     * <p>
     * 触发条件: DANGEROUS_TYPES (arithmetic_expansion, process_substitution 等)
     * 或预检查失败 (控制字符、Unicode 空白、反斜杠空白等)。
     *
     * @param reason   拒绝原因描述
     * @param nodeType 触发 too-complex 的 AST 节点类型
     */
    record TooComplex(String reason, String nodeType) implements ParseForSecurityResult {}

    /**
     * 解析器不可用 — 回退到遗留命令分类路径。
     * <p>
     * 触发条件: 解析器超时 (50ms)、节点预算耗尽 (50000)、命令过长 (&gt;10000 字符)。
     */
    record ParseUnavailable() implements ParseForSecurityResult {}
}
