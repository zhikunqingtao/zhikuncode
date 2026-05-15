package com.aicodeassistant.engine.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工具优先级调度器 — 管理工具执行顺序。
 * <p>
 * 优先级层级（数字越小优先级越高）：
 * - Priority 0: 只读操作（FileRead, GrepSearch, ListDir）
 * - Priority 1: 分析类（LSP 操作）
 * - Priority 2: 执行类（Bash）
 * - Priority 3: 写入类（FileWrite, FileEdit）
 * <p>
 * 设计目标：确保只读操作优先执行，避免在信息不完整时进行写入操作。
 */
@Component
public class ToolPriorityScheduler {

    private static final Logger log = LoggerFactory.getLogger(ToolPriorityScheduler.class);

    /** 工具优先级映射 */
    private static final Map<String, Integer> PRIORITY_MAP = Map.ofEntries(
            // Priority 0: 只读操作优先
            Map.entry("FileRead", 0),
            Map.entry("GrepSearch", 0),
            Map.entry("ListDir", 0),
            // Priority 1: 分析类
            Map.entry("LspDefinition", 1),
            Map.entry("LspReferences", 1),
            Map.entry("Lsp", 1),
            // Priority 2: 执行类
            Map.entry("Bash", 2),
            // Priority 3: 写入类（最后执行）
            Map.entry("FileWrite", 3),
            Map.entry("FileEdit", 3)
    );

    /** 默认优先级 */
    private static final int DEFAULT_PRIORITY = 2;

    /**
     * 获取工具优先级。
     *
     * @param toolName 工具名称
     * @return 优先级数值（0-3，越小越高）
     */
    public int getPriority(String toolName) {
        return PRIORITY_MAP.getOrDefault(toolName, DEFAULT_PRIORITY);
    }

    /**
     * 对一组工具调用按优先级排序。
     *
     * @param toolCalls        工具调用列表
     * @param toolNameExtractor 从工具调用对象中提取工具名称的函数
     * @param <T>              工具调用类型
     * @return 按优先级排序后的列表
     */
    public <T> List<T> sortByPriority(List<T> toolCalls, Function<T, String> toolNameExtractor) {
        if (toolCalls == null || toolCalls.size() <= 1) {
            return toolCalls;
        }
        List<T> sorted = toolCalls.stream()
                .sorted(Comparator.comparingInt(tc -> getPriority(toolNameExtractor.apply(tc))))
                .collect(Collectors.toList());
        if (log.isDebugEnabled()) {
            log.debug("Tool priority sort: {} → {}",
                    toolCalls.stream().map(toolNameExtractor).toList(),
                    sorted.stream().map(toolNameExtractor).toList());
        }
        return sorted;
    }

    /**
     * 检测冲突：是否存在同一文件的读写操作在同一批次中。
     *
     * @param toolNames 工具名称列表
     * @param filePaths 对应的文件路径列表（与 toolNames 一一对应）
     * @return true 如果检测到读写冲突
     */
    public boolean hasConflict(List<String> toolNames, List<String> filePaths) {
        if (toolNames == null || filePaths == null
                || toolNames.size() != filePaths.size()) {
            return false;
        }

        // 收集写入操作涉及的文件
        java.util.Set<String> writeFiles = new java.util.HashSet<>();
        java.util.Set<String> readFiles = new java.util.HashSet<>();

        for (int i = 0; i < toolNames.size(); i++) {
            String tool = toolNames.get(i);
            String path = filePaths.get(i);
            if (path == null) continue;

            int priority = getPriority(tool);
            if (priority == 3) {
                // 写入类工具
                writeFiles.add(path);
            } else if (priority == 0) {
                // 只读类工具
                readFiles.add(path);
            }
        }

        // 检测交集
        for (String writePath : writeFiles) {
            if (readFiles.contains(writePath)) {
                log.warn("Tool conflict detected: read and write on same file: {}", writePath);
                return true;
            }
        }
        return false;
    }
}
