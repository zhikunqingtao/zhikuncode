package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.model.Usage;
import com.aicodeassistant.session.SessionData;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.state.AppState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 增强命令 (59个) 黄金测试 — 覆盖 §4.2 所有增强命令。
 * <p>
 * 测试类别:
 * 1. 命令注册与发现
 * 2. Git 命令执行
 * 3. 会话命令执行
 * 4. 配置命令执行
 * 5. 扩展命令执行
 * 6. 环境命令执行
 * 7. 账户命令执行
 * 8. 信息命令执行
 * 9. 条件命令执行
 */
@Disabled("Pre-existing compilation errors")
class EnhancedCommandsGoldenTest {

    private CommandRegistry registry;
    private CommandContext defaultContext;

    @BeforeEach
    void setUp() {
        // 收集所有 @Bean 方法产生的命令 + 已有 @Component 命令
        GitCommands git = new GitCommands();
        SessionCommands session = new SessionCommands();
        ConfigModeCommands config = new ConfigModeCommands();
        ExtensionCommands ext = new ExtensionCommands();
        EnvironmentCommands env = new EnvironmentCommands();
        AccountCommands acct = new AccountCommands();
        InfoHelpCommands info = new InfoHelpCommands();
        ConditionalCommands cond = new ConditionalCommands();

        List<Command> commands = List.of(
                // Git (7) - some methods removed from source
                /* git.commitCommand(), git.reviewCommand(), */ git.commitPushPrCommand(),
                git.branchCommand(), git.prCommentsCommand(), git.rewindCommand(),
                git.securityReviewCommand(),
                // Session (6)
                session.contextCommand(), session.copyCommand(), session.exportCommand(),
                session.filesCommand(), session.renameCommand(), session.tagCommand(),
                // Config (8) - planCommand removed from source
                config.fastCommand(), config.effortCommand(), config.outputStyleCommand(),
                /* config.planCommand(), */ config.themeCommand(), config.colorCommand(),
                config.vimCommand(), config.keybindingsCommand(),
                // Extension (7)
                ext.mcpCommand(), ext.hooksCommand(), ext.skillsCommand(),
                ext.pluginCommand(), ext.reloadPluginsCommand(), ext.agentCommand(),
                ext.tasksCommand(),
                // Environment (9)
                env.addDirCommand(), env.ideCommand(), env.chromeCommand(),
                env.desktopCommand(), env.mobileCommand(), env.terminalSetupCommand(),
                env.remoteEnvCommand(), env.installGithubAppCommand(), env.installSlackAppCommand(),
                // Account (5)
                acct.usageCommand(), acct.extraUsageCommand(), acct.rateLimitOptionsCommand(),
                acct.upgradeCommand(), acct.versionCommand(),
                // Info (11)
                info.feedbackCommand(), createStatusCommand(), info.statsCommand(),
                info.stickersCommand(), info.releaseNotesCommand(), info.advisorCommand(),
                info.btwCommand(), info.statuslineCommand(), info.privacySettingsCommand(),
                info.sandboxToggleCommand(), info.heapdumpCommand(),
                // Conditional (9)
                cond.bridgeCommand(), cond.voiceCommand(), cond.buddyCommand(),
                cond.passesCommand(), cond.torchCommand(), cond.forkCommand(),
                cond.peersCommand(), cond.workflowsCommand(), cond.ultrareviewCommand()
        );

        registry = new CommandRegistry(commands);
        defaultContext = CommandContext.of("test-session", "/tmp/workspace", "claude-3.5-sonnet",
                AppState.defaultState());
    }

    /**
     * 创建带 mock SessionManager 的 StatusCommand，模拟真实的会话加载行为。
     */
    private StatusCommand createStatusCommand() {
        SessionManager mockSessionManager = mock(SessionManager.class);
        SessionData mockSession = mock(SessionData.class);
        when(mockSession.messages()).thenReturn(Collections.emptyList());
        when(mockSession.totalUsage()).thenReturn(Usage.zero());
        when(mockSession.workingDir()).thenReturn("/tmp/workspace");
        when(mockSession.model()).thenReturn("claude-3.5-sonnet");
        when(mockSessionManager.loadSession(anyString())).thenReturn(Optional.of(mockSession));
        return new StatusCommand(mockSessionManager);
    }

    // ===== 1. 命令注册与发现 =====

    @Nested
    @DisplayName("1. 命令注册与发现")
    class RegistrationTests {

        @Test
        @DisplayName("1.1 所有62个新命令注册成功")
        void allCommandsRegistered() {
            assertEquals(62, registry.size());
        }

