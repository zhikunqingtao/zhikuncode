package com.aicodeassistant.config.database;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/** Replaces permission-only pending state with one durable interaction authority. */
@Component
@Order(15)
public class V015_AddInteractionSchema implements Migration {
    private static final String CHECKSUM =
            "a1840cc0d70cc360e635dd36c8bd7ee91f605a88919a3fb59cb2c463adf866c4";
    private final JdbcTemplate jdbc;
    public V015_AddInteractionSchema(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public String checksum() { return CHECKSUM; }

    @Override public void execute() {
        jdbc.execute("DROP TABLE IF EXISTS permission_requests");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS interaction_requests (
                  interaction_id TEXT PRIMARY KEY,
                  correlation_key TEXT NOT NULL,
                  session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                  run_id TEXT NOT NULL REFERENCES run_envelopes(id) ON DELETE CASCADE,
                  type TEXT NOT NULL CHECK(type IN ('permission','elicitation','plan_approval')),
                  status TEXT NOT NULL CHECK(status IN ('pending','answered','denied','expired','cancelled','undeliverable')),
                  prompt_json TEXT NOT NULL,
                  allowed_decisions_json TEXT NOT NULL,
                  scope_options_json TEXT NOT NULL,
                  response_json TEXT,
                  created_at TEXT NOT NULL,
                  delivery_window_ends_at TEXT NOT NULL,
                  first_dispatched_at TEXT,
                  delivery_ack_deadline_at TEXT,
                  received_at TEXT,
                  decision_deadline_at TEXT,
                  decided_at TEXT,
                  terminal_reason TEXT,
                  source TEXT NOT NULL,
                  child_session_id TEXT,
                  delivery_generation INTEGER NOT NULL DEFAULT 0 CHECK(delivery_generation >= 0),
                  dispatch_attempts INTEGER NOT NULL DEFAULT 0 CHECK(dispatch_attempts >= 0),
                  last_transport_id TEXT,
                  updated_at TEXT NOT NULL,
                  version INTEGER NOT NULL DEFAULT 0 CHECK(version >= 0),
                  UNIQUE(run_id, correlation_key)
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_interaction_session_status ON interaction_requests(session_id,status,created_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_interaction_run_status ON interaction_requests(run_id,status)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_interaction_delivery ON interaction_requests(status,delivery_window_ends_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_interaction_decision ON interaction_requests(status,decision_deadline_at)");
    }

    @Override public void validate() {
        Set<String> columns = jdbc.queryForList("PRAGMA table_info(interaction_requests)").stream()
                .map(r -> String.valueOf(r.get("name"))).collect(Collectors.toSet());
        if (!columns.containsAll(Set.of("interaction_id", "correlation_key", "decision_deadline_at",
                "delivery_generation", "version")))
            throw new IllegalStateException("V015 interaction schema incomplete");
        Integer old = jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='permission_requests'", Integer.class);
        if (old != null && old != 0) throw new IllegalStateException("Legacy permission_requests still exists");
    }
}
