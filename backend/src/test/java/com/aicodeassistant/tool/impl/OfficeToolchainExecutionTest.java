package com.aicodeassistant.tool.impl;

import com.aicodeassistant.sandbox.SandboxManager;
import com.aicodeassistant.security.CommandBlacklistService;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.bash.BashCommandClassifier;
import com.aicodeassistant.tool.bash.BashErrorClassifier;
import com.aicodeassistant.tool.bash.BashOutputProcessor;
import com.aicodeassistant.tool.bash.BashSecurityAnalyzer;
import com.aicodeassistant.tool.bash.ShellStateManager;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * R-12: 通过真实 {@link BashTool} → {@link ManagedProcessRunner} 链路执行离线回归工具链脚本。
 *
 * <p>本测试只在宿主机上真实启动进程（bash -c → python3）来执行
 * {@code tools/office-regression/scripts/} 下的确定性 stdlib 脚本：
 * <ul>
 *   <li>{@code office_fixture_gen.py} 生成 xlsx 正例与反例夹具到 JUnit 临时目录；</li>
 *   <li>{@code office_struct_check.py structure} 检查正例 → exit 0；</li>
 *   <li>检查反例 → exit 2 且输出命中指定 reason code（XLSX_MISSING_FORMULA）。</li>
 * </ul>
 * 不依赖 LibreOffice；LibreOffice 实算场景只在容器套件（tools/office-regression/tests）中覆盖。</p>
 *
 * <p>进程执行链保持真实：真实 {@code BashTool.call()} → 真实 {@code ManagedProcessRunner}
 * → 真实 OS 进程。安全/沙箱/黑名单/Shell 状态协作者使用 mock，装配方式与
 * {@link BashToolFailureClassificationTest} 相同（避免触碰真实安全策略、docker 与 shell 状态目录）。</p>
 *
 * <p>环境不满足时明确跳过（skip ≠ 通过）：找不到仓库根（同时含 {@code backend/pom.xml} 与
 * 生成脚本的目录），或宿主机没有可用的 Python（先 python3、再 python，要求 ≥ 3.9）。</p>
 */
class OfficeToolchainExecutionTest {

    private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(30);
    private static final String FIXTURE_GEN_SCRIPT = "tools/office-regression/scripts/office_fixture_gen.py";
    private static final String STRUCT_CHECK_SCRIPT = "tools/office-regression/scripts/office_struct_check.py";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("office_fixture_gen.py 经真实 BashTool 生成 xlsx 正例与反例夹具")
    void fixtureGeneratorProducesXlsxFixturesThroughRealBashTool() {
        Path repoRoot = requireRepoRoot();
        String python = requirePython();
        BashTool bashTool = newBashTool();

        Path outputDirectory = tempDirectory.resolve("fixtures");
        ToolResult result = execute(bashTool, repoRoot,
                python + " " + shellQuote(repoRoot.resolve(FIXTURE_GEN_SCRIPT)) + " --out " + shellQuote(outputDirectory));

        assertThat(result.isError())
                .as("fixture generation must succeed, exit=%s, content=%s", result.exitCode(), result.content())
                .isFalse();
        assertThat(result.exitCode()).isZero();
        assertThat(result.content()).contains("fixtures written to");
        assertThat(outputDirectory.resolve("xlsx/positive.xlsx")).isRegularFile();
        assertThat(outputDirectory.resolve("xlsx/negative-missing-formula.xlsx")).isRegularFile();
        assertThat(outputDirectory.resolve("specs/xlsx.json")).isRegularFile();
    }

    @Test
    @DisplayName("office_struct_check.py structure 检查正例 xlsx → exit 0")
    void structureCheckAcceptsGeneratedPositiveXlsx() {
        Path repoRoot = requireRepoRoot();
        String python = requirePython();
        BashTool bashTool = newBashTool();
        Path fixturesDirectory = generateFixtures(bashTool, repoRoot, python);

        ToolResult result = structureCheck(bashTool, repoRoot, python, fixturesDirectory, "positive.xlsx");

        assertThat(result.isError())
                .as("positive fixture must pass the structure check, exit=%s, content=%s",
                        result.exitCode(), result.content())
                .isFalse();
        assertThat(result.exitCode()).isZero();
        assertThat(result.content()).contains("\"ok\": true");
    }

