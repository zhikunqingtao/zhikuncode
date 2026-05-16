package com.aicodeassistant.skill;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 验证 .zhikun/skills/ 下新建的 Skill 文件能被 SkillRegistry 正常加载和解析。
 * <p>
 * 纯单元测试 — 不启动 Spring 容器，直接调用 SkillRegistry 的加载逻辑。
 */
class SkillRegistrySkillLoadTest {

    @Test
    void testProjectSkillsCanBeLoaded() {
        SkillRegistry registry = new SkillRegistry();

        // 定位项目根目录下的 .zhikun/skills/
        String projectRoot = System.getProperty("user.dir");
        // IDE 中 user.dir 可能是 backend/，尝试上一级
        Path skillsDir = Path.of(projectRoot, ".zhikun", "skills");
        if (!skillsDir.toFile().isDirectory()) {
            Path parent = Path.of(projectRoot).getParent();
            if (parent != null) {
                skillsDir = parent.resolve(".zhikun").resolve("skills");
                projectRoot = parent.toString();
            }
        }

        if (!skillsDir.toFile().isDirectory()) {
            // CI 环境可能没有 .zhikun/skills/，跳过
            return;
        }

        List<SkillDefinition> loaded = registry.loadSkillsFromDir(
                skillsDir, SkillDefinition.SkillSource.PROJECT);
        loaded.forEach(registry::register);

        // 验证 5 个第二梯队 skill 文件可被加载并 resolve
        assertThat(registry.resolve("batch")).isNotNull();
        assertThat(registry.resolve("debug")).isNotNull();
        assertThat(registry.resolve("verify")).isNotNull();
        assertThat(registry.resolve("refactor")).isNotNull();
        assertThat(registry.resolve("deploy")).isNotNull();
    }

    @Test
    void testLoadedSkillsHaveFrontmatter() {
        SkillRegistry registry = new SkillRegistry();

        String projectRoot = System.getProperty("user.dir");
        Path skillsDir = Path.of(projectRoot, ".zhikun", "skills");
        if (!skillsDir.toFile().isDirectory()) {
            Path parent = Path.of(projectRoot).getParent();
            if (parent != null) {
                skillsDir = parent.resolve(".zhikun").resolve("skills");
            }
        }

        if (!skillsDir.toFile().isDirectory()) {
            return;
        }

        List<SkillDefinition> loaded = registry.loadSkillsFromDir(
                skillsDir, SkillDefinition.SkillSource.PROJECT);
        loaded.forEach(registry::register);

        SkillDefinition debug = registry.resolve("debug");
        assertThat(debug).isNotNull();
        assertThat(debug.frontmatter()).isNotNull();
        assertThat(debug.content()).isNotEmpty();
        assertThat(debug.source()).isEqualTo(SkillDefinition.SkillSource.PROJECT);
    }
}
