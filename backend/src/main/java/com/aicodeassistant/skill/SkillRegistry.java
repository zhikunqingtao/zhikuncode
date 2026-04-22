package com.aicodeassistant.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 技能注册表 — 管理从 5 种来源加载的技能。
 * <p>
 * 加载来源优先级 (高→低):
 * <ol>
 *     <li>managed — 策略管理目录</li>
 *     <li>user    — 用户全局目录 (~/.zhikun/skills/)</li>
 *     <li>project — 项目目录 (.zhikun/skills/)</li>
 *     <li>plugin  — 插件提供的技能</li>
 *     <li>bundled — 内置技能</li>
 *     <li>mcp     — MCP 构建的技能</li>
 * </ol>
 * 同名技能按优先级链覆盖。
 *
 */
@Service
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    /** 所有已注册技能 (name → definition)，按优先级覆盖 */
    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();

    /** 内置技能单独缓存 */
    private final Map<String, SkillDefinition> builtinSkills = new ConcurrentHashMap<>();

    /** 用户全局技能目录 */
    private static final String USER_SKILLS_DIR = ".zhikun/skills";

    /** 项目技能目录 */
    private static final String PROJECT_SKILLS_DIR = ".zhikun/skills";

    /** 内置技能列表 */
    private static final List<String> BUILTIN_SKILL_NAMES = List.of(
            "commit", "review", "fix", "test", "pr"
    );

    /** 文件监听 WatchService 实例 (§11.3.3) */
    private volatile WatchService watchService;
    private final AtomicBoolean watching = new AtomicBoolean(false);
    private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "skill-watcher-debounce"); t.setDaemon(true); return t; });
    /** 防抖标记: 路径 → 最后事件时间戳 */
    private final ConcurrentHashMap<Path, Long> pendingEvents = new ConcurrentHashMap<>();
    private static final long DEBOUNCE_MS = 500;

    /**
     * 启动时加载内置技能。
     */
    @PostConstruct
    public void registerBuiltinSkills() {
        for (String name : BUILTIN_SKILL_NAMES) {
            try {
                String resourcePath = "skills/bundled/" + name + ".md";
                ClassPathResource resource = new ClassPathResource(resourcePath);
                if (resource.exists()) {
                    try (InputStream is = resource.getInputStream()) {
                        String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        SkillDefinition skill = SkillDefinition.fromMarkdown(
                                name + ".md", content,
                                SkillDefinition.SkillSource.BUNDLED, null);
                        registerBuiltin(skill);
                        log.info("Loaded builtin skill: /{}", name);
                    }
                } else {
                    log.debug("Builtin skill resource not found: {}", resourcePath);
                }
            } catch (IOException e) {
                log.warn("Failed to load builtin skill '{}': {}", name, e.getMessage());
            }
        }
        log.info("Builtin skills loaded: {}/{}", builtinSkills.size(), BUILTIN_SKILL_NAMES.size());
    }

    /**
     * 注册内置技能。
     */
    public void registerBuiltin(SkillDefinition skill) {
        builtinSkills.put(skill.name(), skill);
        skills.put(skill.name(), skill);
        log.debug("Registered builtin skill: {}", skill.name());
    }

    /**
     * 注册自定义技能（来自任何来源）。
     */
    public void register(SkillDefinition skill) {
        skills.put(skill.name(), skill);
        log.debug("Registered skill: {} (source={})", skill.name(), skill.source());
    }

    /**
     * 按名称解析技能 — 支持别名匹配。
     *
     * @param name 技能名称（不含 / 前缀）
     * @return 匹配的技能定义，未找到返回 null
     */
    public SkillDefinition resolve(String name) {
        if (name == null) return null;
        String normalized = name.replaceFirst("^/", "").toLowerCase();

        // 精确匹配
        SkillDefinition skill = skills.get(normalized);
        if (skill != null) return skill;

        // 遍历查找（大小写不敏感 + 别名）
        for (SkillDefinition s : skills.values()) {
            if (s.name().equalsIgnoreCase(normalized)
                    || s.effectiveName().equalsIgnoreCase(normalized)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 获取项目技能 — 扫描项目 .zhikun/skills/ 目录。
     */
    public List<SkillDefinition> getProjectSkills(String workingDirectory) {
        if (workingDirectory == null) return List.of();
        Path skillsDir = Path.of(workingDirectory, PROJECT_SKILLS_DIR);
        return loadSkillsFromDir(skillsDir, SkillDefinition.SkillSource.PROJECT);
    }

    /**
     * 获取用户全局技能 — 扫描 ~/.zhikun/skills/ 目录。
     */
    public List<SkillDefinition> getUserSkills() {
        String home = System.getProperty("user.home");
        if (home == null) return List.of();
        Path skillsDir = Path.of(home, USER_SKILLS_DIR);
        return loadSkillsFromDir(skillsDir, SkillDefinition.SkillSource.USER);
    }

    /**
     * 获取所有已注册技能。
     */
    public Collection<SkillDefinition> getAllSkills() {
        return Collections.unmodifiableCollection(skills.values());
    }

    /**
     * 获取所有内置技能。
     */
    public Collection<SkillDefinition> getBuiltinSkills() {
        return Collections.unmodifiableCollection(builtinSkills.values());
    }

    /**
     * 获取可用技能数量。
     */
    public int size() {
        return skills.size();
    }

    /**
     * 加载并注册指定目录下的所有 .zhikun/skills/ 技能。
     *
     * @param workingDirectory 项目工作目录
     */
    public void loadAndRegister(String workingDirectory) {
        // 1. 加载项目技能
        List<SkillDefinition> projectSkills = getProjectSkills(workingDirectory);
        projectSkills.forEach(this::register);

        // 2. 加载用户全局技能
        List<SkillDefinition> userSkills = getUserSkills();
        userSkills.forEach(this::register);

        log.info("SkillRegistry loaded: {} project skills, {} user skills, {} total",
                projectSkills.size(), userSkills.size(), skills.size());
    }

    /**
     * 从指定目录加载 Markdown 技能文件。
     */
    List<SkillDefinition> loadSkillsFromDir(Path dir, SkillDefinition.SkillSource source) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }

        List<SkillDefinition> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(p -> p.toString().endsWith(".md"))
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            String fileName = p.getFileName().toString();
                            SkillDefinition skill = SkillDefinition.fromMarkdown(
                                    fileName, content, source, p.toAbsolutePath().toString());
                            result.add(skill);
                        } catch (IOException e) {
                            log.warn("Failed to read skill file: {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to scan skills directory: {}", dir, e);
        }
        return result;
    }

    /**
     * 清除所有注册 — 测试使用。
     */
    public void clear() {
        skills.clear();
        builtinSkills.clear();
    }

    // ============ 动态技能发现 WatchService (§11.3.3) ============

    /**
     * 启动文件监听 — 监听 .zhikun/skills/ 目录变化，自动注册/反注册技能。
     *
     * @param workingDirectory 项目工作目录
     */
    public void startWatching(String workingDirectory) {
        if (workingDirectory == null || !watching.compareAndSet(false, true)) {
            return;
        }

        Path skillsDir = Path.of(workingDirectory, PROJECT_SKILLS_DIR);
        if (!Files.isDirectory(skillsDir)) {
            watching.set(false);
            return;
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
            // 注册主目录和子目录 (两级)
            registerWatchDir(skillsDir);
            try (Stream<Path> subdirs = Files.list(skillsDir)) {
                subdirs.filter(Files::isDirectory).forEach(this::registerWatchDir);
            }

            Thread watchThread = new Thread(() -> watchLoop(skillsDir), "skill-file-watcher");
            watchThread.setDaemon(true);
            watchThread.start();

            log.info("Started watching skills directory: {}", skillsDir);
        } catch (IOException e) {
            log.warn("Failed to start skills file watcher: {}", e.getMessage());
            watching.set(false);
        }
    }

    private void registerWatchDir(Path dir) {
        try {
            dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException e) {
            log.warn("Failed to register watch on: {}", dir);
        }
    }

    private void watchLoop(Path skillsDir) {
        while (watching.get()) {
            try {
                WatchKey key = watchService.take();
                Path watchedDir = (Path) key.watchable();

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path changed = watchedDir.resolve(pathEvent.context());

                    if (Files.isDirectory(changed) && kind == StandardWatchEventKinds.ENTRY_CREATE) {
                        registerWatchDir(changed);
                        continue;
                    }

                    if (!changed.toString().endsWith(".md")) continue;

                    // 500ms 防抖
                    pendingEvents.put(changed, System.currentTimeMillis());
                    debounceExecutor.schedule(() -> handleFileEvent(changed, kind, skillsDir),
                            DEBOUNCE_MS, TimeUnit.MILLISECONDS);
                }

                boolean valid = key.reset();
                if (!valid) {
                    log.debug("Watch key invalidated for: {}", watchedDir);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }
        }
    }

    private void handleFileEvent(Path changed, WatchEvent.Kind<?> kind, Path skillsDir) {
        Long lastEvent = pendingEvents.remove(changed);
        if (lastEvent == null) return;
        // 如果已经有更新的事件在排队，跳过此次
        if (System.currentTimeMillis() - lastEvent > DEBOUNCE_MS + 100) return;

        String fileName = changed.getFileName().toString();
        try {
            if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                // 反注册
                String skillName = fileName.replace(".md", "").toLowerCase();
                skills.remove(skillName);
                log.info("Skill unregistered (file deleted): {}", skillName);
            } else {
                // CREATE 或 MODIFY — (重新)注册
                if (Files.isRegularFile(changed)) {
                    String content = Files.readString(changed);
                    SkillDefinition skill = SkillDefinition.fromMarkdown(
                            fileName, content,
                            SkillDefinition.SkillSource.PROJECT,
                            changed.toAbsolutePath().toString());
                    register(skill);
                    log.info("Skill {} (file {}): {}",
                            kind == StandardWatchEventKinds.ENTRY_CREATE ? "registered" : "reregistered",
                            kind.name(), skill.name());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to handle skill file event: {} {}", kind, changed, e);
        }
    }

    /**
     * 停止文件监听。
     */
    @PreDestroy
    public void stopWatching() {
        watching.set(false);
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.debug("Error closing watch service: {}", e.getMessage());
            }
        }
        debounceExecutor.shutdownNow();
    }
}
