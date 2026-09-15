package com.aicodeassistant.artifact.meoo;

import com.aicodeassistant.tool.ToolUseContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service
public class MeooPublicationStore {
    private final JdbcTemplate jdbc;
    public MeooPublicationStore(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Publication(String id,String label,String runtime,String state,String projectId,String projectUrl,String version,String accessUrl,String errorCode) {}
    public record Claim(boolean created,Publication publication) {}
    public Claim claim(ToolUseContext context,MeooPublicationPolicy.Snapshot snapshot) {
        if(context.currentRunId()==null || context.sessionId()==null || context.toolUseId()==null) throw new MeooException("MEOO_RUN_REQUIRED");
        String id=UUID.randomUUID().toString(), now=Instant.now().toString();
        int count=jdbc.update("""
            INSERT OR IGNORE INTO meoo_publications
              (id,session_id,run_id,tool_use_id,snapshot_hash,label,runtime,account_id,state,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,'pending',?,?)
            """,id,context.sessionId(),context.currentRunId(),context.toolUseId(),snapshot.sha256(),snapshot.name(),snapshot.runtime(),snapshot.account(),now,now);
        var row=jdbc.queryForMap("SELECT * FROM meoo_publications WHERE run_id=? AND tool_use_id=?",context.currentRunId(),context.toolUseId());
        if(!snapshot.sha256().equals(row.get("snapshot_hash")) || !context.sessionId().equals(row.get("session_id")) || !snapshot.account().equals(row.get("account_id")) || !snapshot.runtime().equals(row.get("runtime")) || !snapshot.name().equals(row.get("label"))) throw new MeooException("MEOO_INVOCATION_CONFLICT");
        return new Claim(count==1,from(row));
    }
    public Publication get(String id) { return from(jdbc.queryForMap("SELECT * FROM meoo_publications WHERE id=?",id)); }
    public void projectCreated(String id,String project) {
        if(project==null || !project.matches("[A-Za-z0-9_-]{1,128}")) throw new MeooException("MEOO_INVALID_RESPONSE");
        // CLI image JSON omits projectUrl. This is the official management route,
        // based only on the newly created provider ID, never a model-supplied URL.
        String settings=MeooAccessVerifier.trustedProject("https://meoo.com/chat/"+project,project);
        jdbc.update("UPDATE meoo_publications SET project_id=?,project_url=?,state='deploying',updated_at=? WHERE id=?",project,settings,Instant.now().toString(),id);
    }
    public void finish(String id,String state,String version,String url,String code) { jdbc.update("UPDATE meoo_publications SET state=?,version=?,access_url=?,error_code=?,updated_at=? WHERE id=?",state,version,url,code,Instant.now().toString(),id); }
    private Publication from(Map<String,Object> r) { return new Publication((String)r.get("id"),(String)r.get("label"),(String)r.get("runtime"),(String)r.get("state"),(String)r.get("project_id"),(String)r.get("project_url"),(String)r.get("version"),(String)r.get("access_url"),(String)r.get("error_code")); }
}
