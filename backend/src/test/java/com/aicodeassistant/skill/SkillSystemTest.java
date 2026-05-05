package com.aicodeassistant.skill;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 技能系统测试集 — TC-SKILL-001 ~ TC-SKILL-005
 */
@DisplayName("技能系统测试集")
class SkillSystemTest {

    // ==================== TC-SKILL-001 ====================

    @Nested
    @DisplayName("TC-SKILL-001: 6级优先级加载验证")
    class SkillPriorityTest {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("高优先级技能覆盖低优先级")
        void highPriorityOverridesLow() {
            SkillRegistry registry = new SkillRegistry();
            registry.clear();

            // 注册低优先级 (BUNDLED)
            SkillDefinition bundled = SkillDefinition.fromMarkdown(
                "test-skill.md", "---\nname: test-skill\ndescription: bundled version\n---\nBundled content",
                SkillDefinition.SkillSource.BUNDLED, null);
            registry.registerBuiltin(bundled);

            // 注册高优先级 (USER)
            SkillDefinition user = SkillDefinition.fromMarkdown(
                "test-skill.md", "---\nname: test-skill\ndescription: user version\n---\nUser content",
                SkillDefinition.SkillSource.USER, "/tmp/test-skill.md");
            registry.register(user);

            SkillDefinition resolved = registry.resolve("test-skill");
            assertNotNull(resolved, "应能解析技能");
            assertEquals("user version", resolved.frontmatter().description(),
                "应使用高优先级(USER)版本");
        }

        @Test
        @DisplayName("listSkills 返回去重后的列表")
        void listSkillsDeduplication() {
            SkillRegistry registry = new SkillRegistry();
            registry.clear();

            SkillDefinition skill1 = SkillDefinition.fromMarkdown(
                "test-skill.md", "---\nname: test-skill\ndescription: v1\n---\nContent1",
                SkillDefinition.SkillSource.BUNDLED, null);
            registry.registerBuiltin(skill1);

            SkillDefinition skill2 = SkillDefinition.fromMarkdown(
                "test-skill.md", "---\nname: test-skill\ndescription: v2\n---\nContent2",
                SkillDefinition.SkillSource.PROJECT, "/tmp/test-skill.md");
            registry.register(skill2);

            // 同名技能 register 会覆盖
            long count = registry.getAllSkills().stream()
                .filter(s -> "test-skill".equals(s.name()))
                .count();
            assertEquals(1, count, "同名技能应去重");
        }

        @Test
        @DisplayName("resolve 返回最高优先级版本")
        void resolveReturnsHighestPriority() {
            SkillRegistry registry = new SkillRegistry();
            registry.clear();

            registry.registerBuiltin(SkillDefinition.fromMarkdown(
                "test.md", "---\nname: test\ndescription: bundled\n---\nB",
                SkillDefinition.SkillSource.BUNDLED, null));
            registry.register(SkillDefinition.fromMarkdown(
                "test.md", "---\nname: test\ndescription: project\n---\nP",
                SkillDefinition.SkillSource.PROJECT, "/p/test.md"));

            SkillDefinition resolved = registry.resolve("test");
            assertNotNull(resolved);
            assertEquals("project", resolved.frontmatter().description(),
                "应返回后注册的高优先级版本");
        }
    }

    // ==================== TC-SKILL-002 ====================

    @Nested
    @DisplayName("TC-SKILL-002: 5个内置技能执行验证")
    class BuiltinSkillsTest {

        @Test
        @DisplayName("内置技能列表不为 null")
        void builtinSkillsNotNull() {
            SkillRegistry registry = new SkillRegistry();
            registry.clear();
            registry.registerBuiltinSkills();
            Collection<SkillDefinition> builtins = registry.getBuiltinSkills();
            assertNotNull(builtins, "内置技能列表不应为null");
        }

