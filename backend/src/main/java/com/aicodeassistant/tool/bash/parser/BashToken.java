package com.aicodeassistant.tool.bash.parser;

import com.aicodeassistant.tool.bash.ast.BashTokenType;

/**
 * Bash 词法 Token — 不可变记录。
 * <p>
 * 携带 Token 类型、文本值、以及 UTF-8 字节偏移位置。
 * 字节偏移用于 AST 节点的 startByte/endByte 填充，与源码 bashParser.ts 的 TsNode 对齐。
 *
 * @param type       Token 类型
 * @param text       Token 原始文本
 * @param startByte  UTF-8 字节起始偏移
 * @param endByte    UTF-8 字节结束偏移 (不含)
 * @param startIndex Java 字符索引 (用于源码访问)
 * @param endIndex   Java 字符索引 (不含)
 *
 * @see <a href="SPEC §3.2.3c.1">Lexer Token 定义</a>
 */
public record BashToken(
        BashTokenType type,
        String text,
        int startByte,
        int endByte,
        int startIndex,
        int endIndex
) {

    /** 判断是否为指定操作符 */
    public boolean isOp(String op) {
        return type == BashTokenType.OP && text.equals(op);
    }

    /** 判断是否为指定关键字 */
    public boolean isWord(String word) {
        return type == BashTokenType.WORD && text.equals(word);
    }

    /** 判断是否为 EOF */
    public boolean isEof() {
        return type == BashTokenType.EOF;
    }

    @Override
    public String toString() {
        return "Token[" + type + " \"" + text + "\" @" + startByte + ".." + endByte + "]";
    }
}
