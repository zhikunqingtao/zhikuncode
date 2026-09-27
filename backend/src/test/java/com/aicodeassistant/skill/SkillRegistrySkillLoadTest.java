package com.aicodeassistant.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 技能加载契约使用临时项目和实际 bundled 资源，不依赖开发者的本地技能。 */
class SkillRegistrySkillLoadTest {

    private static final Set<String> EXPECTED_BUILTINS = Set.of(
            "commit", "review", "test", "pr", "debug", "verify",
            "remember", "software-architecture", "csv-data-summarizer",
            "prompt-engineering", "test-driven-development", "publish-oss", "publish-meoo");

    @TempDir
    Path project;

    @Test
    void projectSkillLoadsWithParsedMetadataAndSource() throws Exception {
        Path file = writeProjectSkill("fixture-skill", "Project fixture", "Fixture body.");
        try (IsolatedRegistry registry = new IsolatedRegistry()) {
            List<SkillDefinition> loaded = registry.getProjectSkills(project.toString());
            assertThat(loaded).hasSize(1);
            loaded.forEach(registry::register);

            SkillDefinition skill = registry.resolve("fixture-skill");
            assertThat(skill).isNotNull();
            assertThat(skill.frontmatter().description()).isEqualTo("Project fixture");
            assertThat(skill.source()).isEqualTo(SkillDefinition.SkillSource.PROJECT);
            assertThat(skill.filePath()).isEqualTo(file.toAbsolutePath().toString());
            assertThat(skill.content()).isEqualTo("Fixture body.");
        }
    }

    @Test
    void projectDefinitionOverridesBundledDefinitionWhenRegistered() throws Exception {
        writeProjectSkill("debug", "Project debug", "Project-specific debugging.");
        try (IsolatedRegistry registry = new IsolatedRegistry()) {
            registry.registerBuiltinSkills();
            SkillDefinition bundled = registry.resolve("debug");
            assertThat(bundled).isNotNull();
            assertThat(bundled.source()).isEqualTo(SkillDefinition.SkillSource.BUNDLED);

            registry.getProjectSkills(project.toString()).forEach(registry::register);

            assertThat(registry.resolve("debug").source())
                    .isEqualTo(SkillDefinition.SkillSource.PROJECT);
            assertThat(registry.resolve("debug").content())
                    .isEqualTo("Project-specific debugging.");
            assertThat(registry.getBuiltinSkills()).contains(bundled);
        }
    }

    @Test
    void freshRegistryKeepsBundledSkillAfterProjectCopyIsRemoved() throws Exception {
        Path copy = writeProjectSkill("debug", "Project debug", "Project-only body.");
        try (IsolatedRegistry beforeRestart = new IsolatedRegistry()) {
            beforeRestart.registerBuiltinSkills();
            beforeRestart.getProjectSkills(project.toString()).forEach(beforeRestart::register);
            assertThat(beforeRestart.resolve("debug").source())
                    .isEqualTo(SkillDefinition.SkillSource.PROJECT);
        }
        Files.delete(copy);

        // 新注册表模拟重启后的加载；不对运行中删除的 fallback 作保证。
        try (IsolatedRegistry afterRestart = new IsolatedRegistry()) {
            afterRestart.registerBuiltinSkills();
            assertThat(afterRestart.getProjectSkills(project.toString())).isEmpty();
            SkillDefinition debug = afterRestart.resolve("debug");
            assertThat(debug).isNotNull();
            assertThat(debug.source()).isEqualTo(SkillDefinition.SkillSource.BUNDLED);
            assertThat(debug.content()).isNotBlank().isNotEqualTo("Project-only body.");
        }
    }

    @Test
    void allThirteenBundledSkillsRemainLoadableAndNonEmpty() {
        try (IsolatedRegistry registry = new IsolatedRegistry()) {
            registry.registerBuiltinSkills();

            assertThat(registry.getBuiltinSkills()).extracting(SkillDefinition::name)
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_BUILTINS);
            for (SkillDefinition skill : registry.getBuiltinSkills()) {
                assertThat(registry.resolve(skill.name())).isSameAs(skill);
                assertThat(skill.source()).isEqualTo(SkillDefinition.SkillSource.BUNDLED);
                assertThat(skill.effectiveDescription()).as(skill.name()).isNotBlank();
                assertThat(skill.content()).as(skill.name()).isNotBlank();
            }
        }
    }

    private Path writeProjectSkill(String name, String description, String body) throws Exception {
        Path directory = Files.createDirectories(project.resolve(".zhikun/skills"));
        return Files.writeString(directory.resolve(name + ".md"), """
                ---
                name: %s
                description: %s
                context: inline
                ---
                %s
                """.formatted(name, description, body));
    }

    /** 保留实际 classpath 加载和解析，只隔离启动时的外部目录扫描及监听。 */
    private static final class IsolatedRegistry extends SkillRegistry implements AutoCloseable {
        @Override
        public void loadAndRegister(String workingDirectory) {
            // 项目 fixture 由各测试显式加载；不读取当前用户或工作目录。
        }

        @Override
        public void startWatching(String workingDirectory) {
            // 这些测试覆盖启动加载，不验证文件监听。
        }

        @Override
        public void close() {
            stopWatching();
        }
    }
}
