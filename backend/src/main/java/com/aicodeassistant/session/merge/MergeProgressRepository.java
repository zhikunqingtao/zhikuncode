package com.aicodeassistant.session.merge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import static com.aicodeassistant.session.merge.MergeHandoffData.*;

/** Durable progress and epoch fencing. No files, provider calls or scheduling in transactions. */
@Repository
public class MergeProgressRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public MergeProgressRepository(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc, ObjectMapper json,
            @Qualifier("projectTransactionManager") PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.json = json; this.tx = new TransactionTemplate(transactions);
    }
    private String now() { return Instant.now().toString(); }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (java.io.IOException e) { throw new IllegalStateException("MERGE_RECORD_UNREADABLE", e); }
    }
    public <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (java.io.IOException e) { throw new IllegalStateException("MERGE_RECORD_UNREADABLE", e); }
    }
    public Optional<Ledger> find(String id) { return one("SELECT * FROM session_merges WHERE operation_id=?", id); }
    public Optional<Ledger> byKey(String key) { return one("SELECT * FROM session_merges WHERE idempotency_key=?", key); }
    public Optional<Ledger> active() { return one("SELECT * FROM session_merges WHERE active_slot=1"); }
    public Optional<Ledger> binding(String sessionId) {
        return one("""
                SELECT m.* FROM session_merges m JOIN sessions s ON s.id=m.target_session_id
                WHERE m.protocol_version=2 AND m.status='completed' AND m.target_session_id=?
                """, sessionId);
    }
    public List<Ledger> interrupted() {
        return jdbc.queryForList("SELECT * FROM session_merges WHERE protocol_version=2 AND status='preparing'")
                .stream().map(this::ledger).toList();
    }
    public List<Ledger> cleanupCandidates() {
        return jdbc.queryForList("""
                SELECT m.* FROM session_merges m WHERE m.protocol_version=2 AND
                (m.status='cancelled' OR (m.status='completed' AND NOT EXISTS
                  (SELECT 1 FROM sessions s WHERE s.id=m.target_session_id)))
                """).stream().map(this::ledger).toList();
    }
    private Optional<Ledger> one(String sql, Object... args) {
        return jdbc.queryForList(sql, args).stream().findFirst().map(this::ledger);
    }
    private Ledger ledger(Map<String, Object> r) {
        return new Ledger(s(r,"operation_id"),s(r,"idempotency_key"),s(r,"params_json"),s(r,"target_session_id"),
                s(r,"status"),s(r,"stage"),s(r,"package_path"),n(r,"protocol_version").intValue(),n(r,"run_epoch").longValue(),
                n(r,"snapshot_version").intValue(),s(r,"snapshot_hash"),s(r,"handoff_hash"),
                decode(s(r,"execution_json"),Execution.class),s(r,"error_code"),s(r,"error"),s(r,"retry_at"),
                s(r,"result_json"),s(r,"usage_json"));
    }
    private static String s(Map<String,Object> r, String key) { return (String) r.get(key); }
    private static Number n(Map<String,Object> r, String key) { return (Number) r.get(key); }
    private static void changed(int rows) { if (rows != 1) throw new IllegalStateException("MERGE_STALE_EXECUTION"); }
    public void create(String id, String key, String params, String target, String path, Execution execution) {
        String now = now();
        jdbc.update("""
                INSERT INTO session_merges(operation_id,idempotency_key,params_json,target_session_id,status,stage,
                  package_path,created_at,updated_at,protocol_version,active_slot,run_epoch,execution_json)
                VALUES(?,?,?,?,'preparing','snapshotting',?,?,?,2,1,1,?)
                """, id,key,params,target,path,now,now,encode(execution));
    }
    public void assertCurrent(String id, long epoch) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM session_merges WHERE operation_id=? AND run_epoch=? AND status='preparing'",
                Integer.class,id,epoch) != 1) throw new IllegalStateException("MERGE_STALE_EXECUTION");
    }
    public void stage(String id, long epoch, String stage) {
        changed(jdbc.update("UPDATE session_merges SET stage=?,retry_at=NULL,updated_at=? WHERE operation_id=? AND run_epoch=? AND status='preparing'",
                stage,now(),id,epoch));
    }
    public int nextSnapshot(String id, long epoch) {
        return Objects.requireNonNull(tx.execute(status -> {
            changed(jdbc.update("""
                    UPDATE session_merges SET snapshot_version=snapshot_version+1,updated_at=?
                    WHERE operation_id=? AND run_epoch=? AND status='preparing' AND snapshot_hash IS NULL
                    """,now(),id,epoch));
            return find(id).orElseThrow().snapshotVersion();
        }));
    }
    public void sealed(String id, long epoch, int version, String hash, Object result) {
        changed(jdbc.update("""
                UPDATE session_merges SET snapshot_hash=?,stage='extracting',result_json=?,updated_at=?
                WHERE operation_id=? AND run_epoch=? AND snapshot_version=? AND status='preparing'
                """,hash,encode(result),now(),id,epoch,version));
    }
    public void pause(String id, long epoch, String code, String message) {
        jdbc.update("""
                UPDATE session_merges SET status='paused',error_code=?,error=?,retry_at=NULL,updated_at=?
                WHERE operation_id=? AND run_epoch=? AND status='preparing'
                """,code,message,now(),id,epoch);
    }
    public void retryAt(String id, long epoch, String at) {
        changed(jdbc.update("UPDATE session_merges SET retry_at=?,updated_at=? WHERE operation_id=? AND run_epoch=? AND status='preparing'",
                at,now(),id,epoch));
    }
    public Ledger resume(String id, long expectedEpoch, Execution execution, boolean recovery) {
        return Objects.requireNonNull(tx.execute(status -> {
            changed(jdbc.update("""
                    UPDATE session_merges SET status='preparing',run_epoch=run_epoch+1,execution_json=?,
                      error_code=NULL,error=NULL,retry_at=NULL,updated_at=?
                    WHERE operation_id=? AND protocol_version=2 AND run_epoch=? AND status=?
                    """,encode(execution),now(),id,expectedEpoch,recovery ? "preparing" : "paused"));
            jdbc.update("UPDATE session_merge_units SET state='pending',updated_at=? WHERE operation_id=? AND state IN ('running','failed')",now(),id);
            jdbc.update("UPDATE session_merge_attempts SET outcome='unknown',finished_at=? WHERE operation_id=? AND outcome='running'",now(),id);
            return find(id).orElseThrow();
        }));
    }
    public boolean cancel(String id) {
        return jdbc.update("""
                UPDATE session_merges SET status='cancelled',run_epoch=run_epoch+1,active_slot=NULL,retry_at=NULL,updated_at=?
                WHERE operation_id=? AND protocol_version=2 AND status IN ('preparing','paused','failed')
                """,now(),id) == 1;
    }
    /** Caller must create the target and its entry message in the same transaction. */
    public void complete(String id, long epoch, String hash, Object result) {
        changed(jdbc.update("""
                UPDATE session_merges SET status='completed',stage='completed',handoff_hash=?,result_json=?,
                  active_slot=NULL,retry_at=NULL,error=NULL,error_code=NULL,updated_at=?
                WHERE operation_id=? AND run_epoch=? AND status='preparing' AND stage='publishing' AND snapshot_hash IS NOT NULL
                """,hash,encode(result),now(),id,epoch));
    }
    public void plan(String id, long epoch, String unitId, String stage, long ordinal,
                     String inputHash, UnitInput input, String model) {
        tx.executeWithoutResult(status -> {
            assertCurrent(id,epoch);
            jdbc.update("""
                    INSERT INTO session_merge_units(operation_id,unit_id,stage,ordinal,input_hash,input_json,
                      processor_version,model,state,run_epoch,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,'pending',?,?) ON CONFLICT(operation_id,unit_id) DO NOTHING
                    """,id,unitId,stage,ordinal,inputHash,encode(input),PROCESSOR_VERSION,model,epoch,now());
            Unit old = unit(id,unitId).orElseThrow();
            if (!inputHash.equals(old.inputHash()) || !stage.equals(old.stage()))
                throw new IllegalStateException("MERGE_UNIT_INPUT_CHANGED");
        });
    }
    public Optional<Unit> unit(String id, String unitId) {
        return jdbc.queryForList("SELECT * FROM session_merge_units WHERE operation_id=? AND unit_id=?",id,unitId)
                .stream().findFirst().map(this::unit);
    }
    public List<Unit> units(String id, String stage) {
        return jdbc.queryForList("SELECT * FROM session_merge_units WHERE operation_id=? AND stage=? ORDER BY ordinal,unit_id",id,stage)
                .stream().map(this::unit).toList();
    }
    private Unit unit(Map<String,Object> r) {
        return new Unit(s(r,"operation_id"),s(r,"unit_id"),s(r,"stage"),n(r,"ordinal").longValue(),s(r,"input_hash"),
                s(r,"input_json"),s(r,"model"),s(r,"state"),n(r,"attempt_count").intValue(),n(r,"run_epoch").longValue(),
                s(r,"result_path"),s(r,"result_hash"));
    }
    public String beginAttempt(String id, long epoch, String unitId, String model) {
        return Objects.requireNonNull(tx.execute(status -> {
            assertCurrent(id,epoch);
            changed(jdbc.update("""
                    UPDATE session_merge_units SET state='running',model=?,run_epoch=?,attempt_count=attempt_count+1,updated_at=?
                    WHERE operation_id=? AND unit_id=? AND state IN ('pending','running')
                    """,model,epoch,now(),id,unitId));
            String request = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO session_merge_attempts(request_id,operation_id,unit_id,run_epoch,model,started_at,outcome)
                    VALUES(?,?,?,?,?,?,'running')
                    """,request,id,unitId,epoch,model,now());
            return request;
        }));
    }
    /** Late usage can close its own attempt, but can never advance a unit or resurrect an operation. */
    public void finishAttempt(String request, String outcome, MergeSummaryService.CallUsage usage) {
        tx.executeWithoutResult(status -> {
            var rows = jdbc.queryForList("SELECT operation_id FROM session_merge_attempts WHERE request_id=?",request);
            if (rows.isEmpty()) throw new IllegalArgumentException("MERGE_ATTEMPT_NOT_FOUND");
            boolean reported = usage != null && usage.usageReported();
            int updated = jdbc.update("""
                    UPDATE session_merge_attempts SET outcome=?,finished_at=?,usage_json=?,usage_reported=?,estimated_cost_usd=?
                    WHERE request_id=? AND outcome IN ('running','unknown')
                    """,outcome,now(),reported ? encode(usage.usage()) : null,reported ? 1 : 0,
                    reported ? usage.estimatedCostUsd() : null,request);
            if (updated == 1) {
                String id = rows.getFirst().get("operation_id").toString();
                var aggregate = jdbc.queryForMap("""
                        SELECT COUNT(*) AS calls,SUM(usage_reported) AS reportedCalls,
                          SUM(estimated_cost_usd) AS estimatedCostUsd FROM session_merge_attempts WHERE operation_id=?
                        """,id);
                jdbc.update("UPDATE session_merges SET usage_json=? WHERE operation_id=?",encode(aggregate),id);
            }
        });
    }
    public void failAttempt(String request) {
        finishAttempt(request,"error",null);
        jdbc.update("UPDATE session_merge_attempts SET outcome='error' WHERE request_id=? AND outcome='completed'",request);
    }
    public void commitUnit(String id, long epoch, String unitId, String path, String hash) {
        tx.executeWithoutResult(status -> {
            assertCurrent(id,epoch);
            changed(jdbc.update("""
                    UPDATE session_merge_units SET state='completed',result_path=?,result_hash=?,error_code=NULL,updated_at=?
                    WHERE operation_id=? AND unit_id=? AND run_epoch=? AND state='running'
                    """,path,hash,now(),id,unitId,epoch));
        });
    }
    public void split(String id, long epoch, Unit parent, UnitInput left, UnitInput right, String model) {
        tx.executeWithoutResult(status -> {
            assertCurrent(id,epoch);
            List<String> children = List.of(parent.unitId()+"-0",parent.unitId()+"-1");
            plan(id,epoch,children.get(0),parent.stage(),parent.ordinal(),sha256(encode(left)),left,model);
            plan(id,epoch,children.get(1),parent.stage(),parent.ordinal(),sha256(encode(right)),right,model);
            changed(jdbc.update("""
                    UPDATE session_merge_units SET state='split',input_json=?,updated_at=?
                    WHERE operation_id=? AND unit_id=? AND state IN ('pending','running')
                    """,encode(new UnitInput(decode(parent.inputJson(),UnitInput.class).inputs(),children)),now(),id,parent.unitId()));
        });
    }
    public Progress progress(String id, String stage) {
        var row = jdbc.queryForMap("""
                SELECT COUNT(*) AS known,COALESCE(SUM(CASE WHEN state='completed' THEN 1 ELSE 0 END),0) AS completed
                FROM session_merge_units WHERE operation_id=? AND state<>'split'
                """,id);
        return new Progress(n(row,"completed").longValue(),n(row,"known").longValue(),
                Set.of("validating","publishing","completed").contains(stage));
    }
}