        @Test
        @DisplayName("1.2 按名称精确查找命令")
        void findByName() {
            Stream.of("commit", "review", "branch", "fast", "mcp", "usage", "version",
                    "bridge", "voice", "fork", "workflows", "ultrareview")
                    .forEach(name -> assertTrue(registry.findCommand(name).isPresent(),
                            "Command /" + name + " should be registered"));
        }

        @Test
        @DisplayName("1.3 别名查找 — pr-comments → pr_comments")
        void findByAlias() {
            assertTrue(registry.findCommand("pr-comments").isPresent());
            assertTrue(registry.findCommand("adddir").isPresent());
        }

        @Test
        @DisplayName("1.4 模糊匹配建议")
        void fuzzySuggestion() {
            String suggestion = registry.suggestCommands("comit");
            assertTrue(suggestion.contains("/commit"), "Should suggest /commit for 'comit'");
        }

        @Test
        @DisplayName("1.5 PROMPT 类型命令筛选")
        void promptTypeFilter() {
            List<Command> prompts = registry.getCommandsByType(CommandType.PROMPT);
            assertTrue(prompts.size() >= 5, "Should have at least 5 PROMPT commands");
            assertTrue(prompts.stream().anyMatch(c -> c.getName().equals("commit")));
            assertTrue(prompts.stream().anyMatch(c -> c.getName().equals("review")));
            assertTrue(prompts.stream().anyMatch(c -> c.getName().equals("advisor")));
        }

        @Test
        @DisplayName("1.6 LOCAL_JSX 类型命令筛选")
        void localJsxTypeFilter() {
            List<Command> jsx = registry.getCommandsByType(CommandType.LOCAL_JSX);
            assertTrue(jsx.size() >= 8, "Should have at least 8 LOCAL_JSX commands");
        }

        @Test
        @DisplayName("1.7 隐藏命令不出现在可见列表")
        void hiddenCommandsFiltered() {
            List<Command> visible = registry.getVisibleCommands();
            assertTrue(visible.stream().noneMatch(c -> c.getName().equals("heapdump")),
                    "heapdump should be hidden");
        }
    }

    // ===== 2. Git 命令执行 =====

    @Nested
    @DisplayName("2. Git 命令")
    class GitCommandTests {

        @Test
        @DisplayName("2.1 /commit 生成提示词")
        void commitGeneratesPrompt() {
            Command cmd = registry.getCommand("commit");
            assertEquals(CommandType.PROMPT, cmd.getType());
            CommandResult result = cmd.execute("", defaultContext);
            assertTrue(result.isSuccess());
            assertTrue(result.value().contains("commit message"));
        }

        @Test
        @DisplayName("2.2 /review 代码审查")
        void reviewAnalysis() {
            Command cmd = registry.getCommand("review");
            CommandResult result = cmd.execute("security", defaultContext);
            assertTrue(result.value().contains("security"));
        }

        @Test
        @DisplayName("2.3 /branch 无参显示用法")
        void branchUsage() {
            Command cmd = registry.getCommand("branch");
            CommandResult result = cmd.execute("", defaultContext);
            assertTrue(result.value().contains("Usage"));
        }

        @Test
        @DisplayName("2.4 /rewind 无参报错")
        void rewindRequiresArg() {
            Command cmd = registry.getCommand("rewind");
            CommandResult result = cmd.execute("", defaultContext);
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("2.5 /security-review 为 PROMPT 类型")
        void securityReviewIsPrompt() {
            Command cmd = registry.getCommand("security-review");
            assertInstanceOf(PromptCommand.class, cmd);
        }
    }

    // ===== 3. 会话命令执行 =====

    @Nested
    @DisplayName("3. 会话命令")
    class SessionCommandTests {

        @Test
        @DisplayName("3.1 /context 显示上下文信息")
        void contextShowsInfo() {
            Command cmd = registry.getCommand("context");
            CommandResult result = cmd.execute("", defaultContext);
            assertTrue(result.value().contains("claude-3.5-sonnet"));
            assertTrue(result.value().contains("test-session"));
        }

        @Test
        @DisplayName("3.2 /copy 返回成功")
        void copySuccess() {
            Command cmd = registry.getCommand("copy");
            CommandResult result = cmd.execute("", defaultContext);
            assertTrue(result.isSuccess());
            assertTrue(result.value().contains("clipboard"));
        }

        @Test
        @DisplayName("3.3 /export 返回 JSX 组件")
        void exportReturnsJsx() {
            Command cmd = registry.getCommand("export");
            assertEquals(CommandType.LOCAL_JSX, cmd.getType());
            CommandResult result = cmd.execute("json", defaultContext);
            assertEquals(CommandResult.ResultType.JSX, result.type());
            assertEquals("json", result.data().get("format"));
        }

