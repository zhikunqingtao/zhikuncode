package com.aicodeassistant.artifact;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.run.RunControlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.aicodeassistant.security.ManagedPathLockManager;

/** Explicit artifact declaration, sealing and verification authority. */
@Service
@DependsOn("migrationRunner")
public class ArtifactManifestService {
    private static final long MAX_VERIFICATION_BYTES = 200L * 1024 * 1024;
    private final JdbcTemplate jdbc; private final SqliteConfig sqlite; private final Path dbPath;
    private final TransactionTemplate tx; private final ObjectMapper json;
    private final RunControlService runs;
    private final com.aicodeassistant.security.ManagedWorkspacePathResolver managedPaths;
    private final ManagedPathLockManager pathLocks;
    private record Update(String id,String state,String actual,Long size,String validator,String failure){}
    public ArtifactManifestService(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc,
            SqliteConfig sqlite, DatabaseResolver resolver,
            @Qualifier("projectTransactionManager") PlatformTransactionManager txManager,
            ObjectMapper json, RunControlService runs,
            com.aicodeassistant.security.ManagedWorkspacePathResolver managedPaths,
            ManagedPathLockManager pathLocks) {
        this.jdbc=jdbc; this.sqlite=sqlite; this.dbPath=resolver.getProjectDbPath(Path.of(System.getProperty("user.dir")));
        this.tx=new TransactionTemplate(txManager); this.json=json; this.runs=runs; this.managedPaths=managedPaths;
        this.pathLocks=pathLocks;
    }

    public ArtifactEntry declare(String runId, String sessionId, String toolUseId,
                                 String path, String operation, String validatorId,
                                 String workspaceRoot) {
        if (runId==null||sessionId==null||toolUseId==null)
            throw new IllegalArgumentException("ARTIFACT_DECLARATION_INCOMPLETE");
        Path canonical=canonical(path, workspaceRoot); Instant now=Instant.now();
        String normalizedOperation=normalizeOperation(operation);
        String manifestId=write(() -> {
            List<String> ids=jdbc.queryForList("SELECT manifest_id FROM artifact_manifests WHERE run_id=?",String.class,runId);
            String id=ids.isEmpty()?UUID.randomUUID().toString():ids.getFirst();
            if(ids.isEmpty()) jdbc.update("INSERT INTO artifact_manifests(manifest_id,run_id,session_id,workspace_root,state,created_at,updated_at) VALUES(?,?,?,?,'open',?,?)",
                    id,runId,sessionId,root(workspaceRoot).toString(),now.toString(),now.toString());
            else {
                String storedRoot=jdbc.queryForObject("SELECT workspace_root FROM artifact_manifests WHERE manifest_id=?",String.class,id);
                if(!root(workspaceRoot).toString().equals(storedRoot))
                    throw new IllegalArgumentException("ARTIFACT_WORKSPACE_ROOT_CONFLICT");
            }
            jdbc.update("""
                    INSERT INTO artifact_entries(artifact_id,manifest_id,tool_use_id,canonical_path,operation,state,
                      sealed_hash,required_validator_id,created_at,updated_at)
                    VALUES(?,?,?,?,?,'declared',NULL,?,?,?)
                    ON CONFLICT(manifest_id,canonical_path) DO UPDATE SET tool_use_id=excluded.tool_use_id,
                      operation=excluded.operation,state='declared',sealed_hash=NULL,
                      actual_hash=NULL,file_size=NULL,required_validator_id=excluded.required_validator_id,
                      validator_result_json=NULL,failure_code=NULL,updated_at=excluded.updated_at
                    """,UUID.randomUUID().toString(),id,toolUseId,canonical.toString(),normalizedOperation,
                    validatorId==null?"sha256":validatorId,now.toString(),now.toString());
            jdbc.update("UPDATE artifact_manifests SET state='open',updated_at=? WHERE manifest_id=?",now.toString(),id);
            runs.appendEventInCurrentWrite(runId,"artifact_declared",toolUseId,Map.of(
                    "manifestId",id,"path",canonical.toString(),"operation",normalizedOperation,
                    "validatorId",validatorId==null?"sha256":validatorId));
            return id;
        });
        return loadEntries(manifestId).stream().filter(e->e.filePath().equals(canonical.toString())).findFirst().orElseThrow();
    }