        @Test
        @DisplayName("每个内置技能名称、描述、内容均不为空")
        void builtinSkillsHaveRequiredFields() {
            SkillRegistry registry = new SkillRegistry();
            registry.clear();
            registry.registerBuiltinSkills();
            for (SkillDefinition skill : registry.getBuiltinSkills()) {
                assertNotNull(skill.name(), "技能名称不应为null");
                assertFalse(skill.name().isBlank(), "技能名称不应为空: " + skill.fileName());
                assertNotNull(skill.effectiveDescription(),
                    "技能描述不应为null: " + skill.name());
                assertNotNull(skill.content(), "技能内容不应为null: " + skill.name());
            }
        }

        @Test
        @DisplayName("技能内容不为空")
        void builtinSkillContentNotBlank() {
            SkillRegistry registry = new SkillRegistry();
            registry.clear();
            registry.registerBuiltinSkills();
            for (SkillDefinition skill : registry.getBuiltinSkills()) {
                assertNotNull(skill.content(), "内容不应为null: " + skill.name());
                assertFalse(skill.content().isBlank(), "内容不应为空: " + skill.name());
            }
        }
    }

    // ==================== TC-SKILL-003 ====================

    @Nested
    @DisplayName("TC-SKILL-003: 热重载验证")
    class HotReloadTest {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("修改文件后技能重新加载")
        void skillReloadedAfterFileModification() throws Exception {
            Path skillsDir = tempDir.resolve(".zhikun/skills");
            Files.createDirectories(skillsDir);
            Path skillFile = skillsDir.resolve("hot-test.md");
            Files.writeString(skillFile,
                "---\nname: hot-test\ndescription: version 1\n---\nOriginal content");

            SkillRegistry registry = new SkillRegistry();
            registry.clear();
            registry.loadAndRegister(tempDir.toString());

            SkillDefinition v1 = registry.resolve("hot-test");
            assertNotNull(v1, "应加载初始技能");
            assertEquals("version 1", v1.frontmatter().description());

            // 修改文件内容
            Files.writeString(skillFile,
                "---\nname: hot-test\ndescription: version 2\n---\nUpdated content");

            // 重新加载
            registry.loadAndRegister(tempDir.toString());

            SkillDefinition v2 = registry.resolve("hot-test");
            assertNotNull(v2, "应重新加载技能");
            assertEquals("version 2", v2.frontmatter().description(),
                "技能应更新为 version 2");
        }

        @Test
        @DisplayName("连续快速修改后重新加载获取最新版本")
        void debounceMultipleRapidModifications() throws Exception {
            Path skillsDir = tempDir.resolve(".zhikun/skills");
            Files.createDirectories(skillsDir);
            Path skillFile = skillsDir.resolve("debounce-test.md");
            Files.writeString(skillFile,
                "---\nname: debounce-test\ndescription: initial\n---\nContent");

            SkillRegistry registry = new SkillRegistry();
            registry.clear();
            registry.loadAndRegister(tempDir.toString());

            // 连续修改 3 次
            for (int i = 1; i <= 3; i++) {
                Files.writeString(skillFile,
                    "---\nname: debounce-test\ndescription: v" + i + "\n---\nContent v" + i);
            }

            // 重新加载
            registry.loadAndRegister(tempDir.toString());
            SkillDefinition result = registry.resolve("debounce-test");
            assertNotNull(result);
            assertEquals("v3", result.frontmatter().description(),
                "应取最后一次修改的版本");
        }
    }

    // ==================== TC-SKILL-004 ====================

    @Nested
    @DisplayName("TC-SKILL-004: Markdown 定义解析验证")
    class MarkdownParsingTest {

