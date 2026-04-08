package com.aicodeassistant.memdir;

/**
 * 记忆分类枚举 — 三类记忆系统。
 * <p>
 * 对标认知心理学的记忆分类模型:
 * <ul>
 *   <li>EPISODIC: 事件记忆 (具体操作历史、调试经过)</li>
 *   <li>SEMANTIC: 语义记忆 (项目知识、用户偏好、技术约定)</li>
 *   <li>PROCEDURAL: 程序记忆 (常用工作流、部署流程、构建步骤)</li>
 * </ul>
 *
 * @see <a href="SPEC §4.11">Memdir 自动记忆系统</a>
 */
public enum MemoryCategory {

    /** 事件记忆 — 具体操作历史 */
    EPISODIC("episodic"),

    /** 语义记忆 — 项目知识、偏好 */
    SEMANTIC("semantic"),

    /** 程序记忆 — 常用工作流 */
    PROCEDURAL("procedural");

    private final String tag;

    MemoryCategory(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }

    /**
     * 从标签字符串解析分类，默认 SEMANTIC。
     */
    public static MemoryCategory fromTag(String tag) {
        if (tag == null) return SEMANTIC;
        for (MemoryCategory cat : values()) {
            if (cat.tag.equalsIgnoreCase(tag)) return cat;
        }
        return SEMANTIC;
    }
}
