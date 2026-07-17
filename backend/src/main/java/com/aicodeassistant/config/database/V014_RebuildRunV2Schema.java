package com.aicodeassistant.config.database;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/** Forward-only maintenance migration to the authoritative Run/Event V2 schema. */
@Component
@Order(14)
public class V014_RebuildRunV2Schema implements Migration {
    private static final String CHECKSUM =
            "707bff5b07e419a620f4dd19763480da988d815b1e071daf33bb8b317d3a5b55";
    private final JdbcTemplate jdbc;

    public V014_RebuildRunV2Schema(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override public String checksum() { return CHECKSUM; }

    @Override
    public void execute() {
        // V011 references run_envelopes. No-history migration still has to clear
        // the child rows before the run tables can be rebuilt with FK checks on.
        jdbc.execute("DELETE FROM permission_requests");
        jdbc.execute("DELETE FROM agent_checkpoints");
        jdbc.execute("DELETE FROM artifact_entries");
        jdbc.execute("DELETE FROM artifact_manifests");
        jdbc.execute("DROP TABLE IF EXISTS run_event_log");
        jdbc.execute("DROP TABLE IF EXISTS run_envelopes");
        jdbc.execute("""
                CREATE TABLE run_envelopes (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    parent_run_id TEXT REFERENCES run_envelopes(id),
                    status TEXT NOT NULL CHECK(status IN ('queued','running','waiting_interaction','cancelling','completed','failed','cancelled','interrupted')),
                    version INTEGER NOT NULL DEFAULT 0 CHECK(version >= 0),
                    agent_type TEXT,
                    model TEXT NOT NULL,
                    prompt_hash TEXT,
                    started_at TEXT NOT NULL,
                    finished_at TEXT,
                    terminal_at TEXT,
                    exit_reason TEXT,
                    requested_exit_reason TEXT,
                    verification_status TEXT NOT NULL DEFAULT 'not_requested'
                        CHECK(verification_status IN ('not_requested','pending','verified','unverified','failed')),
                    waiting_reason TEXT,
                    abort_reason TEXT,
                    total_tokens INTEGER NOT NULL DEFAULT 0,
                    total_cost_usd REAL NOT NULL DEFAULT 0.0,
                    tool_call_count INTEGER NOT NULL DEFAULT 0,
                    turn_count INTEGER NOT NULL DEFAULT 0,
                    error_summary TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    CHECK((status IN ('completed','failed','cancelled','interrupted') AND terminal_at IS NOT NULL)
                       OR (status NOT IN ('completed','failed','cancelled','interrupted') AND terminal_at IS NULL))
                )
                """);
        jdbc.execute("CREATE INDEX idx_run_envelopes_session ON run_envelopes(session_id, started_at DESC)");
        jdbc.execute("CREATE INDEX idx_run_envelopes_parent ON run_envelopes(parent_run_id)");
        jdbc.execute("CREATE INDEX idx_run_envelopes_status ON run_envelopes(status)");
        jdbc.execute("""
                CREATE TABLE run_event_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    run_id TEXT NOT NULL REFERENCES run_envelopes(id) ON DELETE CASCADE,
                    seq INTEGER NOT NULL,
                    event_type TEXT NOT NULL,
                    event_data TEXT NOT NULL,
                    ts INTEGER NOT NULL,
                    UNIQUE(run_id, seq)
                )
                """);
        jdbc.execute("CREATE INDEX idx_run_events_run_seq ON run_event_log(run_id, seq)");
        jdbc.execute("CREATE INDEX idx_run_events_type ON run_event_log(event_type)");
    }

    @Override
    public void validate() {
        Set<String> columns = jdbc.queryForList("PRAGMA table_info(run_envelopes)").stream()
                .map(row -> String.valueOf(row.get("name"))).collect(Collectors.toSet());
        Set<String> required = Set.of("version", "exit_reason", "requested_exit_reason",
                "verification_status", "terminal_at", "waiting_reason");
        if (!columns.containsAll(required)) {
            throw new IllegalStateException("V014 Run V2 columns missing: " + required);
        }
        String ddl = jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='run_event_log'", String.class);
        if (ddl == null || !ddl.toLowerCase().contains("unique(run_id, seq)")) {
            throw new IllegalStateException("V014 run_event_log unique sequence constraint missing");
        }
        Integer fk = jdbc.queryForObject("PRAGMA foreign_keys", Integer.class);
        if (fk == null || fk != 1) throw new IllegalStateException("SQLite foreign keys are disabled");
    }
}