        @Test
        @DisplayName("解析 test-skill.md 获取正确的 SkillDefinition")
        void parseTestSkillMarkdown() throws Exception {
            String content = new String(
                getClass().getResourceAsStream("/fixtures/test-skill.md").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

            FrontmatterParser.ParsedMarkdown parsed = FrontmatterParser.parse(content);

            assertNotNull(parsed.frontmatter(), "frontmatter 不应为null");
            assertEquals("test-greeting", parsed.frontmatter().name(),
                "name 应为 test-greeting");
            assertNotNull(parsed.frontmatter().description(),
                "description 不应为null");
            assertFalse(parsed.frontmatter().description().isBlank(),
                "description 不应为空");
        }

        @Test
        @DisplayName("验证 arguments 包含 username 和 language")
        void parseArguments() throws Exception {
            String content = new String(
                getClass().getResourceAsStream("/fixtures/test-skill.md").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

            FrontmatterParser.ParsedMarkdown parsed = FrontmatterParser.parse(content);
            FrontmatterData fm = parsed.frontmatter();

            assertNotNull(fm.arguments(), "arguments 不应为null");
            assertTrue(fm.arguments().contains("username"), "arguments 应包含 username");
            assertTrue(fm.arguments().contains("language"), "arguments 应包含 language");
        }

        @Test
        @DisplayName("内容包含 Instructions 段落")
        void contentContainsInstructions() throws Exception {
            String content = new String(
                getClass().getResourceAsStream("/fixtures/test-skill.md").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

            FrontmatterParser.ParsedMarkdown parsed = FrontmatterParser.parse(content);
            assertTrue(parsed.content().contains("Instructions"),
                "内容应包含 Instructions 段落");
        }

        @Test
        @DisplayName("模板变量 username 和 language 被识别")
        void templateVariablesRecognized() {
            String templateContent = "Greet {{username}} in {{language}}";
            var names = ArgumentSubstitution.parseArgumentNames(templateContent);
            assertTrue(names.contains("username"), "应识别 username 变量");
            assertTrue(names.contains("language"), "应识别 language 变量");
        }
    }

    // ==================== TC-SKILL-005 ====================

    @Nested
    @DisplayName("TC-SKILL-005: 参数替换验证")
    class ArgumentSubstitutionTest {

        @Test
        @DisplayName("username 替换成功")
        void substituteUsername() {
            String template = "Hello {{username}}";
            String result = ArgumentSubstitution.substitute(template,
                Map.of("username", "Alice"));
            assertEquals("Hello Alice", result);
        }

        @Test
        @DisplayName("language 替换成功")
        void substituteLanguage() {
            String template = "Greet in {{language}}";
            String result = ArgumentSubstitution.substitute(template,
                Map.of("language", "zh"));
            assertEquals("Greet in zh", result);
        }

        @Test
        @DisplayName("缺失参数保留占位符")
        void missingParamKeepsPlaceholder() {
            String template = "Hello {{username}} in {{language}}";
            String result = ArgumentSubstitution.substitute(template,
                Map.of("username", "Bob"));
            assertEquals("Hello Bob in {{language}}", result,
                "缺失参数应保留原始占位符");
        }

        @Test
        @DisplayName("null 参数保留原始内容")
        void nullParamsKeepOriginal() {
            String template = "Hello {{username}}";
            String result = ArgumentSubstitution.substitute(template, null);
            assertEquals("Hello {{username}}", result);
        }

        @Test
        @DisplayName("特殊字符转义验证")
        void specialCharacterEscaping() {
            String template = "Path: {{path}}";
            String result = ArgumentSubstitution.substitute(template,
                Map.of("path", "C:\\Users\\test$1"));
            assertEquals("Path: C:\\Users\\test$1", result,
                "特殊字符应正确处理");
        }

        @Test
        @DisplayName("多参数同时替换")
        void multipleSubstitutions() {
            String template = "请审查 {{file_path}} 文件的 {{language}} 代码";
            String result = ArgumentSubstitution.substitute(template,
                Map.of("file_path", "App.java", "language", "Java"));
            assertEquals("请审查 App.java 文件的 Java 代码", result);
        }

        @Test
        @DisplayName("解析参数名称列表")
        void parseArgumentNames() {
            String template = "{{name}} uses {{tool}} for {{purpose}}";
            var names = ArgumentSubstitution.parseArgumentNames(template);
            assertEquals(3, names.size());
            assertTrue(names.contains("name"));
            assertTrue(names.contains("tool"));
            assertTrue(names.contains("purpose"));
        }
    }
}