    private ArtifactEntry sealLocked(String runId, Path canonical, String sealedHash, String workspaceRoot) {
        Instant now=Instant.now();
        String manifestId=write(() -> {
            List<String> ids=jdbc.queryForList("SELECT manifest_id FROM artifact_manifests WHERE run_id=?",String.class,runId);
            if(ids.isEmpty())throw new IllegalArgumentException("ARTIFACT_NOT_DECLARED");
            String id=ids.getFirst();
            String storedRoot=jdbc.queryForObject("SELECT workspace_root FROM artifact_manifests WHERE manifest_id=?",String.class,id);
            if(!root(workspaceRoot).toString().equals(storedRoot))
                throw new IllegalArgumentException("ARTIFACT_WORKSPACE_ROOT_CONFLICT");
            int updated=jdbc.update("UPDATE artifact_entries SET state='sealed',sealed_hash=?,updated_at=? WHERE manifest_id=? AND canonical_path=? AND state='declared'",
                    sealedHash.toLowerCase(),now.toString(),id,canonical.toString());
            if(updated!=1)throw new IllegalStateException("ARTIFACT_SEAL_STATE_CONFLICT");
            Integer open=jdbc.queryForObject("SELECT COUNT(*) FROM artifact_entries WHERE manifest_id=? AND state='declared'",Integer.class,id);
            if(open!=null&&open==0)jdbc.update("UPDATE artifact_manifests SET state='sealed',updated_at=? WHERE manifest_id=?",now.toString(),id);
            runs.appendEventInCurrentWrite(runId,"artifact_sealed",null,Map.of(
                    "manifestId",id,"path",canonical.toString(),"sealedHash",sealedHash.toLowerCase()));
            return id;
        });
        return loadEntries(manifestId).stream().filter(e->e.filePath().equals(canonical.toString())).findFirst().orElseThrow();
    }

