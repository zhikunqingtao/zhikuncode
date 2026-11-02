package com.aicodeassistant.memdir;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
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
 * 3. 每次请求构建系统提示时由 MemoryPromptService 读取 (readMemoriesForPrompt) 并注入
 * 4. 支持内容变换标记 (transformation markers)
 * <p>
 * 截断保护:
 * - MEMORY.md 按行截断上限: 200 行
 * - MEMORY.md 按字节截断上限: 25,000 bytes (含截断标记)
 * - 加载时超出上限的内容将被截断，防止系统提示 token 爆炸
 * - 写入后总长超过 MAX_MEMORY_SIZE 时拒绝写入 (MemoryFullException)，原文件不变
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

    /** 文件内容与其版本必须一起交给编辑器，不能在 Controller 中分别读取。 */
    public record MemoryFileSnapshot(String content, Instant updatedAt) {}

    /**
     * 与本服务的写操作使用同一把锁；读前后校验属性，避免把外部替换后的版本号
     * 配给旧正文。读取失败必须报错，不能让编辑器把不可读文件当作空文件保存。
     */
    public MemoryFileSnapshot readMemoryFileSnapshot() {
        writeLock.lock();
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                BasicFileAttributes before;
                try {
                    before = Files.readAttributes(memoryFile, BasicFileAttributes.class);
                } catch (NoSuchFileException missing) {
                    return new MemoryFileSnapshot("", null);
                }
                try {
                    String content = Files.readString(memoryFile, StandardCharsets.UTF_8);
                    BasicFileAttributes after = Files.readAttributes(memoryFile, BasicFileAttributes.class);
                    if (before.lastModifiedTime().equals(after.lastModifiedTime())
                            && before.size() == after.size()
                            && Objects.equals(before.fileKey(), after.fileKey())) {
                        return new MemoryFileSnapshot(content, before.lastModifiedTime().toInstant());
                    }
                } catch (NoSuchFileException replaced) {
                    // An external editor may replace/remove the file while it is being read.
                }
            }
            throw new IOException("Memory file kept changing while being read");
        } catch (IOException e) {
            throw new MemdirException("Failed to read memory snapshot", e);
        } finally {
            writeLock.unlock();
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

        // 2. 按字节截断（含截断标记在内不超过 MAX_ENTRYPOINT_BYTES）
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_ENTRYPOINT_BYTES) {
            String marker = "\n<!-- truncated: exceeded " + MAX_ENTRYPOINT_BYTES + " bytes -->";
            int budget = MAX_ENTRYPOINT_BYTES - marker.getBytes(StandardCharsets.UTF_8).length;
            // 优先截在最后一个完整行处
            int cutoff = budget;
            while (cutoff > 0 && bytes[cutoff] != '\n') cutoff--;
            if (cutoff == 0) {
                // 无换行：按字节预算硬截断，并退回到 UTF-8 字符边界（跳过续字节 10xxxxxx）
                cutoff = budget;
                while (cutoff > 0 && (bytes[cutoff] & 0xC0) == 0x80) cutoff--;
            }
            content = new String(bytes, 0, cutoff, StandardCharsets.UTF_8) + marker;
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
            String entry = String.format(
                    "\n<!-- source:%s time:%s category:%s -->\n%s\n",
                    source.name(), Instant.now(), category.tag(), content);

            // 写入后超过上限则拒绝，原文件不变 — 不静默删除旧记忆
            int total = existing.length() + entry.length();
            if (total > MAX_MEMORY_SIZE) {
                throw new MemoryFullException(
                        "Memory file would exceed limit: " + total + " chars (max " + MAX_MEMORY_SIZE
                                + "). Delete or consolidate old memories first.");
            }

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

    // ==================== 用户编辑整体覆盖 ====================

    /** 条目头前缀 — 正文中出现该字符串会被误认为条目头，覆盖写前需转义 */
    private static final String ENTRY_HEADER_PREFIX = "<!-- source:";
    /** 条目头前缀的转义形式 */
    private static final String ENTRY_HEADER_PREFIX_ESCAPED = "&lt;!-- source:";

    /**
     * 用户整篇编辑覆盖保存 — 规范化后整体替换 MEMORY.md。
     * <p>
     * 规范化规则:
     * 1. 按条目头切分后，无 (合法) 头部的段落包装为 USER 条目 (time=now, category=semantic)
     * 2. 已有合法头部 (source 为合法枚举值且时间戳可解析) 的段落头部原样保留
     * 3. 所有段落正文中的伪造头标记 {@code <!-- source:} 转义为 {@code &lt;!-- source:}，
     *    防止正文伪造头在再次解析时劈裂为伪条目 (先判定头部，再对 body 转义)
     * <p>
     * 与 writeMemory 的差异: 超限时抛出 IllegalArgumentException 而非触发 compactMemories
     * — 用户编辑不应静默丢内容。
     *
     * @param rawContent 用户编辑的原始全文
     * @return 规范化后的实际写入内容
     * @throws IllegalArgumentException 规范化内容超过 MAX_MEMORY_SIZE
     */
    public String overwriteFromUserEdit(String rawContent) {
        return persistUserEdit(normalizeRawUserEdit(rawContent), null, false).content();
    }

    /**
     * 用户整篇编辑覆盖保存 (带乐观并发基线)。
     * <p>
     * 在 writeLock 内、备份之前比对 MEMORY.md 当前 mtime 与 baseMtime，不一致 → 抛
     * {@link MemdirConflictException}。baseMtime 为 null 表示客户端加载时文件不存在：
     * 此时若文件已存在同样视为冲突，防止两个基于空文件的编辑互相静默覆盖。
     *
     * @param rawContent 用户编辑的原始全文
     * @param baseMtime  客户端持有的基线 mtime (来自 GET /api/memory/file 的 updatedAt)，null 表示“文件不存在”
     * @return 规范化后的实际写入内容
     * @throws IllegalArgumentException 规范化内容超过 MAX_MEMORY_SIZE
     * @throws MemdirConflictException  baseMtime 与当前文件状态不一致
     */
    public String overwriteFromUserEdit(String rawContent, Instant baseMtime) {
        return overwriteFromUserEditSnapshot(rawContent, baseMtime).content();
    }

    /** 保存并返回本次写入的内容和版本；后续追加不能改变这份响应快照。 */
    public MemoryFileSnapshot overwriteFromUserEditSnapshot(String rawContent, Instant baseMtime) {
        return persistUserEdit(normalizeRawUserEdit(rawContent), baseMtime, true);
    }

    /**
     * 用户卡片编辑覆盖保存 — 将条目列表重建为标准格式后整体替换 MEMORY.md。
     * <p>
     * content 为空白的条目被丢弃；null/非法的 source/timestamp/category
     * 分别降级为 USER / EPOCH / SEMANTIC；content 中的伪造头标记同样转义。
     *
     * @param entries 卡片模式提交的完整条目列表
     * @return 规范化后的实际写入内容
     * @throws IllegalArgumentException 规范化内容超过 MAX_MEMORY_SIZE
     */
    public String overwriteEntriesFromUserEdit(List<MemoryEntry> entries) {
        return persistUserEdit(normalizeEntries(entries), null, false).content();
    }

    /**
     * 用户卡片编辑覆盖保存 (带乐观并发基线)。
     * <p>
     * baseMtime 语义与 {@link #overwriteFromUserEdit(String, Instant)} 相同 (null 表示“文件不存在”)。
     *
     * @param entries   卡片模式提交的完整条目列表
     * @param baseMtime 客户端持有的基线 mtime，null 表示“文件不存在”
     * @return 规范化后的实际写入内容
     * @throws IllegalArgumentException 规范化内容超过 MAX_MEMORY_SIZE
     * @throws MemdirConflictException  baseMtime 与当前文件状态不一致
     */
    public String overwriteEntriesFromUserEdit(List<MemoryEntry> entries, Instant baseMtime) {
        return overwriteEntriesFromUserEditSnapshot(entries, baseMtime).content();
    }

    /** 卡片模式的保存快照，语义与整篇模式一致。 */
    public MemoryFileSnapshot overwriteEntriesFromUserEditSnapshot(List<MemoryEntry> entries, Instant baseMtime) {
        return persistUserEdit(normalizeEntries(entries), baseMtime, true);
    }

    /** 卡片条目规范化 (纯文本变换，不触碰磁盘)。 */
    private String normalizeEntries(List<MemoryEntry> entries) {
        List<MemoryEntry> safe = entries == null ? List.of() : entries;
        String normalized = safe.stream()
                .filter(Objects::nonNull)
                // 空白条目被丢弃
                .filter(e -> e.content() != null && !e.content().isBlank())
                .map(e -> String.format("<!-- source:%s time:%s category:%s -->\n%s",
                        e.source() != null ? e.source() : MemorySource.USER,
                        e.timestamp() != null ? e.timestamp() : Instant.EPOCH,
                        (e.category() != null ? e.category() : MemoryCategory.SEMANTIC).tag(),
                        escapeForgedHeaderMarkers(e.content().trim())))
                .collect(Collectors.joining("\n\n"));
        return normalized;
    }

    /**
     * 规范化用户整篇编辑的原始内容 (纯文本变换，不触碰磁盘)。
     */
    private String normalizeRawUserEdit(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) return "";
        Instant now = Instant.now();
        List<String> sections = new ArrayList<>();
        // 与 parseEntries 相同的切分方式，保证覆盖写 → 解析往返一致
        for (String section : rawContent.split("(?=<!-- source:)")) {
            String trimmed = section.trim();
            if (trimmed.isEmpty()) continue;
            Matcher m = ENTRY_HEADER_PATTERN.matcher(trimmed);
            if (m.lookingAt() && isValidEntryHeader(m)) {
                // 合法头部: 头部原样保留，仅转义正文中的伪造标记
                String header = trimmed.substring(0, m.end());
                String body = escapeForgedHeaderMarkers(trimmed.substring(m.end()).trim());
                sections.add(body.isEmpty() ? header : header + "\n" + body);
            } else {
                // 无 (合法) 头部: 包装为 USER 条目
                sections.add(String.format("<!-- source:USER time:%s category:semantic -->\n%s",
                        now, escapeForgedHeaderMarkers(trimmed)));
            }
        }
        return String.join("\n\n", sections);
    }

    /** 判定条目头是否合法: source 为合法枚举值且时间戳可解析。 */
    private boolean isValidEntryHeader(Matcher m) {
        try {
            MemorySource.valueOf(m.group(1));
            Instant.parse(m.group(2));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 转义正文中的伪造条目头标记，防止再次解析时正文被劈裂为伪条目。 */
    private String escapeForgedHeaderMarkers(String body) {
        return body.replace(ENTRY_HEADER_PREFIX, ENTRY_HEADER_PREFIX_ESCAPED);
    }

    /**
     * 用户编辑覆盖写盘的共享实现: 大小检查 → writeLock 内 [锁内冲突检测] → 备份 MEMORY.md.bak → 临时文件 + ATOMIC_MOVE。
     * <p>
     * 乐观并发冲突检测 (checkConflict 时): 在 writeLock 内、备份之前读取 MEMORY.md 当前 mtime
     * (文件不存在视为 null)，与 baseMtime 不一致则抛 {@link MemdirConflictException}。
     * baseMtime == null 表示客户端加载时文件不存在。检测与写入同锁，保护本服务实例内的并发写入；
     * 不提供跨进程事务，基线仍依赖文件系统的 mtime 精度。
     *
     * @param baseMtime     客户端基线 mtime；null 表示“文件不存在”
     * @param checkConflict 是否执行冲突检测 (仅内部/测试便捷入口传 false，HTTP 入口必须为 true)
     * @throws IllegalArgumentException 规范化内容超过 MAX_MEMORY_SIZE (不触发 compactMemories)
     * @throws MemdirConflictException  baseMtime 与当前文件状态不一致
     */
    private MemoryFileSnapshot persistUserEdit(String normalizedContent, Instant baseMtime, boolean checkConflict) {
        if (normalizedContent.length() > MAX_MEMORY_SIZE) {
            throw new IllegalArgumentException(
                    "Memory content too large: " + normalizedContent.length()
                            + " chars (max " + MAX_MEMORY_SIZE + ")");
        }
        writeLock.lock();
        try {
            Files.createDirectories(memoryDir);
            // 锁内乐观并发检测: baseMtime == null 表示“客户端看到的是文件不存在”，
            // 此时若文件已存在同样视为冲突，防止两个基于空文件的编辑互相静默覆盖
            Instant currentMtime = Files.exists(memoryFile)
                    ? Files.getLastModifiedTime(memoryFile).toInstant() : null;
            if (checkConflict && !Objects.equals(currentMtime, baseMtime)) {
                throw new MemdirConflictException(
                        "记忆已在别处被修改 (基线与当前 MEMORY.md 不一致)，请重新加载后重试");
            }
            // 覆盖前备份现有文件，便于误操作回滚
            if (Files.exists(memoryFile)) {
                Files.copy(memoryFile, memoryFile.resolveSibling(ENTRYPOINT_NAME + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Path tempFile = memoryFile.resolveSibling(memoryFile.getFileName() + ".tmp");
            Files.writeString(tempFile, normalizedContent,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            // Capture the version of the exact file being published, before another writer
            // can replace MEMORY.md. Atomic rename preserves this file's modification time.
            Instant writtenMtime = Files.getLastModifiedTime(tempFile).toInstant();
            Files.move(tempFile, memoryFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            log.info("Memory file overwritten by user edit: length={}", normalizedContent.length());
            return new MemoryFileSnapshot(normalizedContent, writtenMtime);
        } catch (IOException e) {
            log.error("Failed to overwrite memory file: {}", e.getMessage(), e);
            throw new MemdirException("Failed to overwrite memory file", e);
        } finally {
            writeLock.unlock();
        }
    }

    /** 返回记忆文件大小上限 (字符数)，供 Controller 层展示。 */
    public int getMaxMemorySize() {
        return MAX_MEMORY_SIZE;
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
     * 解析给定全文为条目列表（供 Controller 用同一份内容生成一致的响应）。
     */
    public List<MemoryEntry> parseEntriesOf(String content) {
        return parseEntries(content);
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

    /** 记忆文件已满 — 追加后将超过 MAX_MEMORY_SIZE，写入被拒绝且原文件不变 */
    public static class MemoryFullException extends RuntimeException {
        public MemoryFullException(String message) {
            super(message);
        }
    }

    /** Memdir 乐观并发冲突异常 — 基线 mtime 与当前文件不一致时抛出 (对应 HTTP 409) */
    public static class MemdirConflictException extends RuntimeException {
        public MemdirConflictException(String message) {
            super(message);
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
        writeLock.lock();
        try {
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
                    Path tempFile = memoryFile.resolveSibling(memoryFile.getFileName() + ".tmp");
                    Files.writeString(tempFile, updated,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    Files.move(tempFile, memoryFile, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                    log.info("Purged {} expired memories", purged);
                } catch (IOException e) {
                    log.error("Failed to purge memories", e);
                }
            }
            return purged;
        } finally {
            writeLock.unlock();
        }
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
            sb.append("To save memory: use the Memory tool with action=write\n");
            sb.append("To read memory: use the Memory tool with action=read\n");
        }
        return sb.toString();
    }
}
