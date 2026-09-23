package com.aicodeassistant.session.merge;

import com.aicodeassistant.config.database.*;
import com.aicodeassistant.model.Usage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.util.List;
import static com.aicodeassistant.session.merge.MergeHandoffData.*;
import static org.assertj.core.api.Assertions.*;

class MergeProgressRepositoryTest {
    @TempDir Path temp;
    JdbcTemplate jdbc;
    MergeProgressRepository repo;
    TransactionTemplate tx;
    final Execution execution = new Execution("model","/workspace","target",PROCESSOR_VERSION,"v1");
    final UnitInput input = new UnitInput(List.of(new InputRef("r1:p0","A",0,10)),List.of());
    @BeforeEach void setup() {
        var ds = new SQLiteDataSource(); ds.setUrl("jdbc:sqlite:"+temp.resolve("progress.db")); ds.setEnforceForeignKeys(true);
        jdbc = new JdbcTemplate(ds);
        new V002_InitProjectSchema(jdbc).execute(); new V024_CreateSessionMerges(jdbc).execute();
        var manager = new DataSourceTransactionManager(ds); tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(s -> new V026_ExtendSessionMerges(jdbc).execute());
        repo = new MergeProgressRepository(jdbc,new ObjectMapper().findAndRegisterModules(),manager);
        create("one");
    }
    void create(String id) { repo.create(id,id,"{}","target-"+id,temp.resolve(id).toString(),execution); }
    void plan() { repo.plan("one",1,"u","extracting",0,"hash",input,"model"); }
    @Test void singleSlotPersistsAcrossPauseAndCancelledEpochRejectsLateResults() {
        plan(); String request = repo.beginAttempt("one",1,"u","model");
        repo.pause("one",1,"PROVIDER_UNAVAILABLE","retry");
        assertThatThrownBy(() -> create("two")).isInstanceOf(DataAccessException.class);
        Ledger resumed = repo.resume("one",1,execution,false);
        assertThat(resumed.runEpoch()).isEqualTo(2);
        assertThatThrownBy(() -> repo.resume("one",1,execution,false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> repo.commitUnit("one",1,"u","work/old","oldhash")).isInstanceOf(IllegalStateException.class);
        assertThat(repo.cancel("one")).isTrue(); create("two");
        repo.finishAttempt(request,"completed",new MergeSummaryService.CallUsage(request,"model",Usage.zero(),0.1,true));
        repo.finishAttempt(request,"error",null);
        assertThat(repo.find("one").orElseThrow().status()).isEqualTo("cancelled");
        assertThat(repo.unit("one","u").orElseThrow().state()).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_merge_attempts",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT estimated_cost_usd FROM session_merge_attempts",Double.class)).isEqualTo(0.1);
    }
    @Test void targetBindingRequiresAtomicPublicationAndLiveTarget() {
        int version = repo.nextSnapshot("one",1); repo.sealed("one",1,version,"snap",java.util.Map.of());
        repo.stage("one",1,"publishing");
        assertThat(repo.binding("target-one")).isEmpty();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            repo.complete("one",1,"handoff",java.util.Map.of());
            throw new IllegalStateException("commit failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repo.find("one").orElseThrow().status()).isEqualTo("preparing");
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO sessions(id,model,working_dir,created_at,updated_at) VALUES('target-one','m','/w','now','now')");
            repo.complete("one",1,"handoff",java.util.Map.of());
        });
        assertThat(repo.binding("target-one")).isPresent();
        assertThat(repo.cancel("one")).isFalse();
        jdbc.update("DELETE FROM sessions WHERE id='target-one'");
        assertThat(repo.binding("target-one")).isEmpty();
        assertThat(repo.cleanupCandidates()).hasSize(1);
    }
    @Test void splitIsAtomicAndCompletedUnitsSurviveResume() {
        plan(); repo.split("one",1,repo.unit("one","u").orElseThrow(),input,input,"model");
        assertThat(repo.units("one","extracting")).hasSize(3);
        repo.beginAttempt("one",1,"u-0","model"); repo.commitUnit("one",1,"u-0","work/0","h0");
        repo.pause("one",1,"RETRY","retry"); repo.resume("one",1,execution,false);
        assertThat(repo.unit("one","u-0").orElseThrow().state()).isEqualTo("completed");
        assertThat(repo.progress("one","extracting")).isEqualTo(new Progress(1,2,false));
    }
}