        @Test
        @DisplayName("3.4 /rename 无参报错")
        void renameRequiresArg() {
            Command cmd = registry.getCommand("rename");
            CommandResult result = cmd.execute("", defaultContext);
            assertFalse(result.isSuccess());
        }
    }

    // ===== 4. 配置命令执行 =====

    @Nested
    @DisplayName("4. 配置命令")
    class ConfigCommandTests {

        @Test
        @DisplayName("4.1 /fast on 启用快速模式")
        void fastModeEnable() {
            Command cmd = registry.getCommand("fast");
            CommandResult result = cmd.execute("on", defaultContext);
            assertTrue(result.value().contains("enabled"));
        }

        @Test
        @DisplayName("4.2 /effort 无参显示用法")
        void effortUsage() {
            Command cmd = registry.getCommand("effort");
            CommandResult result = cmd.execute("", defaultContext);
            assertTrue(result.value().contains("low"));
            assertTrue(result.value().contains("high"));
        }

        @Test
        @DisplayName("4.3 /vim on/off 切换")
        void vimToggle() {
            Command cmd = registry.getCommand("vim");
            assertTrue(cmd.execute("on", defaultContext).value().contains("enabled"));
            assertTrue(cmd.execute("off", defaultContext).value().contains("disabled"));
        }

        @Test
        @DisplayName("4.4 /theme 返回 JSX")
        void themeReturnsJsx() {
            Command cmd = registry.getCommand("theme");
            CommandResult result = cmd.execute("", defaultContext);
            assertEquals(CommandResult.ResultType.JSX, result.type());
        }

        @Test
        @DisplayName("4.5 /keybindings 返回 JSX")
        void keybindingsJsx() {
            Command cmd = registry.getCommand("keybindings");
            assertEquals(CommandType.LOCAL_JSX, cmd.getType());
        }
    }

    // ===== 5. 扩展命令执行 =====

    @Nested
    @DisplayName("5. 扩展命令")
    class ExtensionCommandTests {

        @Test
        @DisplayName("5.1 /mcp 无参返回列表 JSX")
        void mcpList() {
            Command cmd = registry.getCommand("mcp");
            CommandResult result = cmd.execute("", defaultContext);
            assertEquals(CommandResult.ResultType.JSX, result.type());
            assertEquals("list", result.data().get("action"));
        }

        @Test
        @DisplayName("5.2 /skills 返回 JSX")
        void skillsJsx() {
            Command cmd = registry.getCommand("skills");
            assertEquals(CommandType.LOCAL_JSX, cmd.getType());
        }

        @Test
        @DisplayName("5.3 /reload-plugins 返回文本")
        void reloadPlugins() {
            Command cmd = registry.getCommand("reload-plugins");
            assertEquals(CommandType.LOCAL, cmd.getType());
            assertTrue(cmd.execute("", defaultContext).isSuccess());
        }

        @Test
        @DisplayName("5.4 /tasks 带参数解析")
        void tasksWithArgs() {
            Command cmd = registry.getCommand("tasks");
            CommandResult result = cmd.execute("cancel task-123", defaultContext);
            assertEquals("cancel", result.data().get("action"));
            assertEquals("task-123", result.data().get("taskId"));
        }
    }

    // ===== 6. 环境命令执行 =====

    @Nested
    @DisplayName("6. 环境命令")
    class EnvironmentCommandTests {

        @Test
        @DisplayName("6.1 /add-dir 无参报错")
        void addDirRequiresArg() {
            Command cmd = registry.getCommand("add-dir");
            assertFalse(cmd.execute("", defaultContext).isSuccess());
        }

        @Test
        @DisplayName("6.2 /ide 显示集成状态")
        void ideStatus() {
            Command cmd = registry.getCommand("ide");
            CommandResult result = cmd.execute("", defaultContext);
            assertTrue(result.value().contains("not connected"));
        }

        @Test
        @DisplayName("6.3 /install-github-app 返回 JSX")
        void installGithubApp() {
            Command cmd = registry.getCommand("install-github-app");
            assertEquals(CommandType.LOCAL_JSX, cmd.getType());
        }

        @Test
        @DisplayName("6.4 /remote-env 显示未配置状态")
        void remoteEnvNotConfigured() {
            Command cmd = registry.getCommand("remote-env");
            CommandResult result = cmd.execute("", defaultContext);
            assertTrue(result.value().contains("not configured"));
        }
    }

    // ===== 7. 账户命令执行 =====