    public ArtifactEntry sealFromFile(String runId, String path, String workspaceRoot) throws Exception {
        Path canonical=canonical(path,workspaceRoot);
        return pathLocks.withLock(canonical,()->{
            if(!Files.isRegularFile(canonical,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(canonical))
                throw new IllegalArgumentException("ARTIFACT_OUTPUT_NOT_REGULAR_FILE");
            return sealLocked(runId,canonical,computeSha256(canonical),workspaceRoot);
        });
    }

    public VerificationResult verify(String manifestId) {
        ArtifactManifest manifest=getManifestById(manifestId).orElseThrow(()->new IllegalArgumentException("ARTIFACT_MANIFEST_NOT_FOUND"));
        List<ArtifactEntry> entries=loadEntries(manifestId); List<VerificationResult.FailureDetail> failures=new ArrayList<>();
        int verified=0,failed=0,unverified=0; Instant now=Instant.now();
        List<Update> updates=new ArrayList<>();
        for(ArtifactEntry entry:entries){
            String state="failed",actual=null,failure=null; Long size=null; String validator=null;
            try{
                Path path=canonical(entry.filePath(),manifest.workspaceRoot());
                Update update=pathLocks.withLock(path,()->verifyEntry(entry,path,now));
                state=update.state(); actual=update.actual(); size=update.size(); validator=update.validator(); failure=update.failure();
            }catch(Exception e){failure="VERIFICATION_ERROR"; validator=safeJson(Map.of("error",String.valueOf(e.getMessage())));}
            boolean passed="integrity_verified".equals(state)||"content_verified".equals(state);
            if(passed)verified++; else if("unverified".equals(state)||"unverified_size_limit".equals(state))unverified++; else failed++;
            if(!passed)failures.add(new VerificationResult.FailureDetail(entry.filePath(),failure));
            updates.add(new Update(entry.id(),state,actual,size,validator,failure));
        }
        String manifestState=failed>0?(verified>0?"partial":"failed"):(unverified>0?"unverified":"verified");
        int verifiedCount=verified, failedOrUnverifiedCount=failed+unverified;
        write(()->{for(Update u:updates)jdbc.update("UPDATE artifact_entries SET state=?,actual_hash=?,file_size=?,validator_result_json=?,failure_code=?,updated_at=? WHERE artifact_id=?",
                u.state(),u.actual(),u.size(),u.validator(),u.failure(),now.toString(),u.id());
            jdbc.update("UPDATE artifact_manifests SET state=?,updated_at=? WHERE manifest_id=?",manifestState,now.toString(),manifestId);
            String runId=jdbc.queryForObject("SELECT run_id FROM artifact_manifests WHERE manifest_id=?",String.class,manifestId);
            runs.appendEventInCurrentWrite(runId,"artifact_verification_changed",null,Map.of(
                    "manifestId",manifestId,"status",manifestState,"verified",verifiedCount,
                    "failedOrUnverified",failedOrUnverifiedCount,"total",entries.size()));
            return null;});
        return new VerificationResult(manifestState,verified,failed+unverified,entries.size(),failures);
    }

    private Update verifyEntry(ArtifactEntry entry,Path path,Instant now) throws Exception {
        String state="failed",actual=null,failure=null,validator=null;Long size=null;
        if("deleted".equals(entry.operation())){
            if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))failure="DELETE_TARGET_STILL_EXISTS";
            else if(entry.expectedHash()==null)failure="SEALED_HASH_MISSING";
            else {state="integrity_verified";validator=json.writeValueAsString(Map.of("validator","sha256","deleted",true));}
        } else if(entry.expectedHash()==null){state="unverified";failure="SEALED_HASH_MISSING";}
        else if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path)){failure="ARTIFACT_NOT_REGULAR_FILE";}
        else {size=Files.size(path);if(size>MAX_VERIFICATION_BYTES){state="unverified_size_limit";failure="VERIFICATION_SIZE_LIMIT";}
        else {actual=computeSha256(path);if(!entry.expectedHash().equals(actual))failure="HASH_MISMATCH";
        else if(entry.requiredValidatorId()==null||"sha256".equals(entry.requiredValidatorId())){state="integrity_verified";validator=json.writeValueAsString(Map.of("validator","sha256","passed",true));}
        else {state="unverified";failure="VALIDATOR_UNAVAILABLE";validator=json.writeValueAsString(Map.of("validator",entry.requiredValidatorId(),"passed",false));}}}
        return new Update(entry.id(),state,actual,size,validator,failure);
    }

    public Optional<ArtifactManifest> getManifest(String runId){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM artifact_manifests WHERE run_id=?",runId);
        return rows.isEmpty()?Optional.empty():Optional.of(mapManifest(rows.getFirst()));
    }
    public Optional<ArtifactManifest> getManifestById(String id){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM artifact_manifests WHERE manifest_id=?",id);
        return rows.isEmpty()?Optional.empty():Optional.of(mapManifest(rows.getFirst()));
    }
    public String computeSha256(Path path) throws Exception { MessageDigest d=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)d.update(b,0,n);}return HexFormat.of().formatHex(d.digest());}
    private ArtifactManifest mapManifest(Map<String,Object> r){String id=String.valueOf(r.get("manifest_id"));return new ArtifactManifest(id,String.valueOf(r.get("run_id")),String.valueOf(r.get("session_id")),String.valueOf(r.get("workspace_root")),String.valueOf(r.get("state")),Instant.parse(String.valueOf(r.get("created_at"))),Instant.parse(String.valueOf(r.get("updated_at"))),loadEntries(id));}
    private List<ArtifactEntry> loadEntries(String id){return jdbc.query("SELECT * FROM artifact_entries WHERE manifest_id=? ORDER BY created_at",(rs,n)->new ArtifactEntry(rs.getString("artifact_id"),rs.getString("manifest_id"),rs.getString("tool_use_id"),rs.getString("canonical_path"),rs.getString("operation"),rs.getString("state"),rs.getString("sealed_hash"),rs.getString("actual_hash"),rs.getObject("file_size")==null?null:rs.getLong("file_size"),rs.getString("required_validator_id"),rs.getString("validator_result_json"),rs.getString("failure_code"),Instant.parse(rs.getString("created_at")),Instant.parse(rs.getString("updated_at"))),id);}
    private Path canonical(String raw,String workspaceRoot){try{return managedPaths.resolveProspective(Path.of(raw),workspaceRoot);}catch(IOException|IllegalArgumentException e){throw new IllegalArgumentException("ARTIFACT_PATH_INVALID",e);}}
    private Path root(String workspaceRoot){try{return Path.of(workspaceRoot).toRealPath();}catch(IOException|RuntimeException e){throw new IllegalArgumentException("ARTIFACT_WORKSPACE_ROOT_INVALID",e);}}
    private static String normalizeOperation(String op){return switch(op==null?"":op.toLowerCase()){case "create","created"->"created";case "modify","modified","update"->"modified";case "delete","deleted"->"deleted";default->throw new IllegalArgumentException("ARTIFACT_OPERATION_INVALID");};}
    private String safeJson(Object v){try{return json.writeValueAsString(v);}catch(Exception e){return "{}";}}
    private <T>T write(java.util.function.Supplier<T> op){return sqlite.executeWrite(dbPath,()->tx.execute(s->op.get()));}
}
