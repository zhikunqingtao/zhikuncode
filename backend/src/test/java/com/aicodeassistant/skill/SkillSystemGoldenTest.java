package com.aicodeassistant.skill;

import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Skill 系统黄金测试 — 
 */
class SkillSystemGoldenTest {

    private SkillRegistry registry;
    private SkillExecutor executor;
    private SkillTool skillTool;
    private ToolUseContext defaultContext;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry();
        executor = new SkillExecutor(registry);
        skillTool = new SkillTool(executor, registry);
        defaultContext = ToolUseContext.of("/tmp/workspace", "test-session");
    }

    // ==================== §4.7.1 Frontmatter 解析 ====================

    @Nested
    @DisplayName("§4.7.1 Frontmatter 解析")
    class FrontmatterParsingTests {

        @Test
        @DisplayName("完整 frontmatter 解析 — 所有字段")
        void fullFrontmatterParsing() {
            String md = """
                    ---
                    name: create-component
                    description: 创建 React 组件
                    allowed-tools: Bash,Read,Write
                    argument-hint: <component_name>
                    model: standard
                    effort: high
                    context: fork
                    agent: general-purpose
                    user-invocable: true
                    shell: bash
                    ---
                    
                    # Create Component
                    
                    Please create {{component_name}} component.
                    """;

            var parsed = FrontmatterParser.parse(md);

            assertNotNull(parsed.frontmatter());
            assertEquals("create-component", parsed.frontmatter().name());
            assertEquals("创建 React 组件", parsed.frontmatter().description());
            assertEquals(List.of("Bash", "Read", "Write"), parsed.frontmatter().allowedTools());
            assertEquals("<component_name>", parsed.frontmatter().argumentHint());
            assertEquals("standard", parsed.frontmatter().model());
            assertEquals("high", parsed.frontmatter().effort());
            assertEquals("fork", parsed.frontmatter().context());
            assertEquals("general-purpose", parsed.frontmatter().agent());
            assertTrue(parsed.frontmatter().userInvocable());
            assertEquals("bash", parsed.frontmatter().shell());
            assertTrue(parsed.content().contains("Create Component"));
        }

        @Test
        @DisplayName("无 frontmatter 的纯 Markdown")
        void noFrontmatter() {
            String md = "# Just a title\n\nSome content here.";
            var parsed = FrontmatterParser.parse(md);

            assertNotNull(parsed.frontmatter());
            assertNull(parsed.frontmatter().name());
            assertTrue(parsed.frontmatter().userInvocable()); // 默认 true
            assertEquals("inline", parsed.frontmatter().context()); // 默认 inline
        }

        @Test
        @DisplayName("最小 frontmatter — 仅 description")
        void minimalFrontmatter() {
            String md = """
                    ---
                    description: 简化代码
                    ---
                    
                    Simplify the code.
                    """;
            var parsed = FrontmatterParser.parse(md);

            assertEquals("简化代码", parsed.frontmatter().description());
            assertEquals("inline", parsed.frontmatter().context());
            assertFalse(parsed.frontmatter().disableModelInvocation());
        }

        @Test
        @DisplayName("description fallback — 从正文第一段落提取")
        void descriptionFallback() {
            String md = """
                    ---
                    name: my-skill
                    ---
                    
                    # Title
                    
                    This is the first paragraph describing the skill.
                    
                    ## Details
                    More info here.
                    """;
            var parsed = FrontmatterParser.parse(md);

            assertEquals("This is the first paragraph describing the skill.",
                    parsed.frontmatter().description());
        }

        @Test
        @DisplayName("model='inherit' 返回 null")
        void modelInherit() {
            String md = """
                    ---
                    model: inherit
                    ---
                    Content
                    """;
            var parsed = FrontmatterParser.parse(md);

            assertNull(parsed.frontmatter().resolvedModel());
        }

        @Test
        @DisplayName("disable_model_invocation=true")
        void disableModelInvocation() {
            String md = """
                    ---
                    disable_model_invocation: true
                    ---
                    Content
                    """;
            var parsed = FrontmatterParser.parse(md);

            assertTrue(parsed.frontmatter().disableModelInvocation());
        }

        @Test
        @DisplayName("YAML 列表解析 — paths 字段")
        void yamlListParsing() {
            String md = """
                    ---
                    description: test
                    paths:
                      - src/components/**
                      - *.tsx
                    ---
                    Content
                    """;
            var parsed = FrontmatterParser.parse(md);

            assertEquals(List.of("src/components/**", "*.tsx"), parsed.frontmatter().paths());
        }

        @Test
        @DisplayName("空输入处理")
        void emptyInput() {
            var parsed = FrontmatterParser.parse("");
            assertNotNull(parsed.frontmatter());
            assertEquals("", parsed.content());
        }

        @Test
        @DisplayName("null 输入处理")
        void nullInput() {
            var parsed = FrontmatterParser.parse(null);
            assertNotNull(parsed.frontmatter());
        }
    }

    // ==================== §4.7.3 参数替换 ====================

    @Nested
    @DisplayName("§4.7.3 参数替换")
    class ArgumentSubstitutionTests {

        @Test
        @DisplayName("提取模板变量名")
        void parseArgumentNames() {
            String content = "Create {{component_name}} with {{style}} in {{dir}}";
            List<String> names = ArgumentSubstitution.parseArgumentNames(content);

            assertEquals(3, names.size());
            assertEquals("component_name", names.get(0));
            assertEquals("style", names.get(1));
            assertEquals("dir", names.get(2));
        }

        @Test
        @DisplayName("重复变量名去重")
        void deduplicateArgNames() {
            String content = "{{name}} and {{name}} again";
            List<String> names = ArgumentSubstitution.parseArgumentNames(content);

            assertEquals(1, names.size());
            assertEquals("name", names.get(0));
        }

        @Test
        @DisplayName("命名参数解析 key=value")
        void namedArgsParser() {
            Map<String, String> params = ArgumentSubstitution.parseArgs(
                    "component_name=Button style=tailwind",
                    List.of("component_name", "style"));

            assertEquals("Button", params.get("component_name"));
            assertEquals("tailwind", params.get("style"));
        }

        @Test
        @DisplayName("位置参数按顺序匹配")
        void positionalArgs() {
            Map<String, String> params = ArgumentSubstitution.parseArgs(
                    "Button tailwind",
                    List.of("component_name", "style"));

            assertEquals("Button", params.get("component_name"));
            assertEquals("tailwind", params.get("style"));
        }

        @Test
        @DisplayName("模板变量替换")
        void templateSubstitution() {
            String template = "Create {{component_name}} with {{style}}";
            Map<String, String> params = Map.of(
                    "component_name", "Button",
                    "style", "tailwind"
            );

            String result = ArgumentSubstitution.substitute(template, params);
            assertEquals("Create Button with tailwind", result);
        }

        @Test
        @DisplayName("未提供参数保留占位符")
        void missingArgsPreserved() {
            String template = "Create {{component_name}} with {{style}}";
            Map<String, String> params = Map.of("component_name", "Button");

            String result = ArgumentSubstitution.substitute(template, params);
            assertEquals("Create Button with {{style}}", result);
        }

        @Test
        @DisplayName("空参数返回原始内容")
        void emptyArgsReturnsOriginal() {
            String template = "No params here";
            String result = ArgumentSubstitution.substitute(template, Map.of());
            assertEquals(template, result);
        }

        @Test
        @DisplayName("null 参数安全处理")
        void nullArgs() {
            assertEquals("", ArgumentSubstitution.substitute(null, Map.of()));
            assertEquals("hello", ArgumentSubstitution.substitute("hello", null));
        }
    }

    // ==================== §4.7 SkillDefinition ====================

    @Nested
    @DisplayName("§4.7 SkillDefinition")
    class SkillDefinitionTests {

        @Test
        @DisplayName("从 Markdown 创建 SkillDefinition")
        void fromMarkdown() {
            String md = """
                    ---
                    description: 创建 React 组件
                    ---
                    
                    Create {{component_name}} component.
                    """;

            SkillDefinition skill = SkillDefinition.fromMarkdown(
                    "create-component.md", md,
                    SkillDefinition.SkillSource.PROJECT, "/path/to/skill.md");

            assertEquals("create-component", skill.name());
            assertEquals("create-component.md", skill.fileName());
            assertEquals("创建 React 组件", skill.effectiveDescription());
            assertEquals(SkillDefinition.SkillSource.PROJECT, skill.source());
        }

        @Test
        @DisplayName("effectiveName — frontmatter.name 优先")
        void effectiveNamePriority() {
            String md = """
                    ---
                    name: My Custom Name
                    ---
                    Content
                    """;

            SkillDefinition skill = SkillDefinition.fromMarkdown(
                    "original-name.md", md,
                    SkillDefinition.SkillSource.BUNDLED, null);

            assertEquals("My Custom Name", skill.effectiveName());
            assertEquals("original-name", skill.name());
        }

        @Test
        @DisplayName("parseArgs + renderTemplate 端到端")
        void parseAndRender() {
            String md = """
                    ---
                    description: test
                    arguments:
                      - component_name
                      - style
                    ---
                    
                    Create {{component_name}} with {{style}}.
                    """;

            SkillDefinition skill = SkillDefinition.fromMarkdown(
                    "test.md", md, SkillDefinition.SkillSource.PROJECT, null);

            Map<String, String> params = skill.parseArgs("Button tailwind");
            String rendered = skill.renderTemplate(params);

            assertEquals("Create Button with tailwind.", rendered);
        }
    }

    // ==================== §4.7.3 SkillRegistry ====================

    @Nested
    @DisplayName("§4.7.3 SkillRegistry")
    class SkillRegistryTests {

        @Test
        @DisplayName("注册并解析技能")
        void registerAndResolve() {
            SkillDefinition skill = createTestSkill("test-skill", "Test description");
            registry.register(skill);

            SkillDefinition found = registry.resolve("test-skill");
            assertNotNull(found);
            assertEquals("test-skill", found.name());
        }

        @Test
        @DisplayName("大小写不敏感查找")
        void caseInsensitiveResolve() {
            registry.register(createTestSkill("MySkill", "desc"));

            assertNotNull(registry.resolve("myskill"));
            assertNotNull(registry.resolve("MYSKILL"));
        }

        @Test
        @DisplayName("去除 / 前缀查找")
        void stripSlashPrefix() {
            registry.register(createTestSkill("commit", "desc"));

            assertNotNull(registry.resolve("/commit"));
        }

        @Test
        @DisplayName("注册内置技能")
        void registerBuiltin() {
            SkillDefinition skill = createTestSkill("simplify", "Simplify code");
            registry.registerBuiltin(skill);

            assertEquals(1, registry.getBuiltinSkills().size());
            assertNotNull(registry.resolve("simplify"));
        }

        @Test
        @DisplayName("同名技能覆盖")
        void overrideByName() {
            SkillDefinition builtin = createTestSkillWithSource(
                    "debug", "Builtin", SkillDefinition.SkillSource.BUNDLED);
            SkillDefinition project = createTestSkillWithSource(
                    "debug", "Project override", SkillDefinition.SkillSource.PROJECT);

            registry.registerBuiltin(builtin);
            registry.register(project); // 项目覆盖内置

            SkillDefinition found = registry.resolve("debug");
            assertEquals("Project override", found.effectiveDescription());
        }

        @Test
        @DisplayName("未找到返回 null")
        void resolveNotFound() {
            assertNull(registry.resolve("nonexistent"));
            assertNull(registry.resolve(null));
        }

        @Test
        @DisplayName("目录扫描 — .zhikun/skills/")
        void directoryScanning() throws IOException {
            // 创建临时目录结构
            Path tempDir = Files.createTempDirectory("skill-test");
            Path skillsDir = tempDir.resolve(".zhikun/skills");
            Files.createDirectories(skillsDir);

            String skillContent = """
                    ---
                    description: 测试技能
                    ---
                    
                    This is a test skill.
                    """;
            Files.writeString(skillsDir.resolve("test-scan.md"), skillContent);

            List<SkillDefinition> loaded = registry.getProjectSkills(tempDir.toString());

            assertEquals(1, loaded.size());
            assertEquals("test-scan", loaded.get(0).name());
            assertEquals("测试技能", loaded.get(0).effectiveDescription());

            // 清理
            Files.deleteIfExists(skillsDir.resolve("test-scan.md"));
            Files.deleteIfExists(skillsDir);
            Files.deleteIfExists(tempDir.resolve(".zhikun"));
            Files.deleteIfExists(tempDir);
        }

        @Test
        @DisplayName("空目录不报错")
        void emptyDirectoryNoError() {
            List<SkillDefinition> result = registry.getProjectSkills("/nonexistent/path");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("size() 正确计数")
        void sizeCount() {
            registry.register(createTestSkill("a", "desc"));
            registry.register(createTestSkill("b", "desc"));
            assertEquals(2, registry.size());
        }
    }

    // ==================== §4.7.3 SkillExecutor ====================

    @Nested
    @DisplayName("§4.7.3 SkillExecutor")
    class SkillExecutorTests {

        @Test
        @DisplayName("inline 模式执行")
        void inlineExecution() {
            registerSkill("greet", "inline", "Hello {{name}}!");

            ToolResult result = executor.execute("greet", "name=World", defaultContext);

            assertFalse(result.isError());
            assertTrue(result.content().contains("greet"));
            assertEquals("inline", result.metadata().get("executionMode"));
            assertEquals("Hello World!", result.metadata().get("injectedPrompt"));
        }

        @Test
        @DisplayName("fork 模式执行")
        void forkExecution() {
            registerSkill("review", "fork", "Review {{file}} please.");

            ToolResult result = executor.execute("review", "file=App.java", defaultContext);

            assertFalse(result.isError());
            assertEquals("fork", result.metadata().get("executionMode"));
            assertTrue(result.content().contains("fork mode"));
        }

        @Test
        @DisplayName("技能未找到返回错误")
        void skillNotFound() {
            ToolResult result = executor.execute("nonexistent", "", defaultContext);

            assertTrue(result.isError());
            assertTrue(result.content().contains("Skill not found"));
        }
    }

    // ==================== §4.1.20 SkillTool ====================

    @Nested
    @DisplayName("§4.1.20 SkillTool")
    class SkillToolTests {

        @Test
        @DisplayName("SkillTool 基本属性")
        void toolProperties() {
            assertEquals("Skill", skillTool.getName());
            assertNotNull(skillTool.getDescription());
            assertNotNull(skillTool.getInputSchema());
            assertTrue(skillTool.shouldDefer());
            assertEquals("skill", skillTool.getGroup());
        }

        @Test
        @DisplayName("SkillTool.call() — 正常执行")
        void callSuccess() {
            registerSkill("deploy", "inline", "Deploy {{app}} now.");

            ToolInput input = ToolInput.from(Map.of(
                    "skill", "deploy",
                    "args", "app=myapp"
            ));

            ToolResult result = skillTool.call(input, defaultContext);

            assertFalse(result.isError());
            assertTrue(result.content().contains("deploy"));
        }

        @Test
        @DisplayName("SkillTool.call() — 去除 / 前缀")
        void callWithSlashPrefix() {
            registerSkill("commit", "inline", "Commit changes.");

            ToolInput input = ToolInput.from(Map.of("skill", "/commit"));

            ToolResult result = skillTool.call(input, defaultContext);
            assertFalse(result.isError());
        }

        @Test
        @DisplayName("SkillTool.call() — 技能不存在")
        void callNotFound() {
            ToolInput input = ToolInput.from(Map.of("skill", "unknown-skill"));

            ToolResult result = skillTool.call(input, defaultContext);
            assertTrue(result.isError());
            assertTrue(result.content().contains("Skill not found"));
        }

        @Test
        @DisplayName("isConcurrencySafe 返回 true")
        void concurrencySafe() {
            ToolInput input = ToolInput.from(Map.of("skill", "test"));
            assertTrue(skillTool.isConcurrencySafe(input));
        }

        @Test
        @DisplayName("inputSchema 包含 skill 和 args")
        void inputSchemaFields() {
            Map<String, Object> schema = skillTool.getInputSchema();
            assertEquals("object", schema.get("type"));

            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) schema.get("properties");
            assertTrue(props.containsKey("skill"));
            assertTrue(props.containsKey("args"));

            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) schema.get("required");
            assertTrue(required.contains("skill"));
        }
    }

    // ==================== FrontmatterData ====================

    @Nested
    @DisplayName("FrontmatterData")
    class FrontmatterDataTests {

        @Test
        @DisplayName("defaults() 返回合理默认值")
        void defaultValues() {
            FrontmatterData d = FrontmatterData.defaults();

            assertNull(d.description());
            assertNull(d.name());
            assertTrue(d.allowedTools().isEmpty());
            assertTrue(d.userInvocable());
            assertFalse(d.disableModelInvocation());
            assertEquals("inline", d.context());
            assertEquals("bash", d.shell());
            assertFalse(d.isFork());
        }

        @Test
        @DisplayName("isFork() 正确判断")
        void isForkCheck() {
            FrontmatterData fork = new FrontmatterData(
                    null, null, List.of(), null, List.of(),
                    null, null, null, false, true,
                    Map.of(), null, "fork", null, List.of(), "bash"
            );
            assertTrue(fork.isFork());

            FrontmatterData inline = FrontmatterData.defaults();
            assertFalse(inline.isFork());
        }
    }

    // ==================== 辅助方法 ====================

    private SkillDefinition createTestSkill(String name, String description) {
        return createTestSkillWithSource(name, description, SkillDefinition.SkillSource.PROJECT);
    }

    private SkillDefinition createTestSkillWithSource(String name, String description,
                                                       SkillDefinition.SkillSource source) {
        FrontmatterData fm = new FrontmatterData(
                description, null, List.of(), null, List.of(),
                null, null, null, false, true,
                Map.of(), null, "inline", null, List.of(), "bash"
        );
        return new SkillDefinition(name, name + ".md", fm, "Content", source, null);
    }

    private void registerSkill(String name, String context, String template) {
        String md = "---\ndescription: " + name + " skill\ncontext: " + context
                + "\n---\n\n" + template;
        SkillDefinition skill = SkillDefinition.fromMarkdown(
                name + ".md", md, SkillDefinition.SkillSource.PROJECT, null);
        registry.register(skill);
    }
}
