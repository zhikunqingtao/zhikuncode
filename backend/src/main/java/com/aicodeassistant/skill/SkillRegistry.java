package com.aicodeassistant.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 技能注册表 — 管理从 5 种来源加载的技能。
 * <p>
 * 加载来源优先级 (高→低):
 * <ol>
 *     <li>managed — 策略管理目录</li>
 *     <li>user    — 用户全局目录 (~/.qoder/skills/)</li>
 *     <li>project — 项目目录 (.qoder/skills/)</li>
 *     <li>plugin  — 插件提供的技能</li>
 *     <li>bundled — 内置技能</li>
 *     <li>mcp     — MCP 构建的技能</li>
 * </ol>
 * 同名技能按优先级链覆盖。
 *
 * @see <a href="SPEC §4.7.3">技能加载引擎</a>
 */
@Service
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    /** 所有已注册技能 (name → definition)，按优先级覆盖 */
    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();

    /** 内置技能单独缓存 */
    private final Map<String, SkillDefinition> builtinSkills = new ConcurrentHashMap<>();

    /** 用户全局技能目录 */
    private static final String USER_SKILLS_DIR = ".qoder/skills";

    /** 项目技能目录 */
    private static final String PROJECT_SKILLS_DIR = ".qoder/skills";

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
     * 获取项目技能 — 扫描项目 .qoder/skills/ 目录。
     */
    public List<SkillDefinition> getProjectSkills(String workingDirectory) {
        if (workingDirectory == null) return List.of();
        Path skillsDir = Path.of(workingDirectory, PROJECT_SKILLS_DIR);
        return loadSkillsFromDir(skillsDir, SkillDefinition.SkillSource.PROJECT);
    }

    /**
     * 获取用户全局技能 — 扫描 ~/.qoder/skills/ 目录。
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
     * 加载并注册指定目录下的所有 .qoder/skills/ 技能。
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
}
