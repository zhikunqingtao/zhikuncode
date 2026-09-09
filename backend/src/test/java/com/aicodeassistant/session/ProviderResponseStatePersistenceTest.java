package com.aicodeassistant.session;

import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.run.RunExecutionRegistry;
import com.aicodeassistant.run.RunTerminationCoordinator;
import com.aicodeassistant.state.AppStateStore;
import com.aicodeassistant.tool.agent.BackgroundAgentTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProviderResponseStatePersistenceTest {

    @Test
    void contentJsonRoundTripPreservesOpaqueOutputOrderAndFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SessionManager manager = new SessionManager(
                mock(JdbcTemplate.class), mapper, mock(SqliteConfig.class),
                mock(AppStateStore.class), mock(HookService.class),
                mock(SessionSnapshotService.class), mock(BackgroundAgentTracker.class),
                mock(RunExecutionRegistry.class), mock(RunTerminationCoordinator.class));
        var first = mapper.readTree("""
                {"type":"reasoning","encrypted_content":"cipher","summary":[]}
                """);
        var second = mapper.readTree("""
                {"type":"function_call","call_id":"c1","name":"Read","arguments":"{}"}
                """);
        var state = new ContentBlock.ProviderResponseStateBlock(
                "zenmux", "openai/gpt-6-astra", "summary",
                List.of(first, second));

        String json = ReflectionTestUtils.invokeMethod(
                manager, "toJsonString", List.of(state));
        List<ContentBlock> restored = ReflectionTestUtils.invokeMethod(
                manager, "parseContentBlocks", json);

        assertThat(restored).singleElement()
                .isInstanceOf(ContentBlock.ProviderResponseStateBlock.class);
        ContentBlock.ProviderResponseStateBlock roundTrip =
                (ContentBlock.ProviderResponseStateBlock) restored.getFirst();
        assertThat(roundTrip.provider()).isEqualTo("zenmux");
        assertThat(roundTrip.model()).isEqualTo("openai/gpt-6-astra");
        assertThat(roundTrip.displayThinking()).isEqualTo("summary");
        assertThat(roundTrip.outputItems()).containsExactly(first, second);
        assertThat(roundTrip.outputItems().getFirst().path("encrypted_content").asText())
                .isEqualTo("cipher");
    }
}
