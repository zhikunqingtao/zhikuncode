package com.aicodeassistant.workbench;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 在 Root Run 启动和形成最终回复时固化消息关联。 */
@Service
@DependsOn("migrationRunner")
public class WorkbenchRunLinkService {
    private static final Logger log = LoggerFactory.getLogger(WorkbenchRunLinkService.class);

    private final JdbcTemplate jdbc;
    private final SqliteConfig sqlite;
    private final Path dbPath;
    private final TransactionTemplate transaction;
    private final AcceptanceCriteriaExtractor criteriaExtractor;

    public WorkbenchRunLinkService(
            @Qualifier("projectJdbcTemplate") JdbcTemplate jdbc,
            SqliteConfig sqlite,
            DatabaseResolver resolver,
            @Qualifier("projectTransactionManager") PlatformTransactionManager txManager,
            AcceptanceCriteriaExtractor criteriaExtractor) {
        this.jdbc = jdbc;
        this.sqlite = sqlite;
        this.dbPath = resolver.getProjectDbPath(Path.of(System.getProperty("user.dir")));
        this.transaction = new TransactionTemplate(txManager);
        this.criteriaExtractor = criteriaExtractor;
    }

    public void bindRequest(String rootRunId, Message.UserMessage request) {
        if (rootRunId == null || request == null || request.uuid() == null) return;
        try {
            String requestText = text(request.content());
            write(() -> {
                Integer valid = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM run_envelopes r
                        JOIN messages m ON m.id = ? AND m.session_id = r.session_id AND m.role = 'user'
                        WHERE r.id = ? AND r.parent_run_id IS NULL
                        """, Integer.class, request.uuid(), rootRunId);
                if (valid == null || valid != 1) return null;
                String now = Instant.now().toString();
                int inserted = jdbc.update("""
                        INSERT INTO run_workbench_bindings(
                          root_run_id,request_message_id,result_message_id,created_at,updated_at)
                        VALUES(?,?,NULL,?,?)
                        ON CONFLICT(root_run_id) DO NOTHING
                        """, rootRunId, request.uuid(), now, now);
                if (inserted == 0) {
                    String existing = jdbc.queryForObject("""
                            SELECT request_message_id FROM run_workbench_bindings
                            WHERE root_run_id=?
                            """, String.class, rootRunId);
                    if (!request.uuid().equals(existing)) {
                        log.warn("Workbench request binding conflict ignored: rootRunId={}, existingMessageId={}, attemptedMessageId={}",
                                rootRunId, existing, request.uuid());
                    }
                    return null;
                }
                List<String> criteria = criteriaExtractor.extract(requestText);
                for (int index = 0; index < criteria.size(); index++) {
                    jdbc.update("""
                            INSERT INTO run_acceptance_criteria(
                              criterion_id,root_run_id,ordinal,criterion_type,source_text,status,
                              evidence_bundle_id,created_at,updated_at)
                            VALUES(?,?,?,'business',?,'not_verified',NULL,?,?)
                            """, UUID.randomUUID().toString(), rootRunId, index,
                            criteria.get(index), now, now);
                }
                return null;
            });
        } catch (RuntimeException projectionFailure) {
            log.warn("Workbench request projection failed; root run continues: rootRunId={}",
                    rootRunId, projectionFailure);
        }
    }

    public void bindFinalResult(String rootRunId, Message.AssistantMessage result) {
        if (rootRunId == null || result == null || result.uuid() == null
                || !"end_turn".equals(result.stopReason())
                || text(result.content()).isBlank()
                || result.content().stream().anyMatch(ContentBlock.ToolUseBlock.class::isInstance)) {
            return;
        }
        try {
            write(() -> {
                int updated = jdbc.update("""
                        UPDATE run_workbench_bindings SET result_message_id=?,updated_at=?
                        WHERE root_run_id=? AND result_message_id IS NULL AND EXISTS(
                          SELECT 1 FROM messages m JOIN run_envelopes r ON r.id=?
                          WHERE m.id=? AND m.session_id=r.session_id AND m.role='assistant'
                        )
                        """, result.uuid(), Instant.now().toString(), rootRunId,
                        rootRunId, result.uuid());
                if (updated == 0) {
                    List<String> existing = jdbc.queryForList("""
                            SELECT result_message_id FROM run_workbench_bindings
                            WHERE root_run_id=? AND result_message_id IS NOT NULL
                            """, String.class, rootRunId);
                    if (!existing.isEmpty() && !result.uuid().equals(existing.getFirst())) {
                        log.warn("Workbench result binding conflict ignored: rootRunId={}, existingMessageId={}, attemptedMessageId={}",
                                rootRunId, existing.getFirst(), result.uuid());
                    }
                }
                return updated;
            });
        } catch (RuntimeException projectionFailure) {
            log.warn("Workbench result projection failed; root run continues: rootRunId={}",
                    rootRunId, projectionFailure);
        }
    }

    public static String text(List<ContentBlock> blocks) {
        if (blocks == null) return "";
        return blocks.stream().filter(ContentBlock.TextBlock.class::isInstance)
                .map(ContentBlock.TextBlock.class::cast)
                .map(ContentBlock.TextBlock::text)
                .filter(java.util.Objects::nonNull)
                .reduce((left, right) -> left + "\n" + right).orElse("").strip();
    }

    private <T> T write(java.util.function.Supplier<T> operation) {
        return sqlite.executeWrite(dbPath,
                () -> transaction.execute(status -> operation.get()));
    }
}
