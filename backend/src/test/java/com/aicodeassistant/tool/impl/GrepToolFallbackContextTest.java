package com.aicodeassistant.tool.impl;

import com.aicodeassistant.engine.KeyFileTracker;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.tool.ToolInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** System-grep fallback must not emit "--" group separators that paging would count as results. */
class GrepToolFallbackContextTest {

    private final GrepTool grep = new GrepTool(mock(KeyFileTracker.class), new PathSecurityService());

    private List<String> contextArgs(Map<String, Object> params) {
        List<String> args = grep.buildGrepFallbackArgs(
                ToolInput.from(params), "MATCH", "/workspace", "content", false);
        List<String> context = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("-C") || a.equals("-B") || a.equals("-A")) {
                context.add(a);
                context.add(args.get(i + 1));
            }
        }
        return context;
    }

    @Test
    void zeroOrAbsentContextPassesNoContextFlags() {
        assertThat(contextArgs(Map.of())).isEmpty();
        assertThat(contextArgs(Map.of("-C", 0))).isEmpty();
        assertThat(contextArgs(Map.of("-A", 0, "-B", 0, "-C", 0))).isEmpty();
        assertThat(contextArgs(Map.of("-C", 2, "-A", 0, "-B", 0))).isEmpty();
    }

    @Test
    void nonZeroContextKeepsUserFlagsAndOverrideSemantics() {
        assertThat(contextArgs(Map.of("-C", 1))).containsExactly("-C", "1");
        assertThat(contextArgs(Map.of("-A", 2))).containsExactly("-A", "2");
        // -B 0 overrides only the before side; after side still has context from -C 2.
        assertThat(contextArgs(Map.of("-C", 2, "-B", 0))).containsExactly("-C", "2", "-B", "0");
        assertThat(contextArgs(Map.of("-C", 0, "-A", 1))).containsExactly("-C", "0", "-A", "1");
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void realSystemGrepWithZeroContextReturnsOnlyMatchLines(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("s.txt"), "MATCH-A\nx\nx\nMATCH-B\nx\nMATCH-C\n");
        List<String> args = grep.buildGrepFallbackArgs(
                ToolInput.from(Map.of("-A", 0, "-B", 0, "-C", 0)), "MATCH", ".", "content", false);
        Process p = new ProcessBuilder(args).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();

        assertThat(out.lines().toList()).hasSize(3).noneMatch("--"::equals)
                .allMatch(line -> line.contains("MATCH-"));
    }
}
