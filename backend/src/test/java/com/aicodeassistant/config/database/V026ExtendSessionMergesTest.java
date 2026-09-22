package com.aicodeassistant.config.database;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class V026ExtendSessionMergesTest {
    @TempDir Path temp;
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    V026_ExtendSessionMerges migration;
    @BeforeEach void setup() {
        var source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:"+temp.resolve("migration.db"));
        source.setEnforceForeignKeys(true);
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        new V024_CreateSessionMerges(jdbc).execute();
        migration = new V026_ExtendSessionMerges(jdbc);
    }
    void legacy(String id, String target, String status) {
        jdbc.update("""
                INSERT INTO session_merges(operation_id,idempotency_key,params_json,target_session_id,status,stage,
                  package_path,result_json,usage_json,created_at,updated_at) VALUES(?,?,?, ?,?,'snapshot',?,'{"kept":true}','[1]','before','before')
                """,id,id,"{}",target,status,temp.resolve(id).toString());
    }
    @Test void preservesLegacyRowsAndIsSafeToValidateAgain() {
        legacy("done","target1","completed"); legacy("fail","target2","failed"); legacy("active","target3","preparing");
        tx.executeWithoutResult(s -> migration.execute());
        migration.validate(); migration.execute(); new V024_CreateSessionMerges(jdbc).validate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_merges",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT result_json FROM session_merges WHERE operation_id='done'",String.class)).isEqualTo("{\"kept\":true}");
        assertThat(jdbc.queryForObject("SELECT status FROM session_merges WHERE operation_id='active'",String.class)).isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT error_code FROM session_merges WHERE operation_id='active'",String.class)).isEqualTo("LEGACY_INTERRUPTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_merges WHERE protocol_version=1 AND active_slot IS NULL",Integer.class)).isEqualTo(3);
    }
    @Test void rejectsMissingActiveSlotAndCompletedHashesAndRollsBackDuplicateTargets() {
        legacy("one","same","completed"); legacy("two","same","failed");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> migration.execute())).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForList("PRAGMA table_info(session_merges)").stream().map(r -> r.get("name"))).doesNotContain("protocol_version");
        jdbc.update("UPDATE session_merges SET target_session_id='different' WHERE operation_id='two'");
        tx.executeWithoutResult(s -> migration.execute());
        assertThatThrownBy(() -> jdbc.update("UPDATE session_merges SET protocol_version=2,stage='snapshotting',status='paused' WHERE operation_id='two'"))
                .isInstanceOf(DataAccessException.class);
        jdbc.update("UPDATE session_merges SET protocol_version=2,stage='snapshotting',status='paused',active_slot=1 WHERE operation_id='two'");
        assertThatThrownBy(() -> jdbc.update("UPDATE session_merges SET protocol_version=2,stage='snapshotting',status='preparing',active_slot=1 WHERE operation_id='one'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE session_merges SET status='completed',stage='completed',active_slot=NULL WHERE operation_id='two'"))
                .isInstanceOf(DataAccessException.class);
        jdbc.update("UPDATE session_merges SET status='cancelled',active_slot=NULL WHERE operation_id='two'");
    }
    @Test void enforcesUnitResultAndForeignKeyConstraints() {
        tx.executeWithoutResult(s -> migration.execute());
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO session_merge_attempts(request_id,operation_id,unit_id,run_epoch,model,started_at,outcome)
                VALUES('r','missing','u',1,'m','now','running')
                """)).isInstanceOf(DataAccessException.class);
        legacy("one","t","completed");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO session_merge_units(operation_id,unit_id,stage,ordinal,input_hash,input_json,
                    processor_version,model,state,updated_at) VALUES('one','u','extracting',0,'h','{}','v','m','completed','now')
                """)).isInstanceOf(DataAccessException.class);
    }
}
