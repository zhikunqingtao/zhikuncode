package com.aicodeassistant.tool.impl;

import com.aicodeassistant.history.FileHistoryService;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.security.PathSecurityService.PathCheckResult;
import com.aicodeassistant.service.FileStateCache;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.engine.KeyFileTracker;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * FileEditTool 5 策略 Fuzzy Matching 单元测试 — P0-6 改动验证。
 * <p>
 * 验证策略：
 * <ol>
 *   <li>Strategy 1 — 精确匹配</li>
 *   <li>Strategy 2 — 智能引号归一化（U+201C/D/8/9 → ASCII）</li>
 *   <li>Strategy 3 — 行尾空白归一化</li>
 *   <li>Strategy 4 — 换行符归一化（\r\n → \n）</li>
 *   <li>Strategy 5 — Tab/4 空格双向归一化</li>
 * </ol>
 */
@DisplayName("FileEditTool 5 策略 Fuzzy Matching 测试")
class FileEditToolFuzzyMatchTest {

    private FileEditTool fileEditTool;
    private ToolUseContext context;
    private FileStateCache fileStateCache;

    @Mock private PathSecurityService pathSecurityService;
    @Mock private FileHistoryService fileHistoryService;
    @Mock private SessionManager sessionManager;
    @Mock private KeyFileTracker keyFileTracker;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(pathSecurityService.checkWritePermission(anyString(), anyString()))
                .thenReturn(PathCheckResult.allowed());
        when(pathSecurityService.checkReadPermission(anyString(), anyString()))
                .thenReturn(PathCheckResult.allowed());
        when(pathSecurityService.resolvePath(anyString(), anyString()))
                .thenAnswer(inv -> {
                    String filePath = inv.getArgument(0);
                    String workDir = inv.getArgument(1);
                    Path p = Path.of(filePath);
                    return p.isAbsolute() ? p : Path.of(workDir).resolve(filePath);
                });

        fileStateCache = new FileStateCache() {
            @Override
            public synchronized boolean hasBeenRead(String path) { return true; }
            @Override
            public synchronized boolean isStale(String path) { return false; }
        };
        when(sessionManager.getFileStateCache(anyString())).thenReturn(fileStateCache);
        doNothing().when(fileHistoryService).trackEdit(anyString(), anyString(), any(), anyString());

