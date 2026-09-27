package com.aicodeassistant.tool;

import com.aicodeassistant.tool.impl.ToolSearchTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSearchAvailabilityTest {
    @ParameterizedTest
    @ValueSource(strings = {"select:Skill", "workflow", "+Skill", "select:skill-alias"})
    void everySearchBranchExcludesDisabledSchemaAndCanRecoverAfterEnable(String query) {
        AtomicBoolean enabled = new AtomicBoolean(false);
        Tool tool = new Tool() {
            @Override public String getName() { return "Skill"; }
            @Override public List<String> getAliases() { return List.of("skill-alias"); }
            @Override public String getDescription() { return "workflow DESCRIPTION_SENTINEL"; }
            @Override public String prompt() { return "SCHEMA_SENTINEL"; }
            @Override public Map<String, Object> getInputSchema() { return Map.of("type", "object"); }
            @Override public ToolResult call(ToolInput input, ToolUseContext context) { return ToolResult.success("ok"); }
            @Override public boolean shouldDefer() { return true; }
            @Override public boolean isEnabled() { return enabled.get(); }
        };
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        ToolSearchTool search = new ToolSearchTool(registry, new ObjectMapper());
        ToolUseContext context = ToolUseContext.of("/tmp", "skill-search-test");
        ToolInput input = ToolInput.from(Map.of("query", query));
        assertThat(search.call(input, context).content())
                .contains("No tools found").doesNotContain("DESCRIPTION_SENTINEL", "SCHEMA_SENTINEL");
        enabled.set(true);
        // Search while disabled must not activate a tool that later becomes enabled.
        assertThat(registry.getActiveToolDefinitions(context.sessionId())).isEmpty();
        assertThat(search.call(input, context).content()).contains("DESCRIPTION_SENTINEL", "SCHEMA_SENTINEL");
        assertThat(registry.getActiveToolDefinitions(context.sessionId())).hasSize(1);
        enabled.set(false);
        assertThat(registry.getActiveToolDefinitions(context.sessionId())).isEmpty();
        assertThat(search.call(input, context).content()).doesNotContain("DESCRIPTION_SENTINEL", "SCHEMA_SENTINEL");
    }
}
