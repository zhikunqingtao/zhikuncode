package com.aicodeassistant.tool.impl;

import com.aicodeassistant.engine.KeyFileTracker;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Real rg/grep and files; invalid inputs also exercise call() without model-schema validation. */
@EnabledOnOs({OS.MAC, OS.LINUX})
class GrepToolPagingTest {
    @TempDir Path workspace;

    private GrepTool tool;
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        workspace = workspace.toRealPath();
        file = workspace.resolve("sample.txt");
        Files.writeString(file, "before\nMATCH-A\nbetween\nMATCH-B\nafter\nMATCH-C\ntail\n");
        tool = new GrepTool(mock(KeyFileTracker.class), new PathSecurityService());
    }

    @Test
    void positiveLimitAndOffsetReturnTheRequestedPage() {
        ToolResult second = search(Map.of("head_limit", 1, "offset", 1));
        assertThat(second.content()).isEqualTo(file + ":4:MATCH-B\n[Results truncated]");
        assertThat(second.metadata()).containsEntry("truncated", true);

        ToolResult last = search(Map.of("head_limit", 1, "offset", 2));
        assertThat(last.content()).isEqualTo(file + ":6:MATCH-C");
        assertThat(last.metadata()).containsEntry("truncated", false);
    }

    @Test
    void contextAndPagingCountOutputLinesNotOnlyMatches() {
        ToolResult context = search(Map.of("head_limit", 20, "-C", 1));
        assertThat(context.content()).isEqualTo(String.join("\n",
                file + "-1-before", file + ":2:MATCH-A", file + "-3-between",
                file + ":4:MATCH-B", file + "-5-after", file + ":6:MATCH-C", file + "-7-tail"));

        ToolResult page = search(Map.of("head_limit", 2, "offset", 1, "-C", 1));
        assertThat(page.content()).isEqualTo(file + ":2:MATCH-A\n" + file + "-3-between\n[Results truncated]");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nonPositiveLimitDisablesPagingAndIgnoresOffset(int limit) {
        ToolResult result = search(Map.of("head_limit", limit, "offset", 2));
        assertThat(result.content()).isEqualTo(String.join("\n",
                file + ":2:MATCH-A", file + ":4:MATCH-B", file + ":6:MATCH-C"));
        assertThat(result.metadata()).containsEntry("truncated", false);
    }

    @Test
    void disablingPagingStillRetainsTheOutputCharacterLimit() throws Exception {
        Files.writeString(file, ("MATCH-" + "x".repeat(190) + "\n").repeat(200));
        ToolResult result = search(Map.of("head_limit", 0, "offset", 100));

        assertThat(result.content()).startsWith(file + ":1:MATCH-").endsWith("\n[Results truncated]");
        assertThat(result.metadata()).containsEntry("truncated", true);
        assertThat(result.content()).hasSize(20_000 + "\n[Results truncated]".length());
    }

    @Test
    void defaultPagingAndZeroContextKeepAllMatches() {
        ToolResult result = search(Map.of("-A", 0, "-B", 0, "-C", 0));
        assertThat(result.content()).isEqualTo(allMatches());
    }

    @Test
    void representableIntegerStringsRetainRuntimeCompatibility() {
        ToolResult result = call(tool, Map.of("head_limit", "1", "offset", "+1", "-C", "0"));

        assertThat(result.isError()).as(result.content()).isFalse();
        assertThat(result.content()).isEqualTo(file + ":4:MATCH-B\n[Results truncated]");
    }

    @Test
    void exactReadCountBoundaryDoesNotIntroduceASmallerPagingLimit() {
        assertThat(search(Map.of("head_limit", Integer.MAX_VALUE - 1)).content()).isEqualTo(allMatches());
        assertThat(search(Map.of("head_limit", 1, "offset", Integer.MAX_VALUE - 2)).content()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void disabledPagingDoesNotValidateAnUnusedOffset(int headLimit) {
        for (Object offset : new Object[]{-1, 4_294_967_296L, "ignored"}) {
            ToolResult result = call(tool, Map.of("head_limit", headLimit, "offset", offset));
            assertThat(result.isError()).as(result.content()).isFalse();
            assertThat(result.content()).isEqualTo(allMatches());
        }
    }

    @Test
    void nonContentModeStillIgnoresUnusedContextValues() {
        ToolResult result = call(tool, Map.of("output_mode", "files_with_matches", "-C", "ignored"));

        assertThat(result.isError()).as(result.content()).isFalse();
        assertThat(result.content()).isEqualTo(file.toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidNumericInputs")
    void invalidNumbersReturnValidationBeforePathAccessOrSearchStarts(
            String description, Map<String, Object> options, String offendingKey) {
        PathSecurityService untouchedPaths = mock(PathSecurityService.class);
        KeyFileTracker untouchedTracker = mock(KeyFileTracker.class);
        GrepTool validatingTool = new GrepTool(untouchedTracker, untouchedPaths);

        // Grep does not provide getSchema(); enforcement must occur in the real tool call.
        ToolResult result = call(validatingTool, options);

        assertThat(result.isError()).isTrue();
        assertThat(result.failureCode()).isEqualTo("GREP_ARGUMENT_INVALID");
        assertThat(result.failureType()).isEqualTo(ToolResult.ToolFailureType.VALIDATION);
        assertThat(result.effectState()).isEqualTo(ToolResult.EffectState.NOT_STARTED);
        assertThat(result.content()).contains(offendingKey);
        verifyNoInteractions(untouchedPaths, untouchedTracker);
    }

    private static Stream<Arguments> invalidNumericInputs() {
        return Stream.of(
                Arguments.of("negative offset", Map.of("head_limit", 1, "offset", -1), "offset"),
                Arguments.of("head addition overflow", Map.of("head_limit", Integer.MAX_VALUE), "offset + head_limit"),
                Arguments.of("offset addition overflow", Map.of("head_limit", 1, "offset", Integer.MAX_VALUE), "offset + head_limit"),
                Arguments.of("head Number narrowing", Map.of("head_limit", 4_294_967_296L), "head_limit"),
                Arguments.of("offset Number narrowing", Map.of("offset", 4_294_967_296L), "offset"),
                Arguments.of("context Number narrowing", Map.of("-C", 4_294_967_296L), "-C"),
                Arguments.of("fractional head", Map.of("head_limit", 1.5), "head_limit"),
                Arguments.of("fractional offset", Map.of("offset", 0.5), "offset"),
                Arguments.of("fractional context", Map.of("-A", 0.5), "-A"),
                Arguments.of("head string overflow", Map.of("head_limit", "2147483648"), "head_limit"),
                Arguments.of("invalid offset string", Map.of("offset", "not-an-integer"), "offset"),
                Arguments.of("invalid context type", Map.of("-B", true), "-B"),
                Arguments.of("negative after context", Map.of("-A", -1), "-A"),
                Arguments.of("negative before context", Map.of("-B", -1), "-B"),
                Arguments.of("negative surrounding context", Map.of("-C", -1), "-C"));
    }

    private ToolResult search(Map<String, Object> options) {
        var mapper = new ObjectMapper();
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
                .getSchema(mapper.valueToTree(tool.getInputSchema()));
        assertThat(schema.validate(mapper.valueToTree(input(options)))).isEmpty();
        ToolResult result = call(tool, options);
        assertThat(result.isError()).as(result.content()).isFalse();
        return result;
    }

    private ToolResult call(GrepTool target, Map<String, Object> options) {
        return target.call(ToolInput.from(input(options)), ToolUseContext.of(workspace.toString(), "grep-fixture"));
    }

    private Map<String, Object> input(Map<String, Object> options) {
        Map<String, Object> input = new HashMap<>();
        input.put("pattern", "MATCH");
        input.put("path", workspace.toString());
        input.put("output_mode", "content");
        input.putAll(options);
        return input;
    }

    private String allMatches() {
        return String.join("\n", file + ":2:MATCH-A", file + ":4:MATCH-B", file + ":6:MATCH-C");
    }
}
