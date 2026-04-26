package com.aicodeassistant.memdir;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Memdir 自动记忆系统 — 跨会话持久化记忆。
 * <p>
 * <p>
 * 功能:
 * 1. LLM 通过 MemoryTool 主动写入记忆 (用户偏好、项目约定等)
 * 2. 记忆存储在用户级文件中，跨会话和项目持久化
 * 3. 每次会话启动时自动加载到系统提示的 memory 段
 * 4. 支持内容变换标记 (transformation markers)
 * <p>
 * 截断保护:
 * - MEMORY.md 按行截断上限: 200 行
 * - MEMORY.md 按字节截断上限: 25KB (25,600 bytes)
 * - 加载时超出上限的内容将被截断，防止系统提示 token 爆炸
 *
 */
@Service
public class MemdirService {

    private static final Logger log = LoggerFactory.getLogger(MemdirService.class);

    /** 用户级记忆目录 */
    private final Path memoryDir;
    /** 入口文件名必须全大写 — 与源码 ENTRYPOINT_NAME = 'MEMORY.md' 一致 */
    static final String ENTRYPOINT_NAME = "MEMORY.md";
    /** 记忆文件路径 */
    private final Path memoryFile;
    /** BM25 搜索引擎 */
    private final MemorySearchEngine searchEngine;

    /** LLM rerank 服务（可选注入） */
    @Autowired(required = false)
    private MemoryRerankService rerankService;

    /** 最大行数限制 */
    static final int MAX_ENTRYPOINT_LINES = 200;
    /** 最大字节数限制 (25KB) */
    static final int MAX_ENTRYPOINT_BYTES = 25_000;
    /** 字符数上限 */
    static final int MAX_MEMORY_SIZE = 50_000;
    /** 压缩保留比例 */
    private static final double COMPACT_KEEP_RATIO = 0.7;

    /** 团队记忆目录名 */
    private static final String TEAM_MEM_DIR = ".zhikun/team-memories";
    /** 记忆最大存活时间（天） */
    private static final int MAX_MEMORY_AGE_DAYS = 90;

    /** 记忆条目头部匹配模式 (增强版，支持分类标签) */
    /** 写操作并发保护锁 */
    private final ReentrantLock writeLock = new ReentrantLock();

    private static final Pattern ENTRY_HEADER_PATTERN = Pattern.compile(
            "<!-- source:(\\w+) time:(\\S+)(?: category:(\\w+))? -->");

    /**
     * 默认构造器 — 使用用户主目录。
     */
    public MemdirService() {
        this(Path.of(System.getProperty("user.home"), ".ai-code-assistant"));
    }

    /**
     * 可测试构造器 — 允许指定记忆目录。
     */
    public MemdirService(Path memoryDir) {
        this(memoryDir, new MemorySearchEngine());
    }

    /**
     * 完整构造器 — 允许注入自定义搜索引擎。
     */
    public MemdirService(Path memoryDir, MemorySearchEngine searchEngine) {
        this.memoryDir = memoryDir;
        this.memoryFile = memoryDir.resolve(ENTRYPOINT_NAME);
        this.searchEngine = searchEngine;
    }

    // ==================== 查询（BM25 语义搜索） ====================

    /**
     * 语义搜索记忆 — 基于 BM25 加权的关键词匹配。
     *
     * @param query    查询文本
     * @param topK     返回前 K 条
     * @return 按相关度排序的记忆列表
     */
    public List<Memory> searchMemories(String query, int topK) {
        String content = readMemories();
        if (content.isEmpty()) return List.of();

        List<MemoryEntry> entries = parseEntries(content);
        if (entries.isEmpty()) return List.of();

        // 构建搜索文档列表
        List<MemorySearchEngine.DocumentEntry> documents = entries.stream()
                .map(e -> new MemorySearchEngine.DocumentEntry(
                        extractTitle(e.content()),    // 提取 markdown 标题
                        e.content()
                ))
                .toList();

        // BM25 搜索 — 扩大候选集（rerank 启用时取 Top-20，否则取 topK）
        int bm25Limit = (rerankService != null && rerankService.isEnabled())
                ? Math.max(topK, 20) : topK;
        List<MemorySearchEngine.ScoredResult> results = searchEngine.search(
                documents, query, bm25Limit);

        List<Memory> bm25Results = results.stream()
                .map(sr -> {
                    MemoryEntry entry = entries.get(sr.index());
                    return new Memory(
                            entry.source().name() + "_" + entry.timestamp().getEpochSecond(),
                            entry.content(),
                            entry.category());
                })
                .toList();

        // LLM rerank 精排（启用时）
        if (rerankService != null && rerankService.isEnabled() && bm25Results.size() > topK) {
            return rerankService.rerank(query, bm25Results, topK);
        }

        return bm25Results;
    }