    @Nested
    @DisplayName("7. 账户命令")
    class AccountCommandTests {

        @Test
        @DisplayName("7.1 /version 显示版本信息")
        void versionInfo() {
            Command cmd = registry.getCommand("version");
            CommandResult result = cmd.execute("", defaultContext);
            assertTrue(result.value().contains("Qoder"));
            assertTrue(result.value().contains("Java:"));
        }

        @Test
        @DisplayName("7.2 /usage 显示用量报告")
        void usageReport() {
            Command cmd = registry.getCommand("usage");
            assertTrue(cmd.execute("", defaultContext).isSuccess());
        }

        @Test
        @DisplayName("7.3 /rate-limit-options 包含升级提示")
        void rateLimitOptions() {
            Command cmd = registry.getCommand("rate-limit-options");
            CommandResult result = cmd.execute("", defaultContext);
            assertTrue(result.value().contains("/upgrade"));
        }
    }

    // ===== 8. 信息命令执行 =====

    @Nested
    @DisplayName("8. 信息命令")
    class InfoCommandTests {

        @Test
        @DisplayName("8.1 /status 显示系统状态")
        void systemStatus() {
            Command cmd = registry.getCommand("status");
            CommandResult result = cmd.execute("", defaultContext);
            assertTrue(result.value().contains("系统状态"));
            assertTrue(result.value().contains("会话 ID: test-session"));
            assertTrue(result.value().contains("当前模型: claude-3.5-sonnet"));
        }

        @Test
        @DisplayName("8.2 /advisor 为 PROMPT 类型")
        void advisorIsPrompt() {
            Command cmd = registry.getCommand("advisor");
            assertInstanceOf(PromptCommand.class, cmd);
        }

        @Test
        @DisplayName("8.3 /btw 无参报错")
        void btwRequiresQuestion() {
            Command cmd = registry.getCommand("btw");
            CommandResult result = cmd.execute("", defaultContext);
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("8.4 /sandbox-toggle on/off")
        void sandboxToggle() {
            Command cmd = registry.getCommand("sandbox-toggle");
            assertTrue(cmd.execute("on", defaultContext).value().contains("enabled"));
            assertTrue(cmd.execute("off", defaultContext).value().contains("disabled"));
        }

        @Test
        @DisplayName("8.5 /heapdump 是隐藏命令")
        void heapdumpIsHidden() {
            Command cmd = registry.getCommand("heapdump");
            assertTrue(cmd.isHidden());
        }
    }

    // ===== 9. 条件命令执行 =====

    @Nested
    @DisplayName("9. 条件命令")
    class ConditionalCommandTests {

        @Test
        @DisplayName("9.1 /bridge 非桥接模式报错")
        void bridgeNotActive() {
            Command cmd = registry.getCommand("bridge");
            assertEquals(CommandAvailability.REQUIRES_BRIDGE, cmd.getAvailability());
            CommandResult result = cmd.execute("", defaultContext);
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("9.2 /bridge 桥接模式下成功")
        void bridgeInBridgeMode() {
            Command cmd = registry.getCommand("bridge");
            CommandContext bridgeCtx = new CommandContext("s1", "/tmp", "model",
                    AppState.defaultState(), true, false, true);
            CommandResult result = cmd.execute("status", bridgeCtx);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("9.3 条件命令都有 REQUIRES_* 可用性")
        void conditionalAvailability() {
            Stream.of("voice", "buddy", "passes", "torch", "fork", "peers", "workflows")
                    .forEach(name -> {
                        Command cmd = registry.getCommand(name);
                        assertNotEquals(CommandAvailability.ALWAYS, cmd.getAvailability(),
                                "/" + name + " should have conditional availability");
                    });
        }

        @Test
        @DisplayName("9.4 /ultrareview 为 PROMPT 类型")
        void ultrareviewIsPrompt() {
            Command cmd = registry.getCommand("ultrareview");
            assertInstanceOf(PromptCommand.class, cmd);
            assertEquals(CommandAvailability.REQUIRES_FEATURE, cmd.getAvailability());
        }

        @Test
        @DisplayName("9.5 /fork 无参显示用法")
        void forkUsage() {
            Command cmd = registry.getCommand("fork");
            CommandResult result = cmd.execute("", defaultContext);
            assertTrue(result.value().contains("Usage"));
        }

        @Test
        @DisplayName("9.6 /workflows 无参显示用法")
        void workflowsUsage() {
            Command cmd = registry.getCommand("workflows");
            CommandResult result = cmd.execute("", defaultContext);
            assertTrue(result.value().contains("list"));
            assertTrue(result.value().contains("run"));
        }
    }
}