        FileVersionTracker fileVersionTracker = new FileVersionTracker();
        AtomicFileWriter atomicFileWriter = new AtomicFileWriter(fileVersionTracker);
        fileEditTool = new FileEditTool(fileHistoryService, pathSecurityService,
                sessionManager, keyFileTracker, fileVersionTracker, atomicFileWriter);
        context = ToolUseContext.of(tempDir.toString(), "test-session");
    }

    private ToolResult invoke(Path file, String oldString, String newString) {
        ToolInput input = ToolInput.from(Map.of(
                "file_path", file.toString(),
                "old_string", oldString,
                "new_string", newString));
        return fileEditTool.call(input, context);
    }

    @Test
    @DisplayName("tc001: Strategy 1 - 精确匹配成功")
    void tc001_strategy1_exactMatch_succeeds() throws IOException {
        Path file = tempDir.resolve("exact.txt");
        Files.writeString(file, "hello world", StandardCharsets.UTF_8);

        ToolResult result = invoke(file, "hello", "HI");

        assertThat(result.isError()).isFalse();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("HI world");
    }

    @Test
    @DisplayName("tc002: Strategy 1 - 完全无关字符串触发后续策略后仍失败")
    void tc002_strategy1_miss_triggersFallbackAndFails() throws IOException {
        Path file = tempDir.resolve("miss.txt");
        Files.writeString(file, "alpha beta gamma", StandardCharsets.UTF_8);

        ToolResult result = invoke(file, "completely_absent_token", "X");

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("No match found");
    }

    @Test
    @DisplayName("tc003: Strategy 2 - 搜索串使用智能引号，文件用 ASCII 引号")
    void tc003_strategy2_smartQuoteSearch_normalizedToAscii() throws IOException {
        Path file = tempDir.resolve("quotes-ascii.txt");
        Files.writeString(file, "msg = \"hello\";", StandardCharsets.UTF_8);

        // 搜索串包含弯引号 \u201C \u201D
        ToolResult result = invoke(file, "msg = \u201Chello\u201D;", "msg = \"world\";");

        assertThat(result.isError()).isFalse();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("\"world\"");
    }

    @Test
    @DisplayName("tc004: Strategy 2 - 文件中含智能引号，搜索用 ASCII 引号")
    void tc004_strategy2_smartQuoteFile_searchAsciiHits() throws IOException {
        Path file = tempDir.resolve("quotes-smart.txt");
        // 文件内容含弯引号
        Files.writeString(file, "say \u201Chi\u201D loud", StandardCharsets.UTF_8);

        ToolResult result = invoke(file, "say \"hi\" loud", "say HELLO loud");

        // 该路径走 Strategy 2 中的 normalizedFile.contains 分支
        assertThat(result.isError()).isFalse();
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("HELLO");
    }

    @Test
    @DisplayName("tc005: Strategy 3 - 搜索串带尾部空白，文件无")
    void tc005_strategy3_trailingWhitespace_inSearchOnly() throws IOException {
        Path file = tempDir.resolve("trail.txt");
        Files.writeString(file, "line1\nline2\nline3\n", StandardCharsets.UTF_8);

        // 搜索串末尾带额外空格，原文件不带
        ToolResult result = invoke(file, "line2   ", "LINE_TWO");

        assertThat(result.isError()).isFalse();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("LINE_TWO");
    }

    @Test
    @DisplayName("tc006: Strategy 3 - 文件有尾部空格，搜索串无")
    void tc006_strategy3_trailingWhitespace_inFileOnly() throws IOException {
        Path file = tempDir.resolve("trail-file.txt");
        // 文件 line2 后有尾部空格
        Files.writeString(file, "line1\nline2   \nline3\n", StandardCharsets.UTF_8);

        ToolResult result = invoke(file, "line2", "LINE_TWO");

        // 精确匹配先命中（"line2" 是 "line2   " 的子串）
        assertThat(result.isError()).isFalse();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("LINE_TWO");
    }

    @Test
    @DisplayName("tc007: Strategy 4 - 搜索串使用 \\r\\n，文件使用 \\n")
    void tc007_strategy4_crlfSearch_lfFile_matches() throws IOException {
        Path file = tempDir.resolve("crlf.txt");
        Files.writeString(file, "alpha\nbeta\ngamma\n", StandardCharsets.UTF_8);

        // 搜索串带 \r\n，需归一化为 \n
        ToolResult result = invoke(file, "alpha\r\nbeta", "ALPHA\nBETA");

        assertThat(result.isError()).isFalse();
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("ALPHA\nBETA");
    }

    @Test
    @DisplayName("tc008: Strategy 4 - 搜索串无 \\r\\n 不触发该策略")
    void tc008_strategy4_noCrlf_notTriggered() throws IOException {
        Path file = tempDir.resolve("nocrlf.txt");
        Files.writeString(file, "alpha\nbeta\n", StandardCharsets.UTF_8);

        ToolResult result = invoke(file, "alpha\nbeta", "X\nY");

        // 精确匹配命中
        assertThat(result.isError()).isFalse();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("X\nY");
    }

    @Test
    @DisplayName("tc009: Strategy 5 - Tab 搜索匹配 4 空格文件")
    void tc009_strategy5_tabSearchMatchesSpacesFile() throws IOException {
        Path file = tempDir.resolve("tab2spaces.txt");
        // 文件用 4 空格缩进
        Files.writeString(file, "if (x):\n    return 1\n", StandardCharsets.UTF_8);

        // 搜索串用 Tab
        ToolResult result = invoke(file, "if (x):\n\treturn 1", "if (x):\n    return 42");

        assertThat(result.isError()).isFalse();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("return 42");
    }

    @Test
    @DisplayName("tc010: Strategy 5 - 4 空格搜索匹配 Tab 文件")
    void tc010_strategy5_spacesSearchMatchesTabFile() throws IOException {
        Path file = tempDir.resolve("spaces2tab.txt");
        // 文件用 Tab 缩进
        Files.writeString(file, "func {\n\treturn 1\n}", StandardCharsets.UTF_8);

        // 搜索串用 4 个空格
        ToolResult result = invoke(file, "func {\n    return 1", "func {\n\treturn 99");

        assertThat(result.isError()).isFalse();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("return 99");
    }

    @Test
    @DisplayName("tc011: 边界 - 文件不存在 + 空 old_string 视为创建文件")
    void tc011_boundary_emptyOldStringCreatesNewFile() {
        Path file = tempDir.resolve("created.txt");

        ToolResult result = invoke(file, "", "fresh content");

        assertThat(result.isError()).isFalse();
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    @DisplayName("tc012: 边界 - 大文件（1MB）匹配性能 < 5s")
    void tc012_boundary_largeFile_performant() throws IOException {
        Path file = tempDir.resolve("big.txt");
        StringBuilder sb = new StringBuilder(1_100_000);
        for (int i = 0; i < 100_000; i++) {
            sb.append("line").append(i).append("\n");
        }
        sb.append("UNIQUE_MARKER");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);

        long t0 = System.currentTimeMillis();
        ToolResult result = invoke(file, "UNIQUE_MARKER", "REPLACED");
        long elapsed = System.currentTimeMillis() - t0;

        assertThat(result.isError()).isFalse();
        assertThat(elapsed).isLessThan(5_000);
    }

    @Test
    @DisplayName("tc013: 全策略均失败 - 错误消息包含 5 策略列表")
    void tc013_allStrategiesFail_errorMentionsAllStrategies() throws IOException {
        Path file = tempDir.resolve("nomatch.txt");
        Files.writeString(file, "abc def ghi", StandardCharsets.UTF_8);

        ToolResult result = invoke(file, "totally-different-content", "X");

        assertThat(result.isError()).isTrue();
        String msg = result.content();
        assertThat(msg).contains("Attempted strategies");
        assertThat(msg).contains("exact");
        assertThat(msg).contains("quote-normalization");
        assertThat(msg).contains("trailing-whitespace");
        assertThat(msg).contains("newline-normalization");
        assertThat(msg).contains("tab/space-normalization");
    }
}
