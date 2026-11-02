package com.aicodeassistant.prompt;

import com.aicodeassistant.memdir.MemdirService;
import com.aicodeassistant.service.ProjectMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * MemoryPromptService — 统一生成注入系统提示的记忆段。
 * <p>
 * 来源与顺序（后者优先级更高，冲突时以后者为准）:
 * <ol>
 *     <li>个人记忆 {@code ~/.ai-code-assistant/MEMORY.md}（跨项目，按 200 行 / 25,000 字节截断）</li>
 *     <li>项目共享记忆 {@code zhikun.md}</li>
 *     <li>项目个人本地记忆 {@code zhikun.local.md}</li>
 * </ol>
 * 不做缓存：每次构建系统提示都重新读取，文件修改后下一次请求即生效。
 * 同一次查询执行内的多轮工具循环仍复用执行开始时构建的系统提示。
 */
@Service
public class MemoryPromptService {

    private static final Logger log = LoggerFactory.getLogger(MemoryPromptService.class);

    static final String PRECEDENCE_NOTE =
            "以下为持久化记忆，按优先级从低到高排列：个人记忆（MEMORY.md）< 项目记忆（zhikun.md）"
            + "< 项目本地记忆（zhikun.local.md）。内容冲突时以优先级高者为准；"
            + "记忆不凌驾于用户当前的明确指令。";

    private final MemdirService memdirService;
    private final ProjectMemoryService projectMemoryService;

    public MemoryPromptService(MemdirService memdirService,
                               ProjectMemoryService projectMemoryService) {
        this.memdirService = memdirService;
        this.projectMemoryService = projectMemoryService;
    }

    /**
     * 构建记忆段；无任何记忆时返回空字符串。
     *
     * @param projectRoot 当前 Session 的 Project 根目录（可为 null，此时只注入个人记忆）
     */
    public String buildMemoryPrompt(Path projectRoot) {
        String personal = safe(() -> memdirService.readMemoriesForPrompt(), "personal");
        String shared = projectRoot == null ? ""
                : safe(() -> projectMemoryService.loadMemoryFile(projectRoot, ProjectMemoryService.SHARED_FILE),
                        "zhikun.md");
        String local = projectRoot == null ? ""
                : safe(() -> projectMemoryService.loadMemoryFile(projectRoot, ProjectMemoryService.LOCAL_FILE),
                        "zhikun.local.md");

        if (personal.isBlank() && shared.isBlank() && local.isBlank()) return "";

        StringBuilder sb = new StringBuilder("<memory>\n").append(PRECEDENCE_NOTE).append("\n");
        appendSection(sb, "personal_memory", personal);
        appendSection(sb, "project_memory", shared);
        appendSection(sb, "project_local_memory", local);
        sb.append("</memory>");
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String tag, String content) {
        if (content == null || content.isBlank()) return;
        sb.append("\n<").append(tag).append(">\n")
          .append(content)
          .append("\n</").append(tag).append(">\n");
    }

    /** 记忆读取失败不得阻断查询：记录日志并跳过该来源。 */
    private static String safe(java.util.function.Supplier<String> supplier, String name) {
        try {
            String value = supplier.get();
            return value == null ? "" : value;
        } catch (RuntimeException e) {
            log.warn("Failed to load {} memory for prompt: {}", name, e.getMessage());
            return "";
        }
    }
}