    /**
     * 从记忆内容中提取 markdown 标题。
     */
    private String extractTitle(String content) {
        if (content == null) return "";
        int newline = content.indexOf('\n');
        String firstLine = newline > 0 ? content.substring(0, newline) : content;
        return firstLine.replaceAll("^#+\\s*", "").trim();
    }

    /**
     * 按分类过滤记忆。
     */
    public List<Memory> searchByCategory(MemoryCategory category, int maxCount) {
        String content = readMemories();
        if (content.isEmpty()) return List.of();

        return parseEntries(content).stream()
                .filter(e -> e.category() == category)
                .sorted(Comparator.comparing(MemoryEntry::timestamp).reversed())
                .limit(maxCount)
                .map(e -> new Memory(
                        e.source().name() + "_" + e.timestamp().getEpochSecond(),
                        e.content(), e.category()))
                .toList();
    }

    /**
     * 分词器 — 将文本转换为小写 token 列表。
     */
    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.toLowerCase().split("[\\s\\p{Punct}]+"))
                .filter(t -> t.length() > 1)  // 过滤单字符
                .toList();
    }

    private record ScoredEntry(MemoryEntry entry, double score) {}

    /**
     * 加载与查询相关的记忆 — 简单关键词匹配。
     *
     * @param query    查询文本
     * @param maxCount 最大返回数量
     * @return 按相关度排序的记忆列表
     */
    public List<Memory> loadRelevantMemories(String query, int maxCount) {
        // 委托给增强的 TF-IDF 搜索
        List<Memory> tfidfResults = searchMemories(query, maxCount);
        if (!tfidfResults.isEmpty()) return tfidfResults;

        // Fallback: 简单关键词匹配
        String content = readMemories();
        if (content.isEmpty()) return List.of();

        List<MemoryEntry> entries = parseEntries(content);
        if (entries.isEmpty()) return List.of();

        String[] queryWords = query.toLowerCase().split("\\s+");
        return entries.stream()
                .sorted(Comparator.comparingDouble(e ->
                        -calculateRelevance(e.content(), queryWords)))
                .limit(maxCount)
                .map(e -> new Memory(e.source().name() + "_" + e.timestamp().getEpochSecond(),
                        e.content(), e.category()))
                .toList();
    }

    /**
     * 计算文本与查询词的相关度。
     */
    private double calculateRelevance(String content, String[] queryWords) {
        if (queryWords.length == 0) return 0;
        String lower = content.toLowerCase();
        int matches = 0;
        for (String word : queryWords) {
            if (!word.isBlank() && lower.contains(word)) matches++;
        }
        return (double) matches / queryWords.length;
    }

    /** 简化记忆记录 */
    public record Memory(String name, String content, MemoryCategory category) {
        public Memory(String name, String content) {
            this(name, content, MemoryCategory.SEMANTIC);
        }
    }

    // ==================== 简化写入 ====================

    /**
     * 简化写入接口 — 按名称保存记忆。
     *
     * @param name    记忆名称
     * @param content 记忆内容
     */
    public void saveMemory(String name, String content) {
        writeMemory("## " + name + "\n" + content, MemorySource.TOOL);
    }

    // ==================== 读取 ====================

    /**
     * 读取所有自动记忆。
     *
     * @return 记忆文件内容，不存在时返回空字符串
     */
    public String readMemories() {
        if (!Files.exists(memoryFile)) return "";
        try {
            return Files.readString(memoryFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to read memory file: {}", memoryFile, e);
            return "";
        }
    }

    /**
     * 读取记忆并应用截断保护（用于注入系统提示）。
     * <p>
     * 截断规则:
     * 1. 按行截断: 最多 MAX_ENTRYPOINT_LINES 行
     * 2. 按字节截断: 最多 MAX_ENTRYPOINT_BYTES 字节
     *
     * @return 截断后的记忆内容
     */
    public String readMemoriesForPrompt() {
        String content = readMemories();
        if (content.isEmpty()) return content;

        // 1. 按行截断
        String[] lines = content.split("\n", -1);
        if (lines.length > MAX_ENTRYPOINT_LINES) {
            content = Arrays.stream(lines)
                    .limit(MAX_ENTRYPOINT_LINES)
                    .collect(Collectors.joining("\n"));
            content += "\n<!-- truncated: exceeded " + MAX_ENTRYPOINT_LINES + " lines -->";
            log.info("Memory truncated: {} lines → {} lines", lines.length, MAX_ENTRYPOINT_LINES);
        }

        // 2. 按字节截断
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_ENTRYPOINT_BYTES) {
            // 找到最后一个完整行的位置
            int cutoff = MAX_ENTRYPOINT_BYTES;
            while (cutoff > 0 && bytes[cutoff] != '\n') cutoff--;
            if (cutoff > 0) {
                content = new String(bytes, 0, cutoff, StandardCharsets.UTF_8);
                content += "\n<!-- truncated: exceeded " + MAX_ENTRYPOINT_BYTES + " bytes -->";
            }
            log.info("Memory truncated: {} bytes → {} bytes", bytes.length, cutoff);
        }

        return content;
    }

    // ==================== 写入 ====================

    /**
     * 写入记忆条目 — 追加模式，支持分类。
     *
     * @param content  记忆内容 (markdown 格式)
     * @param source   记忆来源标记 (AUTO/USER/TOOL)
     * @param category 记忆分类
     */
    public void writeMemory(String content, MemorySource source, MemoryCategory category) {
        writeLock.lock();
        try {
            Files.createDirectories(memoryDir);

            String existing = readMemories();
            if (existing.length() + content.length() > MAX_MEMORY_SIZE) {
                existing = compactMemories(existing);
                log.info("Memories compacted due to size limit");
            }

            String entry = String.format(
                    "\n<!-- source:%s time:%s category:%s -->\n%s\n",
                    source.name(), Instant.now(), category.tag(), content);

            Path tempFile = memoryFile.resolveSibling(memoryFile.getFileName() + ".tmp");
            Files.writeString(tempFile, existing + entry,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tempFile, memoryFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);

            log.info("Memory written: source={}, category={}, length={}", source, category, content.length());
        } catch (IOException e) {
            log.error("Failed to write memory: {}", e.getMessage(), e);
            throw new MemdirException("Failed to write memory", e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 写入记忆条目 — 追加模式 (默认 SEMANTIC 分类)。
     *
     * @param content 记忆内容 (markdown 格式)
     * @param source  记忆来源标记 (AUTO/USER/TOOL)
     */
    public void writeMemory(String content, MemorySource source) {
        writeMemory(content, source, MemoryCategory.SEMANTIC);
    }

    // ==================== 删除 ====================

    /**
     * 删除匹配指定模式的记忆条目。
     *
     * @param searchPattern 搜索模式（文本匹配）
     * @return true 如果有条目被删除
     */
    public boolean deleteMemory(String searchPattern) {
        writeLock.lock();
        try {
            String content = readMemories();
            if (content.isEmpty()) return false;

            String updated = removeMatchingEntries(content, searchPattern);
            if (updated.equals(content)) return false;

            Path tempFile = memoryFile.resolveSibling(memoryFile.getFileName() + ".tmp");
            Files.writeString(tempFile, updated,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tempFile, memoryFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            log.info("Memory deleted: pattern={}", searchPattern);
            return true;
        } catch (IOException e) {
            log.error("Failed to delete memory: {}", e.getMessage(), e);
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    // ==================== 解析与压缩 ====================

    /**
     * 解析记忆条目列表。
     */
    List<MemoryEntry> parseEntries(String content) {
        if (content == null || content.isBlank()) return new ArrayList<>();

        List<MemoryEntry> entries = new ArrayList<>();
        String[] sections = content.split("(?=<!-- source:)");

        for (String section : sections) {
            if (section.isBlank()) continue;

            Matcher m = ENTRY_HEADER_PATTERN.matcher(section);
            if (m.find()) {
                String sourceName = m.group(1);
                String timeStr = m.group(2);
                String body = section.substring(m.end()).trim();

                MemorySource source;
                try {
                    source = MemorySource.valueOf(sourceName);
                } catch (IllegalArgumentException e) {
                    source = MemorySource.AUTO;
                }

                Instant timestamp;
                try {
                    timestamp = Instant.parse(timeStr);
                } catch (Exception e) {
                    timestamp = Instant.EPOCH;
                }

                entries.add(new MemoryEntry(source, timestamp, body,
                        MemoryCategory.fromTag(m.group(3))));
            } else {
                // 无头部标记的纯文本条目
                entries.add(new MemoryEntry(MemorySource.USER, Instant.EPOCH, section.trim()));
            }
        }

        return entries;
    }

    /**
     * 压缩旧记忆 — 保留最新的 70%。
     */
    String compactMemories(String existing) {
        List<MemoryEntry> entries = parseEntries(existing);
        if (entries.isEmpty()) return "";

        entries.sort(Comparator.comparing(MemoryEntry::timestamp).reversed());
        int keepCount = Math.max(1, (int) (entries.size() * COMPACT_KEEP_RATIO));

        return entries.stream()
                .limit(keepCount)
                .map(e -> String.format("<!-- source:%s time:%s category:%s -->\n%s",
                        e.source().name(), e.timestamp(), e.category().tag(), e.content()))
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 移除匹配搜索模式的条目。
     */
    String removeMatchingEntries(String content, String searchPattern) {
        List<MemoryEntry> entries = parseEntries(content);
        String patternLower = searchPattern.toLowerCase();

        List<MemoryEntry> remaining = entries.stream()
                .filter(e -> !e.content().toLowerCase().contains(patternLower))
                .toList();

        if (remaining.size() == entries.size()) return content;

        return remaining.stream()
                .map(e -> String.format("<!-- source:%s time:%s category:%s -->\n%s",
                        e.source().name(), e.timestamp(), e.category().tag(), e.content()))
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 获取记忆文件路径。
     */
    public Path getMemoryFile() {
        return memoryFile;
    }

    /**
     * 返回所有记忆条目（供外部调用）。
     */
    public List<MemoryEntry> listEntries() {
        return parseEntries(readMemories());
    }

    /**
     * 获取记忆条目数量。
     */
    public int getEntryCount() {
        return parseEntries(readMemories()).size();
    }

    // ==================== 内部类型 ====================

    /** 记忆来源枚举 */
    public enum MemorySource {
        AUTO,    // LLM 自动记录
        USER,    // 用户手动编辑
        TOOL     // 通过工具记录
    }

    /** 记忆条目 */
    public record MemoryEntry(
            MemorySource source,
            Instant timestamp,
            String content,
            MemoryCategory category
    ) {
        public MemoryEntry(MemorySource source, Instant timestamp, String content) {
            this(source, timestamp, content, MemoryCategory.SEMANTIC);
        }
    }

    /** Memdir 异常 */
    public static class MemdirException extends RuntimeException {
        public MemdirException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ==================== 团队记忆 ====================

    /**
     * 加载团队记忆 — 扫描项目根目录的 .zhikun/team-memories/。
     */
    public List<Memory> loadTeamMemories(Path projectRoot) {
        if (projectRoot == null) return List.of();
        Path teamDir = projectRoot.resolve(TEAM_MEM_DIR);
        if (!Files.isDirectory(teamDir)) return List.of();
        List<Memory> memories = new ArrayList<>();
        try (var stream = Files.list(teamDir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                  .forEach(p -> {
                      try {
                          String content = Files.readString(p, StandardCharsets.UTF_8);
                          memories.add(new Memory("team:" + p.getFileName(), content,
                                                   MemoryCategory.TEAM));
                      } catch (IOException e) {
                          log.warn("Failed to read team memory: {}", p, e);
                      }
                  });
        } catch (IOException e) {
            log.warn("Failed to scan team memory dir: {}", teamDir, e);
        }
        return memories;
    }

    /**
     * 清理过期记忆。
     */
    public int purgeExpiredMemories() {
        String content = readMemories();
        if (content.isEmpty()) return 0;
        List<MemoryEntry> entries = parseEntries(content);
        Instant cutoff = Instant.now().minus(Duration.ofDays(MAX_MEMORY_AGE_DAYS));
        List<MemoryEntry> remaining = entries.stream()
                .filter(e -> e.timestamp().isAfter(cutoff) || e.timestamp().equals(Instant.EPOCH))
                .toList();
        int purged = entries.size() - remaining.size();
        if (purged > 0) {
            String updated = remaining.stream()
                    .map(e -> String.format("<!-- source:%s time:%s category:%s -->\n%s",
                            e.source().name(), e.timestamp(), e.category().tag(), e.content()))
                    .collect(Collectors.joining("\n\n"));
            try {
                Files.writeString(memoryFile, updated,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                log.info("Purged {} expired memories", purged);
            } catch (IOException e) {
                log.error("Failed to purge memories", e);
            }
        }
        return purged;
    }

    /**
     * 构建记忆提示行 — 用于注入系统提示。
     */
    public String buildMemoryPrompt(Path projectRoot) {
        StringBuilder sb = new StringBuilder();
        String personalMemory = readMemoriesForPrompt();
        if (!personalMemory.isEmpty()) {
            sb.append("## Personal Memory\n");
            sb.append(personalMemory);
            sb.append("\n\n");
        }
        List<Memory> teamMemories = loadTeamMemories(projectRoot);
        if (!teamMemories.isEmpty()) {
            sb.append("## Team Memory\n");
            for (Memory mem : teamMemories) {
                sb.append("### ").append(mem.name()).append("\n");
                sb.append(mem.content()).append("\n\n");
            }
        }
        if (!sb.isEmpty()) {
            sb.append("\n---\n");
            sb.append("Memory categories: USER_PREFERENCE, PROJECT_CONVENTION, ");
            sb.append("CODE_PATTERN, ERROR_SOLUTION, SEMANTIC, TEAM\n");
            sb.append("To save memory: use MemoryTool with appropriate category\n");
            sb.append("To search memory: use MemoryTool search with keywords\n");
        }
        return sb.toString();
    }
}