    @Test
    @DisplayName("office_struct_check.py structure 检查反例 xlsx → exit 2 且命中 XLSX_MISSING_FORMULA")
    void structureCheckFlagsNegativeXlsxWithReasonCode() {
        Path repoRoot = requireRepoRoot();
        String python = requirePython();
        BashTool bashTool = newBashTool();
        Path fixturesDirectory = generateFixtures(bashTool, repoRoot, python);

        ToolResult result = structureCheck(bashTool, repoRoot, python, fixturesDirectory,
                "negative-missing-formula.xlsx");

        assertThat(result.isError())
                .as("negative fixture must be rejected with exit 2, content=%s", result.content())
                .isTrue();
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.content()).contains("\"ok\": false", "XLSX_MISSING_FORMULA");
    }

    // ──────────────────────────────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────────────────────────────

    private Path generateFixtures(BashTool bashTool, Path repoRoot, String python) {
        Path outputDirectory = tempDirectory.resolve("fixtures");
        ToolResult result = execute(bashTool, repoRoot,
                python + " " + shellQuote(repoRoot.resolve(FIXTURE_GEN_SCRIPT)) + " --out " + shellQuote(outputDirectory));
        assertThat(result.isError())
                .as("fixture generation must succeed, exit=%s, content=%s", result.exitCode(), result.content())
                .isFalse();
        return outputDirectory;
    }

    private ToolResult structureCheck(BashTool bashTool, Path repoRoot, String python,
                                      Path fixturesDirectory, String fixtureName) {
        String command = python + " " + shellQuote(repoRoot.resolve(STRUCT_CHECK_SCRIPT))
                + " structure --kind xlsx"
                + " --file " + shellQuote(fixturesDirectory.resolve("xlsx").resolve(fixtureName))
                + " --spec " + shellQuote(fixturesDirectory.resolve("specs/xlsx.json"));
        return execute(bashTool, repoRoot, command);
    }

    private ToolResult execute(BashTool bashTool, Path workingDirectory, String command) {
        int sequence = SEQUENCE.incrementAndGet();
        ToolUseContext context = ToolUseContext.of(workingDirectory.toString(), "office-toolchain-session")
                .withCurrentRunId("office-toolchain-run-" + sequence)
                .withToolUseId("office-toolchain-tool-" + sequence);
        ToolInput input = ToolInput.from(Map.of(
                "command", command,
                "timeout", (int) EXECUTION_TIMEOUT.toMillis(),
                "description", "R-12 office-regression toolchain script"));
        return bashTool.call(input, context);
    }

    /**
     * 与 {@link BashToolFailureClassificationTest} 相同的轻量装配：安全分析、Shell 状态、
     * 沙箱与黑名单协作者使用 mock；进程执行链（ManagedProcessRunner → OwnedProcess）使用真实实现。
     */
    private BashTool newBashTool() {
        BashSecurityAnalyzer securityAnalyzer = mock(BashSecurityAnalyzer.class);
        ShellStateManager shellStateManager = mock(ShellStateManager.class);
        SandboxManager sandboxManager = mock(SandboxManager.class);
        CommandBlacklistService blacklist = mock(CommandBlacklistService.class);

        lenient().when(blacklist.checkCommand(anyString()))
                .thenReturn(new CommandBlacklistService.BlockResult(
                        CommandBlacklistService.BlockLevel.ALLOWED, null, null));
        lenient().when(shellStateManager.wrapCommand(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(shellStateManager.resolveWorkingDirectory(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        lenient().when(sandboxManager.isSandboxingEnabled()).thenReturn(false);

        return new BashTool(securityAnalyzer, new BashCommandClassifier(), shellStateManager,
                new BashOutputProcessor(), sandboxManager, blacklist, new BashErrorClassifier(),
                new ManagedProcessRunner());
    }

    /**
     * 从 {@code user.dir} 向上寻找同时包含 {@code backend/pom.xml} 与生成脚本的仓库根；
     * 找不到时 assumption 跳过（skip ≠ 通过）。
     */
    private static Path requireRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("backend/pom.xml"))
                    && Files.isRegularFile(candidate.resolve(FIXTURE_GEN_SCRIPT))) {
                return candidate;
            }
        }
        Assumptions.assumeTrue(false,
                "Repository root (backend/pom.xml + " + FIXTURE_GEN_SCRIPT + ") not found above user.dir=" + current);
        return null; // unreachable
    }

    /**
     * 宿主机 Python 探测：先 python3、再 python，要求 Python ≥ 3.9（脚本声明的兼容下限）。
     * 均不可用时 assumption 跳过。
     */
    private static String requirePython() {
        for (String candidate : List.of("python3", "python")) {
            if (isUsablePython(candidate)) {
                return candidate;
            }
        }
        Assumptions.assumeTrue(false, "Host has no usable Python (>=3.9) on PATH; tried python3 and python");
        return null; // unreachable
    }

    private static boolean isUsablePython(String command) {
        try {
            Process probe = new ProcessBuilder(command, "-c",
                    "import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)")
                    .redirectErrorStream(true)
                    .start();
            try {
                if (probe.waitFor(10, TimeUnit.SECONDS)) {
                    return probe.exitValue() == 0;
                }
                return false;
            } finally {
                probe.destroyForcibly();
            }
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String shellQuote(Path path) {
        return "'" + path.toString().replace("'", "'\\''") + "'";
    }
}