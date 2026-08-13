package com.aicodeassistant.workbench;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.Usage;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkbenchRunLinkServiceTest {

    @Test
    void repeatedAndConflictingBindingsNeverReplaceTheFirstAssociation() throws Exception {
        var dataSource = new SingleConnectionDataSource(
                DriverManager.getConnection("jdbc:sqlite::memory:"), true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE run_envelopes(id TEXT PRIMARY KEY, session_id TEXT, parent_run_id TEXT)");
        jdbc.execute("CREATE TABLE messages(id TEXT PRIMARY KEY, session_id TEXT, role TEXT)");
        jdbc.execute("CREATE TABLE run_workbench_bindings(root_run_id TEXT PRIMARY KEY, request_message_id TEXT, result_message_id TEXT, created_at TEXT, updated_at TEXT)");
        jdbc.execute("CREATE TABLE run_acceptance_criteria(criterion_id TEXT PRIMARY KEY, root_run_id TEXT, ordinal INTEGER, criterion_type TEXT, source_text TEXT, status TEXT, evidence_bundle_id TEXT, created_at TEXT, updated_at TEXT)");
        jdbc.update("INSERT INTO run_envelopes VALUES (?,?,NULL)", "root-1", "session-1");
        jdbc.update("INSERT INTO messages VALUES (?,?,?)", "request-1", "session-1", "user");
        jdbc.update("INSERT INTO messages VALUES (?,?,?)", "request-2", "session-1", "user");
        jdbc.update("INSERT INTO messages VALUES (?,?,?)", "result-1", "session-1", "assistant");
        jdbc.update("INSERT INTO messages VALUES (?,?,?)", "result-2", "session-1", "assistant");

        SqliteConfig sqlite = mock(SqliteConfig.class);
        doAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get())
                .when(sqlite).executeWrite(any(Path.class), any());
        DatabaseResolver resolver = mock(DatabaseResolver.class);
        when(resolver.getProjectDbPath(any(Path.class))).thenReturn(Path.of("projection-test.db"));
        AcceptanceCriteriaExtractor extractor = mock(AcceptanceCriteriaExtractor.class);
        when(extractor.extract(any())).thenReturn(List.of("must finish"));
        WorkbenchRunLinkService service = new WorkbenchRunLinkService(
                jdbc, sqlite, resolver, new DataSourceTransactionManager(dataSource), extractor);

        service.bindRequest("root-1", user("request-1"));
        service.bindFinalResult("root-1", assistant("result-1"));
        String originalCriterion = jdbc.queryForObject(
                "SELECT criterion_id FROM run_acceptance_criteria", String.class);
        service.bindRequest("root-1", user("request-1"));
        service.bindRequest("root-1", user("request-2"));
        service.bindFinalResult("root-1", assistant("result-1"));
        service.bindFinalResult("root-1", assistant("result-2"));

        var binding = jdbc.queryForMap("SELECT * FROM run_workbench_bindings WHERE root_run_id='root-1'");
        assertThat(binding.get("request_message_id")).isEqualTo("request-1");
        assertThat(binding.get("result_message_id")).isEqualTo("result-1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_acceptance_criteria", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT criterion_id FROM run_acceptance_criteria", String.class))
                .isEqualTo(originalCriterion);
        dataSource.destroy();
    }

    @Test
    void projectionWriteFailuresNeverEscapeIntoRootRunExecution() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SqliteConfig sqlite = mock(SqliteConfig.class);
        DatabaseResolver resolver = mock(DatabaseResolver.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        AcceptanceCriteriaExtractor criteria = mock(AcceptanceCriteriaExtractor.class);
        when(resolver.getProjectDbPath(any(Path.class))).thenReturn(Path.of("projection-test.db"));
        when(sqlite.executeWrite(any(Path.class), any())).thenThrow(new RuntimeException("database busy"));

        WorkbenchRunLinkService service = new WorkbenchRunLinkService(
                jdbc, sqlite, resolver, transactions, criteria);
        Message.UserMessage request = new Message.UserMessage(
                "request-1", Instant.now(), List.of(new ContentBlock.TextBlock("完成任务")), null, null);
        Message.AssistantMessage result = new Message.AssistantMessage(
                "result-1", Instant.now(), List.of(new ContentBlock.TextBlock("任务完成")),
                "end_turn", new Usage(1, 1, 0, 0));

        assertThatCode(() -> service.bindRequest("root-run-1", request)).doesNotThrowAnyException();
        assertThatCode(() -> service.bindFinalResult("root-run-1", result)).doesNotThrowAnyException();
    }

    private static Message.UserMessage user(String id) {
        return new Message.UserMessage(id, Instant.now(),
                List.of(new ContentBlock.TextBlock("完成任务")), null, null);
    }

    private static Message.AssistantMessage assistant(String id) {
        return new Message.AssistantMessage(id, Instant.now(),
                List.of(new ContentBlock.TextBlock("任务完成")),
                "end_turn", new Usage(1, 1, 0, 0));
    }
}
