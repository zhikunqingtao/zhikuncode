package com.aicodeassistant.session.merge;

import com.aicodeassistant.coordinator.SwarmService;
import com.aicodeassistant.security.SystemScratchpadPathPolicy;
import com.aicodeassistant.tool.agent.BackgroundAgentTracker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import static com.aicodeassistant.session.merge.MergeHandoffData.*;

/** A readable, immutable handoff. It never imports executable tool or provider state. */
@Service
public class MergePackageService {
    public record Asset(String sourceSessionId, String originalPath, String copiedPath, String status,
                        String reason, long size, String sha256) { }
    public record Source(String id, String title, String workingDirectory, int messages, String lastMessageId) { }
    public record Bundle(Path path, List<Path> transcripts, List<Source> sources, List<Asset> assets,
                         @com.fasterxml.jackson.annotation.JsonIgnore MergeTextBudget textBudget) {
        public Bundle(Path path, List<Path> transcripts, List<Source> sources, List<Asset> assets) {
            this(path, transcripts, sources, assets, MergeTextBudget.defaults(path));
        }
        public long copiedCount() { return assets.stream().filter(a -> a.status().equals("copied")).count(); }
        public long warningCount() { return assets.stream().filter(a -> Set.of("missing", "ownership_unknown", "copy_failed").contains(a.status())).count(); }
    }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SystemScratchpadPathPolicy scratchpads;
    private final SwarmService swarms;
    private final BackgroundAgentTracker agents;

    public MergePackageService(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc,
            ObjectMapper json, SystemScratchpadPathPolicy scratchpads, SwarmService swarms, BackgroundAgentTracker agents) {
        this.jdbc = jdbc; this.json = json; this.scratchpads = scratchpads;
        this.swarms = swarms; this.agents = agents;
    }
    // Per operation, shared by A/B, descendants and embedded images. Failed partial writes
    // still consume the allowance so repeatedly changing files cannot cause unbounded writes.
    @org.springframework.beans.factory.annotation.Value("${zhikuncode.session-merge.max-copy-bytes:1073741824}")
    private long maxCopyBytes = 1024L * 1024 * 1024;
    @org.springframework.beans.factory.annotation.Value("${zhikuncode.session-merge.min-free-bytes:1073741824}")
    private long minFreeBytes = 1024L * 1024 * 1024;

    long availableBytes(Path directory) throws IOException {
        return Files.getFileStore(directory).getUsableSpace();
    }
    private final class CopyBudget {
        final Path directory;
        long written;
        CopyBudget(Path directory) { this.directory = directory; }
        void check(long bytes) throws IOException {
            if (bytes > Math.max(0, maxCopyBytes) - written)
                throw new CopyLimitException("已达本次合并产物复制容量上限，未收录");
            if (availableBytes(directory) - bytes < Math.max(0, minFreeBytes))
                throw new CopyLimitException("磁盘可用空间不足以保留安全余量，未收录");
        }
    }
    private static final class CopyLimitException extends IOException {
        CopyLimitException(String reason) { super("MERGE_COPY_INCOMPLETE: " + reason); }
    }
    // Bound a single JDBC/JSON allocation before retrieving content from SQLite. Never truncate it.
    static final int MAX_RECORD_BYTES = 16 * 1024 * 1024;
    record SessionInfo(String title, String model, String workingDir, JsonNode metadata) { }
    SessionInfo sessionInfo(String id) {
        var rows = jdbc.queryForList("SELECT title,model,working_dir,metadata_json FROM sessions WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("来源会话不存在");
        var row = rows.getFirst();
        try {
            JsonNode metadata = json.readTree(Objects.toString(row.get("metadata_json"), "{}"));
            if (metadata == null || !metadata.isObject()) throw new IOException("Invalid session metadata");
            return new SessionInfo((String) row.get("title"), (String) row.get("model"), (String) row.get("working_dir"), metadata);
        } catch (IOException invalid) { throw new IllegalArgumentException("来源会话元数据无法读取", invalid); }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.aicodeassistant.config.database.DatabaseResolver databaseResolver;

    public Path packageRoot() {
        Path database;
        if (databaseResolver != null) database = databaseResolver.getProjectDbPath(Path.of(System.getProperty("user.dir")));
        else database = jdbc.queryForList("PRAGMA database_list").stream()
                .filter(row -> "main".equals(row.get("name"))).map(row -> Path.of(row.get("file").toString()))
                .findFirst().orElseThrow();
        return database.toAbsolutePath().normalize().getParent().resolve("session-merges");
    }
    public Path packagePath(String target, String operation) {
        return scratchpads.resolveChild(target).resolve("handoffs").resolve(UUID.fromString(operation).toString());
    }
    public Path snapshotPath(String operation) { return packageRoot().resolve(UUID.fromString(operation).toString()); }

    public List<String> descendants(List<String> roots) {
        var result = new LinkedHashSet<>(roots);
        var rows = jdbc.queryForList("SELECT id, metadata_json FROM sessions");
        boolean added;
        do {
            added = false;
            for (var row : rows) {
                try {
                    JsonNode meta = json.readTree(Objects.toString(row.get("metadata_json"), "{}"));
                    if (meta != null && result.contains(meta.path("parent_session_id").asText()))
                        added |= result.add(row.get("id").toString());
                } catch (IOException invalid) { /* Unrelated corrupt metadata cannot establish ownership. */ }
            }
        } while (added);
        return List.copyOf(result);
    }

    public record Snapshot(String hash, int version, long records, long files, long copiedCount,
                           long warningCount, String blockedReason) { }
    @org.springframework.beans.factory.annotation.Value("${zhikuncode.session-merge.max-record-materialize-bytes:67108864}")
    private int maxRecordMaterializeBytes = 64 * 1024 * 1024;

    /** A bounded reader of a SQLite TEXT column. No JDBC allocation grows with the whole record. */
    private Reader columnReader(String table, String column, String id) { return columnReader(table,column,"id",id); }
    private Reader columnReader(String table, String column, String idColumn, String id) {
        InputStream chunks = new InputStream() {
            byte[] bytes = new byte[0]; int index; long offset = 1; boolean ended;
            @Override public int read() throws IOException {
                byte[] one = new byte[1]; return read(one,0,1) < 0 ? -1 : one[0] & 255;
            }
            @Override public int read(byte[] out, int start, int length) throws IOException {
                if (length == 0) return 0;
                if (index == bytes.length && !ended) {
                    // Table and column names are internal constants, never request/model input.
                    List<byte[]> rows = jdbc.query("SELECT substr(CAST("+column+" AS BLOB),?,65536) FROM "+table+" WHERE "+idColumn+"=?",
                            (rs,n) -> rs.getBytes(1),offset,id);
                    if (rows.isEmpty()) throw new IOException("SOURCE_CHANGED");
                    bytes = rows.getFirst(); if (bytes == null) bytes = new byte[0];
                    index = 0; offset += bytes.length; ended = bytes.length == 0;
                }
                if (ended) return -1;
                int count = Math.min(length,bytes.length-index); System.arraycopy(bytes,index,out,start,count); index += count;
                return count;
            }
        };
        return new InputStreamReader(chunks,StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT));
    }
    MergeTextBudget textBudget(Path directory) { return new MergeTextBudget(minFreeBytes,() -> availableBytes(directory)); }

    public Snapshot seal(Path directory, List<String> roots, int version, Runnable check) throws IOException {
        requireOperationPath(directory);
        Files.createDirectories(directory);
        Path sealed = directory.resolve("snapshot");
        if (Files.exists(sealed,LinkOption.NOFOLLOW_LINKS)) return validateSnapshot(directory,null,check);
        Path staging = directory.resolve("staging");
        deleteTree(staging); Files.createDirectories(staging);
        try (var writer = new SnapshotWriter(staging,check)) {
            for (String id : descendants(roots)) { check.run(); writer.source(id); }
            writer.closeCatalogs();
            Map<String,Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion",2); manifest.put("operationId",directory.getFileName().toString());
            manifest.put("snapshotVersion",version); manifest.put("sources",roots);
            manifest.put("capturedAt",Instant.now().toString()); manifest.put("recordCount",writer.recordCount);
            manifest.put("fileCount",writer.fileCount); manifest.put("copiedCount",writer.copiedCount);
            manifest.put("warningCount",writer.warningCount); manifest.put("blockedReason",writer.blockedReason);
            var catalogs = new LinkedHashMap<String,Object>();
            for (String name : List.of("records.jsonl","files.jsonl","occurrences.jsonl","gaps.jsonl"))
                catalogs.put(name,Map.of("sha256",hash(staging.resolve(name),check),"bytes",Files.size(staging.resolve(name))));
            manifest.put("catalogs",catalogs);
            writer.budget.write(staging.resolve("manifest.json"),json.writeValueAsString(manifest),StandardOpenOption.CREATE_NEW);
            String manifestHash = hash(staging.resolve("manifest.json"),check);
            writer.budget.write(staging.resolve("seal.json"),json.writeValueAsString(Map.of("snapshotVersion",version,"manifestHash",manifestHash)),StandardOpenOption.CREATE_NEW);
            check.run(); Files.move(staging,sealed,StandardCopyOption.ATOMIC_MOVE);
            return new Snapshot(manifestHash,version,writer.recordCount,writer.fileCount,writer.copiedCount,writer.warningCount,writer.blockedReason);
        }
    }
    public Snapshot validateSnapshot(Path directory, String expectedHash, Runnable check) throws IOException {
        requireOperationPath(directory);
        Path root = directory.resolve("snapshot");
        Path manifestPath = safeFile(root,"manifest.json");
        if (Files.size(manifestPath) > 1024*1024) throw new IOException("MERGE_SNAPSHOT_CORRUPT");
        String actual = hash(manifestPath,check);
        JsonNode manifest = json.readTree(Files.readString(manifestPath));
        JsonNode seal = json.readTree(Files.readString(safeFile(root,"seal.json")));
        if ((expectedHash != null && !expectedHash.equals(actual)) || !actual.equals(seal.path("manifestHash").asText())
                || !directory.getFileName().toString().equals(manifest.path("operationId").asText())
                || manifest.path("schemaVersion").asInt()!=2 || manifest.path("snapshotVersion").asInt()!=seal.path("snapshotVersion").asInt())
            throw new IOException("MERGE_SNAPSHOT_CORRUPT");
        for (String name : List.of("records.jsonl","files.jsonl","occurrences.jsonl","gaps.jsonl")) {
            if (!hash(safeFile(root,name),check).equals(manifest.path("catalogs").path(name).path("sha256").asText()))
                throw new IOException("MERGE_SNAPSHOT_CORRUPT");
        }
        try (var lines = Files.newBufferedReader(safeFile(root,"files.jsonl"))) {
            String line;
            while ((line=lines.readLine())!=null) {
                check.run(); FileEntry entry=json.readValue(line,FileEntry.class);
                Path file=safeFile(directory,entry.path());
                if (Files.size(file)!=entry.bytes() || !hash(file,check).equals(entry.sha256())) throw new IOException("MERGE_SNAPSHOT_CORRUPT");
            }
        }
        return new Snapshot(actual,manifest.path("snapshotVersion").asInt(),manifest.path("recordCount").asLong(),
                manifest.path("fileCount").asLong(),manifest.path("copiedCount").asLong(),manifest.path("warningCount").asLong(),
                manifest.path("blockedReason").isNull() ? null : manifest.path("blockedReason").asText(null));
    }
    /** Bounded presentation only; the complete gap ledger remains in the sealed package. */
    public List<Map<String,Object>> warningDetails(Path directory,Runnable check) throws IOException {
        List<Map<String,Object>> result=new ArrayList<>();
        try(var reader=Files.newBufferedReader(safeFile(directory,"snapshot/gaps.jsonl"))) {
            String line;
            while(result.size()<50 && (line=reader.readLine())!=null) {
                check.run(); JsonNode gap=json.readTree(line);
                String reason=gap.path("reason").asText(); int separator=reason.indexOf(':');
                result.add(Map.of("sourceId",gap.path("sourceId").asText(),
                        "originalPath",separator<0?"":reason.substring(separator+1),
                        "status",separator<0?reason:reason.substring(0,separator),"reason",reason,
                        "blocking",gap.path("blocking").asBoolean()));
            }
        }
        return List.copyOf(result);
    }
    /** Retry parsing only immutable raw copies after a materialization limit/configuration change. */
    public void recoverBlockedProjections(Path directory,Runnable check) throws IOException {
        requireOperationPath(directory);
        Path recovered=directory.resolve("work/recovered");
        Set<String> projected=new HashSet<>(); Map<String,FileEntry> rawFiles=new HashMap<>();
        Map<String,RecordEntry> blockedRecords=new LinkedHashMap<>();
        try(var records=Files.newBufferedReader(safeFile(directory,"snapshot/records.jsonl"))) {
            String line; while((line=records.readLine())!=null) {
                RecordEntry record=json.readValue(line,RecordEntry.class);
                if("blocked".equals(record.processingPolicy())) blockedRecords.put(record.recordRef(),record);
            }
        }
        try(var lines=Files.newBufferedReader(safeFile(directory,"snapshot/files.jsonl"))) {
            String line; while((line=lines.readLine())!=null) {
                FileEntry file=json.readValue(line,FileEntry.class);
                if(file.kind().equals("raw")) rawFiles.put(file.ref(),file);
                if(file.ref().contains(":recovered:p")) {
                    verifyRecoveredFile(directory,file,check);
                    projected.add(file.ref().split(":recovered:p",2)[0]);
                }
            }
        }
        try(var gaps=Files.newBufferedReader(safeFile(directory,"snapshot/gaps.jsonl"))) {
            String line; while((line=gaps.readLine())!=null) {
                JsonNode gap=json.readTree(line);
                if(!gap.path("blocking").asBoolean()) continue;
                String reason=gap.path("reason").asText();
                String prefix="RECORD_REQUIRES_HANDLING:";
                // Only an explicitly blocked raw record can be retried. An archived checkpoint,
                // malformed attachment or unbound gap must not disappear behind an empty seal.
                if(!reason.startsWith(prefix) || !blockedRecords.containsKey(reason.substring(prefix.length())))
                    throw new IOException("RECORD_REQUIRES_HANDLING");
            }
        }
        // Completed derived projections are immutable too. Reuse them when resuming model work.
        if(Files.exists(recovered.resolve("seal.json"))) {
            for(Path catalog:projectionCatalogs(directory)) try(var lines=Files.newBufferedReader(catalog)) {
                String line; while((line=lines.readLine())!=null) {
                    FileEntry file=json.readValue(line,FileEntry.class);
                    if(file.ref().contains(":recovered:p")) {
                        verifyRecoveredFile(directory,file,check);
                        projected.add(file.ref().split(":recovered:p",2)[0]);
                    }
                }
            }
            if(!projected.containsAll(blockedRecords.keySet())) throw new IOException("RECORD_REQUIRES_HANDLING");
            return;
        }
        Files.createDirectories(recovered.getParent()); deleteTree(recovered);
        try(var writer=new SnapshotWriter(recovered,check)) {
            for(RecordEntry record:blockedRecords.values()) {
                check.run(); if(projected.contains(record.recordRef())) continue;
                FileEntry raw=rawFiles.get(record.rawRef());
                if(raw==null) throw new IOException("MERGE_RAW_RECORD_MISSING");
                Path original=safeFile(directory,raw.path());
                if(Files.size(original)>maxRecordMaterializeBytes) throw new IOException("RECORD_REQUIRES_HANDLING");
                JsonNode node;
                try { node=writer.parser.readTree(original.toFile()); }
                catch(IOException invalid) { throw new IOException("RECORD_REQUIRES_HANDLING",invalid); }
                // The sealed original remains unchanged; the derived text has a stable reference back to it.
                try { writer.recoverProjection(record,node); }
                catch(IOException invalid) {
                    if(writer.parseFailure(invalid)) throw new IOException("RECORD_REQUIRES_HANDLING",invalid);
                    throw invalid;
                }
                projected.add(record.recordRef());
            }
            writer.closeCatalogs();
        }
        Path catalog=recovered.resolve("files.jsonl"); Path adjusted=recovered.resolve("projection-files.jsonl");
        var budget=textBudget(directory);
        try(var reader=Files.newBufferedReader(catalog); var out=new java.io.BufferedWriter(new java.io.OutputStreamWriter(budget.output(adjusted),StandardCharsets.UTF_8))) {
            String line; while((line=reader.readLine())!=null) {
                FileEntry e=json.readValue(line,FileEntry.class);
                FileEntry derived=new FileEntry(e.ref(),e.recordRef(),e.sourceId(),e.part(),"work/recovered/"+e.path().substring("snapshot/".length()),e.bytes(),e.sha256(),e.kind());
                out.write(json.writeValueAsString(derived)); out.newLine();
            }
        }
        if(!projected.containsAll(blockedRecords.keySet())) throw new IOException("RECORD_REQUIRES_HANDLING");
        budget.atomicWrite(recovered.resolve("seal.json"),json.writeValueAsString(Map.of("catalogHash",hash(adjusted,check))));
    }
    private void verifyRecoveredFile(Path directory,FileEntry file,Runnable check) throws IOException {
        Path path=safeFile(directory,file.path());
        if(!"text".equals(file.kind()) || Files.size(path)!=file.bytes() || !hash(path,check).equals(file.sha256()))
            throw new IOException("MERGE_RECOVERED_HASH_MISMATCH");
    }
    public static List<Path> projectionCatalogs(Path directory) throws IOException {
        List<Path> result=new ArrayList<>(); result.add(safeFile(directory,"snapshot/files.jsonl"));
        Path seal=directory.resolve("work/recovered/seal.json");
        if(Files.exists(seal,LinkOption.NOFOLLOW_LINKS)) {
            Path catalog=safeFile(directory,"work/recovered/projection-files.jsonl");
            JsonNode node=new ObjectMapper().readTree(Files.readString(safeFile(directory,"work/recovered/seal.json")));
            if(!hash(catalog,()->{}).equals(node.path("catalogHash").asText())) throw new IOException("MERGE_RECOVERED_HASH_MISMATCH");
            result.add(catalog);
        }
        return result;
    }
    public static Path safeFile(Path root, String relative) throws IOException {
        Path rel;
        try { rel=Path.of(relative); } catch (RuntimeException invalid) { throw new IOException("MERGE_INVALID_REF",invalid); }
        if (rel.isAbsolute() || rel.getNameCount()==0 || rel.normalize().startsWith("..")) throw new IOException("MERGE_INVALID_REF");
        Path base=root.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(base)) throw new IOException("MERGE_INVALID_REF");
        Path candidate=base.resolve(rel).normalize();
        if (!candidate.startsWith(base)) throw new IOException("MERGE_INVALID_REF");
        Path current=base;
        for (Path part : base.relativize(candidate)) {
            current=current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("MERGE_INVALID_REF");
        }
        if (!Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS)) throw new IOException("MERGE_REF_MISSING");
        return candidate;
    }
    private void requireOperationPath(Path directory) throws IOException {
        Path path=directory.toAbsolutePath().normalize();
        if (!Objects.equals(path.getParent(),packageRoot()) || !path.getFileName().toString().matches("[0-9a-fA-F-]{36}")
                || Files.isSymbolicLink(path) || Files.isSymbolicLink(packageRoot())) throw new IOException("INVALID_PACKAGE_PATH");
    }
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root,LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(root)) throw new IOException("INVALID_PACKAGE_PATH");
        Files.walkFileTree(root,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path path,java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException error) throws IOException {
                if (error!=null) throw error; Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
    private String messageVersion(String role,JsonNode content,String stop,JsonNode metadata) {
        var semantic=json.createObjectNode();
        semantic.put("role",role); semantic.set("content",content); semantic.put("stopReason",stop);
        var meta=metadata!=null && metadata.isObject()
                ? (com.fasterxml.jackson.databind.node.ObjectNode)metadata.deepCopy() : json.createObjectNode();
        meta.remove("handoffOrigin"); semantic.set("metadata",meta);
        return sha256(semantic.toString());
    }
    /** Older checkpoints stored some tool results beside, rather than inside, content. */
    private JsonNode checkpointContent(JsonNode message) throws IOException {
        JsonNode content=message.get("content");
        if(content==null || !(content.isArray() || content.isTextual())
                || !Set.of("user","assistant","system").contains(message.path("type").asText()))
            throw new IOException("CHECKPOINT_UNREADABLE");
        if(!message.hasNonNull("toolUseResult")) return content;
        var blocks=json.createArrayNode();
        if(content.isArray()) blocks.addAll((com.fasterxml.jackson.databind.node.ArrayNode)content);
        else blocks.add(json.createObjectNode().put("type","text").put("text",content.asText()));
        var result=json.createObjectNode().put("type","tool_result")
                .put("tool_use_id",message.path("sourceToolAssistantUUID").asText());
        result.set("content",message.get("toolUseResult"));
        blocks.add(result);
        return blocks;
    }
    private final class SnapshotWriter implements AutoCloseable {
        final Path root; final Runnable check; final MergeTextBudget budget; final CopyBudget copyBudget;
        final BufferedWriter records,files,occurrences,gaps;
        final Set<String> recordRefs=new HashSet<>(),fileRefs=new HashSet<>(),seenAssets=new HashSet<>();
        final ObjectMapper parser;
        long recordCount,fileCount,copiedCount,warningCount; String blockedReason; boolean catalogsClosed;
        SnapshotWriter(Path root,Runnable check) throws IOException {
            this.root=root; this.check=check; budget=textBudget(root); copyBudget=new CopyBudget(root);
            for (String name : List.of("raw","text","assets")) Files.createDirectories(root.resolve(name));
            records=writer("records.jsonl"); files=writer("files.jsonl"); occurrences=writer("occurrences.jsonl"); gaps=writer("gaps.jsonl");
            parser=json.copy(); parser.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                    .maxStringLength(maxRecordMaterializeBytes).build());
        }
        BufferedWriter writer(String name) throws IOException { return new BufferedWriter(new OutputStreamWriter(budget.output(root.resolve(name),StandardOpenOption.CREATE_NEW),StandardCharsets.UTF_8)); }
        void line(BufferedWriter writer,Object value) throws IOException { writer.write(json.writeValueAsString(value)); writer.newLine(); }
        void gap(String source,String reason,boolean blocked) throws IOException {
            warningCount++; if (blocked) blockedReason=reason;
            line(gaps,Map.of("sourceId",source,"reason",reason,"blocking",blocked));
        }
        void file(String ref,String recordRef,String source,Path path,String kind,int part) throws IOException {
            if (!fileRefs.add(ref)) return;
            line(files,new FileEntry(ref,recordRef,source,part,"snapshot/"+root.relativize(path),Files.size(path),hash(path,check),kind)); fileCount++;
        }
        String record(Origin origin,String role,String created,Path raw,String policy) throws IOException {
            String ref="r_"+digest(json.writeValueAsString(origin));
            line(occurrences,Map.of("recordRef",ref,"sourceId",origin.sessionId(),"recordId",origin.recordId(),"createdAt",Objects.toString(created,"")));
            if (!recordRefs.add(ref)) { Files.deleteIfExists(raw); return null; }
            Path destination=root.resolve("raw/"+ref+".json"); Files.move(raw,destination);
            line(records,new RecordEntry(ref,origin,role,created,ref+":raw",policy)); recordCount++;
            file(ref+":raw",ref,origin.sessionId(),destination,"raw",0);
            return ref;
        }
        Path rawRow(Map<String,Object> row) throws IOException {
            Path raw=root.resolve("raw/tmp-"+UUID.randomUUID()+".json");
            budget.write(raw,json.writeValueAsString(row),StandardOpenOption.CREATE_NEW); return raw;
        }
        Path rawColumns(String table,Map<String,Object> header,List<String> columns) throws IOException {
            return rawColumns(table,"id",header,columns);
        }
        Path rawColumns(String table,String idColumn,Map<String,Object> header,List<String> columns) throws IOException {
            Path raw=root.resolve("raw/tmp-"+UUID.randomUUID()+".json");
            try (var out=budget.output(raw,StandardOpenOption.CREATE_NEW); var generator=json.getFactory().createGenerator(out)) {
                generator.writeStartObject();
                for (var entry:header.entrySet()) generator.writeObjectField(entry.getKey(),entry.getValue());
                for (String column:columns) {
                    if (jdbc.queryForObject("SELECT "+column+" IS NULL FROM "+table+" WHERE "+idColumn+"=?",Integer.class,header.get(idColumn))==1) {
                        generator.writeNullField(column); continue;
                    }
                    check.run(); generator.writeFieldName(column);
                    try (Reader reader=columnReader(table,column,idColumn,header.get(idColumn).toString())) { generator.writeString(reader,-1); }
                }
                generator.writeEndObject();
            }
            return raw;
        }
        JsonNode parse(Path raw,String source,String recordId,String kind,String role,String created) throws IOException {
            try {
                if (Files.size(raw)>maxRecordMaterializeBytes) throw new IOException("record materialization limit");
                return parser.readTree(raw.toFile());
            } catch (IOException invalid) {
                String ref=record(new Origin(source,recordId,"raw-"+hash(raw,check),kind),role,created,raw,"blocked");
                gap(source,"RECORD_REQUIRES_HANDLING"+(ref==null ? "" : ":"+ref),true); return null;
            }
        }
        void recoverProjection(RecordEntry record,JsonNode node) throws IOException {
            var assets=new ArrayList<Asset>();
            String source=record.origin().sessionId();
            try(var out=new TextParts(record.recordRef()+":recovered",source)) {
                out.append("Recovered immutable raw ref="+record.rawRef()+"; source="+source+"\n");
                if(node.has("content_json")) {
                    out.append("消息 "+record.origin().recordId()+"; "+node.path("role").asText()
                            +"; "+node.path("created_at").asText()+"; stop="+node.path("stop_reason").asText()+"\n");
                    JsonNode content=parser.readTree(node.path("content_json").asText());
                    if(content==null || !(content.isArray() || content.isTextual())) throw new IOException("RECORD_REQUIRES_HANDLING");
                    render(content,out,source,assets,new HashSet<>(),new LinkedHashSet<>());
                    if(node.hasNonNull("meta_json") && !node.path("meta_json").asText().isBlank())
                        out.append("\n消息元数据: "+parser.readTree(node.path("meta_json").asText()));
                } else if(node.has("messages_json")) {
                    JsonNode messages=parser.readTree(node.path("messages_json").asText());
                    if(messages==null || !messages.isArray()) throw new IOException("RECORD_REQUIRES_HANDLING");
                    for(JsonNode message:messages) {
                        out.append("\n消息 "+message.path("uuid").asText()+"; "+message.path("type").asText()
                                +"; stop="+message.path("stopReason").asText(message.path("stop_reason").asText())+"\n");
                        render(checkpointContent(message),out,source,assets,new HashSet<>(),new LinkedHashSet<>());
                        JsonNode meta=message.path("meta").isObject()?message.path("meta"):message.path("metadata");
                        if(meta.isObject()) out.append("\n消息元数据: "+meta);
                    }
                } else out.append(node.toString());
            }
            // Recovery never reads live source files. Embedded bytes are already in the sealed raw.
            for(Asset asset:assets) if("copied".equals(asset.status())) exportAsset(asset);
            if(blockedReason!=null) throw new IOException("RECORD_REQUIRES_HANDLING");
        }
        void source(String source) throws IOException {
            SessionInfo info=sessionInfo(source);
            var cutoff=jdbc.queryForMap("SELECT COALESCE(MAX(seq_num),-1) AS lastSeq,COUNT(*) AS messages FROM messages WHERE session_id=?",source);
            long lastSeq=((Number)cutoff.get("lastSeq")).longValue();
            Path meta=rawRow(Map.of("sourceId",source,"title",Objects.toString(info.title(),""),"workingDirectory",info.workingDir(),
                    "model",info.model(),"metadata",info.metadata(),"messageCutoff",cutoff,"capturedAt",Instant.now().toString()));
            String metaRef=record(new Origin(source,source,hash(meta,check),"session"),"reference",null,meta,"extract");
            if (metaRef!=null) text(metaRef,source,"来源 "+source+"; 工程目录为共享外部引用: "+info.workingDir()+"; 标题: "+info.title());
            importPrevious(source);
            var assets=new ArrayList<Asset>(); var calls=new HashSet<String>(); var outputs=new LinkedHashSet<Path>();
            long sequence=-1;
            while (true) {
                check.run(); var rows=jdbc.queryForList("SELECT id,seq_num,role,created_at,stop_reason FROM messages WHERE session_id=? AND seq_num>? AND seq_num<=? ORDER BY seq_num LIMIT 1",source,sequence,lastSeq);
                if (rows.isEmpty()) break;
                var row=rows.getFirst(); sequence=((Number)row.get("seq_num")).longValue();
                Path raw=rawColumns("messages",row,List.of("content_json","meta_json"));
                JsonNode parsed=parse(raw,source,row.get("id").toString(),"message",row.get("role").toString(),(String)row.get("created_at"));
                if (parsed==null) continue;
                try {
                    JsonNode content=parser.readTree(parsed.path("content_json").asText());
                    String metaText=parsed.path("meta_json").asText();
                    JsonNode metadata=metaText.isBlank() ? json.createObjectNode() : parser.readTree(metaText);
                    exportMessage(source,row.get("id").toString(),(String)row.get("role"),(String)row.get("created_at"),
                            (String)row.get("stop_reason"),content,metadata,raw,assets,calls,outputs);
                } catch (IOException invalid) {
                    if (!parseFailure(invalid)) throw invalid;
                    String ref=Files.exists(raw) ? record(new Origin(source,row.get("id").toString(),"raw-"+hash(raw,check),"message"),"reference",null,raw,"blocked") : null;
                    gap(source,"RECORD_REQUIRES_HANDLING"+(ref==null?"":":"+ref),true);
                }
            }
            if(!cutoff.equals(jdbc.queryForMap("SELECT COALESCE(MAX(seq_num),-1) AS lastSeq,COUNT(*) AS messages FROM messages WHERE session_id=?",source))) throw new IOException("SOURCE_CHANGED");
            if (info.metadata().path("history_storage_version").asInt()!=2) exportCheckpoints(source,assets,calls,outputs);
            exportSimpleRows(source,"run_envelopes","id", "session_id",source);
            if (tableExists("artifact_manifests")) {
                String last="";
                while (true) {
                    var manifests=jdbc.queryForList("SELECT manifest_id FROM artifact_manifests WHERE session_id=? AND manifest_id>? ORDER BY manifest_id LIMIT 1",source,last);
                    if (manifests.isEmpty()) break;
                    var row=manifests.getFirst(); last=row.get("manifest_id").toString();
                    exportRow(source,"artifact_manifests","manifest_id",last);
                    exportSimpleRows(source,"artifact_entries","artifact_id","manifest_id",last);
                }
            }
            Path own=scratchpads.systemRoot().resolve(source);
            if (Files.exists(own,LinkOption.NOFOLLOW_LINKS)) copyTree(source,own,root.resolve("assets"),assets,seenAssets,check,copyBudget);
            for (Path swarm:swarms.ownedScratchpads(source)) copyTree(source,swarm,root.resolve("assets"),assets,seenAssets,check,copyBudget);
            for (Path swarm:swarms.ambiguousScratchpads(source)) gap(source,"ownership_unknown:"+swarm,false);
            for (var agent:agents.listForSession(source)) if (agent.outputFile()!=null) outputs.add(Path.of(agent.outputFile()));
            for (Path output:outputs) copyFile(source,output,root.resolve("assets"),assets,seenAssets,check,copyBudget);
            for (Asset asset:List.copyOf(assets)) {
                if ("external_reference".equals(asset.status()) && asset.originalPath().startsWith("/")) {
                    try {
                        Path path=Path.of(asset.originalPath()).toAbsolutePath().normalize();
                        Path real=canonicalReference(path);
                        if (!Files.isSymbolicLink(own) && path.startsWith(own.toAbsolutePath().normalize())
                                && real.startsWith(canonicalReference(own)) && !Files.isDirectory(real,LinkOption.NOFOLLOW_LINKS))
                            copyFile(source,real,root.resolve("assets"),assets,seenAssets,check,copyBudget);
                    } catch(InvalidPathException | IOException invalid) {
                        // Historical references are data; an invalid path remains an explicit gap.
                    }
                }
            }
            Set<String> accounted=new HashSet<>();
            for(Asset asset:assets) if(!"external_reference".equals(asset.status()) && asset.originalPath().startsWith("/")) {
                try { accounted.add(canonicalReference(Path.of(asset.originalPath())).toString()); }
                catch(InvalidPathException | IOException invalid) { /* Embedded assets have no source path. */ }
            }
            assets.removeIf(asset -> {
                if(!"external_reference".equals(asset.status()) || !asset.originalPath().startsWith("/")) return false;
                try { return accounted.contains(canonicalReference(Path.of(asset.originalPath())).toString()); }
                catch(InvalidPathException | IOException invalid) { return false; }
            });
            for (Asset asset:assets) exportAsset(asset);
        }
        boolean tableExists(String name) { return jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",Integer.class,name)==1; }
        boolean parseFailure(IOException failure) {
            return failure instanceof com.fasterxml.jackson.core.JsonProcessingException
                    || Set.of("SOURCE_MESSAGE_UNREADABLE","CHECKPOINT_UNREADABLE","SOURCE_TEXT_INVALID_UTF16")
                    .contains(Objects.toString(failure.getMessage(),""));
        }
        void simpleRecord(String source,String kind,String id,Map<String,Object> row) throws IOException {
            check.run(); Path raw=rawRow(row);
            String ref=record(new Origin(source,id,hash(raw,check),kind),"reference",Objects.toString(row.get("created_at"),null),raw,"extract");
            if (ref!=null) text(ref,source,json.writeValueAsString(row));
        }
        void exportSimpleRows(String source,String table,String idColumn,String filter,String value) throws IOException {
            String last="";
            while (true) {
                check.run(); var rows=jdbc.queryForList("SELECT "+idColumn+" FROM "+table+" WHERE "+filter+"=? AND "+idColumn+">? ORDER BY "+idColumn+" LIMIT 1",value,last);
                if (rows.isEmpty()) return; last=rows.getFirst().get(idColumn).toString(); exportRow(source,table,idColumn,last);
            }
        }
        void exportRow(String source,String table,String idColumn,String id) throws IOException {
            List<String> columns=jdbc.queryForList("PRAGMA table_info("+table+")").stream()
                    .map(row -> row.get("name").toString()).filter(name -> !name.equals(idColumn)).toList();
            Path raw=rawColumns(table,idColumn,Map.of(idColumn,id),columns);
            JsonNode node=parse(raw,source,id,table,"reference",null);
            if(node==null) return;
            String ref=record(new Origin(source,id,hash(raw,check),table),"reference",null,raw,"extract");
            if(ref!=null) text(ref,source,node.toString());
        }
        void exportMessage(String source,String id,String role,String created,String stop,JsonNode content,JsonNode meta,
                           Path raw,List<Asset> assets,Set<String> calls,Set<Path> outputs) throws IOException {
            if (content==null || !(content.isArray() || content.isTextual()) || !Set.of("user","assistant","system").contains(role))
                throw new IOException("SOURCE_MESSAGE_UNREADABLE");
            Origin origin;
            if (meta!=null && meta.path("handoffOrigin").isObject()) origin=json.treeToValue(meta.get("handoffOrigin"),Origin.class);
            else origin=new Origin(source,id,messageVersion(role,content,stop,meta),
                    meta!=null && meta.has("sessionMergeOperationId") ? "derived_context" : "message");
            String ref=record(origin,role,created,raw,"extract"); if (ref==null) return;
            try (var projection=new TextParts(ref,source)) {
                projection.append("来源 "+source+"; 消息 "+id+"; "+role+"; "+Objects.toString(created,"")+"; stop="+Objects.toString(stop,"")+"\n");
                render(content,projection,source,assets,calls,outputs);
                if (meta!=null) projection.append("\n消息元数据: "+meta);
            }
        }
        void render(JsonNode content,TextParts out,String source,List<Asset> assets,Set<String> calls,Set<Path> outputs) throws IOException {
            if (content.isTextual()) { out.append(content.asText()); return; }
            if (!content.isArray()) { out.append(content.toString()); return; }
            for (JsonNode block:content) {
                check.run(); String type=block.path("type").asText();
                if (Set.of("thinking","redacted_thinking","provider_response_state").contains(type)) continue;
                try { collectAgentOutput(block,calls,outputs); }
                catch(InvalidPathException invalid) { gap(source,"invalid_path:历史 Agent 产物路径无法解析，原文已保留",false); }
                collectReferences(block,source,assets,seenAssets);
                switch(type) {
                    case "text" -> out.append(block.path("text").asText());
                    case "tool_use" -> out.append("\n工具调用 "+block.path("name").asText()+" · "+block.path("id").asText()+"\n"+block.path("input"));
                    case "tool_result" -> {
                        out.append("\n工具结果 "+toolUseId(block)+" · is_error="+(block.path("is_error").asBoolean() || block.path("isError").asBoolean())+"\n");
                        render(block.path("content"),out,source,assets,calls,outputs); out.append("\nmetadata="+block.path("metadata"));
                    }
                    case "image" -> {
                        String data=block.path("base64Data").asText(block.path("source").path("data").asText());
                        if (!data.isBlank()) {
                            Path image=root.resolve("assets/"+UUID.randomUUID());
                            try {
                                byte[] bytes=Base64.getDecoder().decode(data); copyBudget.check(bytes.length); copyBudget.written+=bytes.length;
                                try(var stream=budget.output(image,StandardOpenOption.CREATE_NEW)) { stream.write(bytes); }
                                assets.add(new Asset(source,"embedded:"+out.recordRef+":"+assets.size(),image.toString(),"copied",null,bytes.length,hash(image,check)));
                            } catch (IllegalArgumentException invalid) { gap(source,"RECORD_REQUIRES_HANDLING:invalid_image",true); }
                        }
                        out.append("\n[历史图片原件见附件目录，尚未解释其视觉内容]\n");
                    }
                    default -> out.append(block.toString());
                }
                out.append("\n");
            }
        }
        void exportCheckpoints(String source,List<Asset> assets,Set<String> calls,Set<Path> outputs) throws IOException {
            if (!tableExists("agent_checkpoints")) return;
            String last=""; boolean any=false;
            while (true) {
                check.run(); var rows=jdbc.queryForList("SELECT id,run_id,seq,created_at FROM agent_checkpoints WHERE session_id=? AND id>? ORDER BY id LIMIT 1",source,last);
                if (rows.isEmpty()) break; any=true; var row=rows.getFirst(); last=row.get("id").toString();
                Path raw=rawColumns("agent_checkpoints",row,List.of("messages_json"));
                JsonNode parsed=parse(raw,source,last,"checkpoint","reference",(String)row.get("created_at"));
                if(parsed==null) continue;
                try {
                    JsonNode messages=parser.readTree(parsed.path("messages_json").asText());
                    if (messages==null || !messages.isArray()) throw new IOException("CHECKPOINT_UNREADABLE");
                    int position=0;
                    for (JsonNode message:messages) {
                        position++; String id=message.path("uuid").asText();
                        if (id.isBlank()) id="inferred-"+digest(message.toString());
                        line(occurrences,Map.of("sourceId",source,"checkpointId",last,"position",position,"recordId",id,"identityInferred",id.startsWith("inferred-")));
                        Path messageRaw=rawRow(Map.of("checkpointId",last,"position",position,"message",message));
                        JsonNode meta=message.path("meta"); if (!meta.isObject()) meta=message.path("metadata");
                        exportMessage(source,id,message.path("type").asText(),message.path("timestamp").asText(),
                                message.path("stopReason").asText(message.path("stop_reason").asText(null)),checkpointContent(message),meta,messageRaw,assets,calls,outputs);
                    }
                    record(new Origin(source,last,hash(raw,check),"checkpoint"),"reference",(String)row.get("created_at"),raw,"archive");
                } catch (IOException invalid) {
                    if (!parseFailure(invalid)) throw invalid;
                    String container=record(new Origin(source,last,"raw-"+hash(raw,check),"checkpoint"),"reference",(String)row.get("created_at"),raw,"blocked");
                    gap(source,"RECORD_REQUIRES_HANDLING:"+Objects.toString(container,last),true);
                }
            }
            if (any) gap(source,"legacy_history:checkpoint 可能已经裁剪，缺失历史无法恢复",false);
            else {
                JsonNode metadata=sessionInfo(source).metadata();
                if(metadata.has("parent_session_id") || "subagent".equals(metadata.path("type").asText()))
                    gap(source,"legacy_history:子任务未保存 checkpoint，未持久化的过程无法恢复",false);
            }
        }
        void exportAsset(Asset asset) throws IOException {
            if ("copy_failed".equals(asset.status())) throw new IOException("MERGE_COPY_INCOMPLETE");
            if (!"copied".equals(asset.status())) { gap(asset.sourceSessionId(),asset.status()+":"+asset.originalPath(),false); return; }
            Path path=Path.of(asset.copiedPath());
            String id=digest(asset.sourceSessionId()+":"+asset.originalPath()+":"+asset.sha256());
            Path destination=root.resolve("assets/a_"+id);
            if (!path.equals(destination)) Files.move(path,destination,StandardCopyOption.REPLACE_EXISTING);
            Path raw=rawRow(Map.of("originalPath",asset.originalPath(),"sha256",asset.sha256(),"bytes",asset.size()));
            String ref=record(new Origin(asset.sourceSessionId(),asset.originalPath(),asset.sha256(),"asset"),"reference",null,raw,"extract");
            if (ref==null) return;
            file("a_"+id,ref,asset.sourceSessionId(),destination,"asset",0); copiedCount++;
            text(ref,asset.sourceSessionId(),"资料原件 a_"+id+"; 原路径: "+asset.originalPath());
            String lower=asset.originalPath().toLowerCase(Locale.ROOT);
            if (lower.matches(".*\\.(txt|md|json|jsonl|log|csv|yaml|yml|xml|html|java|ts|tsx|js|py|sh)$")) {
                try(var reader=Files.newBufferedReader(destination,StandardCharsets.UTF_8); var out=new TextParts(ref+"-body",asset.sourceSessionId())) {
                    out.recordRef=ref; char[] buffer=new char[4096]; int count;
                    while((count=reader.read(buffer))!=-1) {
                        String chunk=new String(buffer,0,count);
                        if (count>0 && Character.isHighSurrogate(chunk.charAt(count-1))) {
                            int low=reader.read(); if(low<0 || !Character.isLowSurrogate((char)low)) throw new IOException("INVALID_UTF16"); chunk+=(char)low;
                        }
                        out.append(chunk);
                    }
                } catch(java.nio.charset.CharacterCodingException invalid) { gap(asset.sourceSessionId(),"RECORD_REQUIRES_HANDLING:"+ref,true); }
            } else gap(asset.sourceSessionId(),"attachment_uninterpreted:"+ref,false);
        }
        void importPrevious(String source) throws IOException {
            if (!jdbc.queryForList("PRAGMA table_info(session_merges)").stream().anyMatch(row -> "protocol_version".equals(row.get("name")))) return;
            var rows=jdbc.queryForList("SELECT package_path,snapshot_hash,handoff_hash,protocol_version FROM session_merges WHERE target_session_id=? AND status='completed'",source);
            if (rows.isEmpty()) return;
            Path previous=Path.of(rows.getFirst().get("package_path").toString());
            if(((Number)rows.getFirst().get("protocol_version")).intValue()==1) {
                if(!Files.isDirectory(previous,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(previous)) {
                    gap(source,"legacy_package_missing",true); return;
                }
                // Legacy packages lack canonical origin IDs; retain every available file and state this limitation.
                gap(source,"legacy_package_origin_inferred",false);
                try(var paths=Files.walk(previous)) {
                    var iterator=paths.iterator();
                    while(iterator.hasNext()) {
                        check.run(); Path original=iterator.next();
                        if(Files.isSymbolicLink(original)) { gap(source,"legacy_symlink_not_copied:"+original,true); continue; }
                        if(!Files.isRegularFile(original,LinkOption.NOFOLLOW_LINKS)) continue;
                        safeFile(previous,previous.relativize(original).toString());
                        var copies=new ArrayList<Asset>();
                        copyFile(source,original,root.resolve("assets"),copies,seenAssets,check,copyBudget);
                        for(var asset:copies) exportAsset(asset);
                    }
                }
                return;
            }
            validateSnapshot(previous,rows.getFirst().get("snapshot_hash").toString(),check);
            if(Files.exists(previous.resolve("work/recovered/seal.json")))
                HandoffReadService.verifyPrepared(previous,rows.getFirst().get("snapshot_hash").toString(),
                        Objects.toString(rows.getFirst().get("handoff_hash"),""),json);
            try(var reader=Files.newBufferedReader(safeFile(previous,"snapshot/records.jsonl"))) {
                String line;
                while((line=reader.readLine())!=null) {
                    check.run(); RecordEntry entry=json.readValue(line,RecordEntry.class);
                    if (recordRefs.add(entry.recordRef())) { line(records,entry); recordCount++; }
                    else line(occurrences,Map.of("recordRef",entry.recordRef(),"importedFrom",source));
                }
            }
            for(Path previousCatalog:projectionCatalogs(previous)) try(var reader=Files.newBufferedReader(previousCatalog)) {
                String line;
                while((line=reader.readLine())!=null) {
                    check.run(); FileEntry entry=json.readValue(line,FileEntry.class);
                    if(!fileRefs.add(entry.ref())) continue;
                    Path original=safeFile(previous,entry.path());
                    Path relative=Path.of(entry.path());
                    if (!relative.startsWith("snapshot")) {
                        relative=Path.of("snapshot/recovered/"+sha256(entry.ref())+".txt");
                        entry=new FileEntry(entry.ref(),entry.recordRef(),entry.sourceId(),entry.part(),relative.toString(),entry.bytes(),entry.sha256(),entry.kind());
                    }
                    Path destination=root.resolve(Path.of("snapshot").relativize(relative));
                    Files.createDirectories(destination.getParent());
                    copyBudget.check(entry.bytes());
                    try(var input=Files.newInputStream(original,LinkOption.NOFOLLOW_LINKS); var output=budget.output(destination,StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer=new byte[65536];int count;
                        while((count=input.read(buffer))!=-1) { check.run();copyBudget.check(count);copyBudget.written+=count;output.write(buffer,0,count); }
                    }
                    if (!hash(destination,check).equals(entry.sha256())) throw new IOException("MERGE_SNAPSHOT_CORRUPT");
                    line(files,entry);fileCount++; if("asset".equals(entry.kind())) copiedCount++;
                }
            }
            for(String name:List.of("occurrences.jsonl","gaps.jsonl")) {
                try(var reader=Files.newBufferedReader(safeFile(previous,"snapshot/"+name))) {
                    String line;
                    while((line=reader.readLine())!=null) {
                        check.run(); if ("gaps.jsonl".equals(name)) {
                            JsonNode gap=json.readTree(line); warningCount++;
                            if(gap.path("blocking").asBoolean()) blockedReason=gap.path("reason").asText();
                        }
                        var output="gaps.jsonl".equals(name)?gaps:occurrences;output.write(line);output.newLine();
                    }
                }
            }
        }
        void text(String ref,String source,String text) throws IOException { try(var out=new TextParts(ref,source)) { out.append(text); } }
        final class TextParts implements AutoCloseable {
            final String ref,source; String recordRef; final StringBuilder text=new StringBuilder(); int bytes,part;
            TextParts(String ref,String source) { this.ref=ref;this.recordRef=ref;this.source=source; }
            void append(String value) throws IOException {
                for(int i=0;i<value.length();) {
                    if((i&4095)==0) check.run(); int cp=value.codePointAt(i);
                    if(cp>=0xD800 && cp<=0xDFFF) throw new IOException("SOURCE_TEXT_INVALID_UTF16");
                    int width=cp<=127?1:cp<=2047?2:cp<=65535?3:4;
                    if(bytes+width>32768) flush(); text.appendCodePoint(cp);bytes+=width;i+=Character.charCount(cp);
                }
            }
            void flush() throws IOException {
                if(text.isEmpty()) return;
                // Keep logical refs unchanged; Windows filenames cannot contain the recovery colon.
                String fileRef=ref.replace(":recovered","-recovered");
                Path path=root.resolve("text/"+fileRef+"-"+part+".txt"); budget.write(path,text,StandardOpenOption.CREATE_NEW);
                file(ref+":p"+part,recordRef,source,path,"text",part);part++;text.setLength(0);bytes=0;
            }
            @Override public void close() throws IOException { flush(); }
        }
        void closeCatalogs() throws IOException {
            if(!catalogsClosed) { catalogsClosed=true; records.close();files.close();occurrences.close();gaps.close(); }
        }
        @Override public void close() throws IOException { closeCatalogs(); }
    }

    public Bundle build(Path directory, List<String> roots, Runnable check) throws IOException {
        if (Files.exists(directory)) throw new IOException("PACKAGE_ALREADY_EXISTS");
        Files.createDirectories(directory);
        var budget = new CopyBudget(directory);
        var textBudget = new MergeTextBudget(minFreeBytes, () -> availableBytes(directory));
        var transcripts = new ArrayList<Path>();
        var sources = new ArrayList<Source>();
        var assets = new ArrayList<Asset>();
        var seen = new HashSet<String>();
        for (String id : descendants(roots)) {
            check.run();
            if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("INVALID_SOURCE_ID");
            SessionInfo session = sessionInfo(id);
            long expected = jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE session_id=?", Long.class, id);
            int exported = 0;
            String last = "";
            Path sourceDir = directory.resolve("from-" + id);
            Files.createDirectories(sourceDir);
            assets.add(new Asset(id, session.workingDir(), null, "external_reference", "工程代码保留原路径，不是文件快照", 0, null));
            var backgroundCalls = new HashSet<String>();
            var outputPaths = new LinkedHashSet<Path>();
            boolean historicalSwarm;
            try (var text = new PartWriter(sourceDir, "transcript", check, textBudget)) {
                text.append("来源会话: ").append(id).append("\n标题: ").append(session.title())
                        .append("\n工程目录（外部引用）: ").append(session.workingDir()).append("\n快照时间: ").append(Instant.now()).append('\n');
                long sequence = -1;
                while (true) {
                    check.run();
                    // Keyset pagination releases the read connection before filesystem work.
                    var rows = jdbc.queryForList("""
                            SELECT id,seq_num,role,created_at,stop_reason,
                              CASE WHEN length(CAST(content_json AS BLOB)) + COALESCE(length(CAST(meta_json AS BLOB)),0) <= ? THEN content_json END AS content_json,
                              CASE WHEN length(CAST(content_json AS BLOB)) + COALESCE(length(CAST(meta_json AS BLOB)),0) <= ? THEN meta_json END AS meta_json
                            FROM messages WHERE session_id=? AND seq_num>? ORDER BY seq_num LIMIT 1
                            """, MAX_RECORD_BYTES, MAX_RECORD_BYTES, id, sequence);
                    if (rows.isEmpty()) break;
                    var row = rows.getFirst();
                    if (row.get("content_json") == null) throw new IOException("SOURCE_RECORD_TOO_LARGE_OR_NULL");
                    JsonNode content;
                    try { content = json.readTree(row.get("content_json").toString()); }
                    catch (IOException unreadable) { throw new IOException("SOURCE_MESSAGE_UNREADABLE: " + row.get("id"), unreadable); }
                    if (content == null || !(content.isArray() || content.isTextual())) throw new IOException("SOURCE_MESSAGE_UNREADABLE: " + row.get("id"));
                    if (!Set.of("user", "assistant", "system").contains(Objects.toString(row.get("role"))))
                        throw new IOException("SOURCE_MESSAGE_UNREADABLE: " + row.get("id"));
                    text.append("\n## 消息 ").append(row.get("seq_num")).append(" · ").append(row.get("id"))
                            .append(" · ").append(row.get("role")).append(" · ").append(row.get("created_at")).append('\n');
                    if (row.get("stop_reason") != null)
                        text.append("终止原因 (stop_reason): ").append(row.get("stop_reason")).append('\n');
                    if (content.isArray()) for (JsonNode block : content) {
                        renderBlock(block, text, id, sourceDir, assets, seen, check, budget);
                        collectAgentOutput(block, backgroundCalls, outputPaths);
                    }
                    else text.append(content.asText()).append('\n');
                    if (row.get("meta_json") != null) text.append("消息元数据:\n").append(pretty(json.readTree(row.get("meta_json").toString()))).append('\n');
                    collectReferences(content, id, assets, seen);
                    sequence = ((Number) row.get("seq_num")).longValue();
                    last = row.get("id").toString(); exported++;
                }
                if (exported != expected) throw new IOException("SOURCE_MESSAGE_UNREADABLE: " + id);
                // Keep every distinct checkpoint version, including messages trimmed from later snapshots.
                // Exact repeated messages point back to the first exported occurrence.
                var checkpointMessages = new HashMap<String, String>();
                String checkpointId = null;
                String checkpointRun = "";
                long checkpointSeq = -1;
                while (true) {
                    check.run();
                    var rows = jdbc.queryForList("""
                            SELECT id,run_id,seq,created_at,
                              CASE WHEN length(CAST(messages_json AS BLOB)) <= ? THEN messages_json END AS messages_json
                            FROM agent_checkpoints WHERE session_id=? AND (? IS NULL OR (run_id,seq,id)>(?,?,?)) ORDER BY run_id,seq,id LIMIT 1
                            """, MAX_RECORD_BYTES, id, checkpointId, checkpointRun, checkpointSeq, checkpointId);
                    if (rows.isEmpty()) break;
                    var checkpoint = rows.getFirst();
                    checkpointId = checkpoint.get("id").toString();
                    checkpointRun = checkpoint.get("run_id").toString();
                    checkpointSeq = ((Number) checkpoint.get("seq")).longValue();
                    if (checkpoint.get("messages_json") == null) throw new IOException("SOURCE_RECORD_TOO_LARGE_OR_NULL");
                    JsonNode messages = json.readTree(checkpoint.get("messages_json").toString());
                    if (messages == null || !messages.isArray()) throw new IOException("CHECKPOINT_UNREADABLE");
                    text.append("\n## 持久化检查点 ").append(checkpointId).append(" · run ").append(checkpoint.get("run_id"))
                            .append(" · seq ").append(checkpoint.get("seq")).append(" · ").append(checkpoint.get("created_at"))
                            .append("\n按 run、seq 导出；完全相同的消息引用首次出现位置。\n");
                    int position = 0;
                    for (JsonNode message : messages) {
                        check.run();
                        String location = checkpointId + " / " + (++position);
                        String fingerprint = digest(message.toString());
                        String previous = checkpointMessages.putIfAbsent(fingerprint, location);
                        if (previous != null) {
                            text.append("\n重复消息 ").append(location).append("：完整内容见检查点 ").append(previous).append('\n');
                            continue;
                        }
                        text.append("\n消息 ").append(location).append(" · ").append(message.path("uuid").asText()).append(" · ")
                                .append(message.path("type").asText()).append(" · ").append(message.path("timestamp").asText()).append('\n');
                        JsonNode stopReason = message.hasNonNull("stopReason") ? message.get("stopReason") : message.get("stop_reason");
                        if (stopReason != null && !stopReason.isNull())
                            text.append("终止原因 (stop_reason): ").append(stopReason.asText()).append('\n');
                        JsonNode content = message.path("content");
                        if (content.isArray()) {
                            for (JsonNode block : content) {
                                renderBlock(block, text, id, sourceDir, assets, seen, check, budget);
                                collectAgentOutput(block, backgroundCalls, outputPaths);
                            }
                        } else if (content.isTextual() && "system".equals(message.path("type").asText())) {
                            text.append(content.asText()).append('\n');
                        } else throw new IOException("CHECKPOINT_MESSAGE_UNREADABLE");
                        if (message.hasNonNull("metadata")) text.append("元数据: ").append(pretty(message.get("metadata"))).append('\n');
                        if (message.hasNonNull("meta")) text.append("元数据: ").append(pretty(message.get("meta"))).append('\n');
                        collectReferences(content, id, assets, seen);
                    }
                }
                exportRuns(id, text, check);
                text.finish();
                transcripts.addAll(text.paths());
                historicalSwarm = text.containsSwarm;
            }
            sources.add(new Source(id, session.title(), session.workingDir(), exported, last));
            Path own = scratchpads.systemRoot().resolve(id);
            if (Files.exists(own, LinkOption.NOFOLLOW_LINKS)) copyTree(id, own, sourceDir, assets, seen, check, budget);
            for (Path swarm : swarms.ownedScratchpads(id)) copyTree(id, swarm, sourceDir, assets, seen, check, budget);
            for (Path swarm : swarms.ambiguousScratchpads(id))
                assets.add(new Asset(id, swarm.toString(), null, "ownership_unknown", "Swarm 目录被复用或存在多来源归属，未复制", 0, null));
            for (var agent : agents.listForSession(id)) if (agent.outputFile() != null) outputPaths.add(Path.of(agent.outputFile()));
            for (Path output : outputPaths) copyFile(id, output, sourceDir, assets, seen, check, budget);
            // Explicit references within this source's scratchpad also expose files that disappeared.
            for (Asset reference : List.copyOf(assets)) {
                if (!id.equals(reference.sourceSessionId()) || !"external_reference".equals(reference.status())) continue;
                try {
                    if (!reference.originalPath().startsWith("/")) continue;
                    Path referenced = canonicalReference(Path.of(reference.originalPath()));
                    if (referenced.startsWith(own) && !Files.isDirectory(referenced, LinkOption.NOFOLLOW_LINKS))
                        copyFile(id, referenced, sourceDir, assets, seen, check, budget);
                } catch (InvalidPathException | IOException ignored) { }
            }
            if (historicalSwarm && swarms.ownedScratchpads(id).isEmpty())
                assets.add(new Asset(id, "历史 Swarm 临时目录", null, "ownership_unknown", "没有可核实的目录归属；未猜测复制", 0, null));
        }
        // Keep one authoritative status for a known file rather than both an external and copied entry.
        var accounted = new HashSet<String>();
        for (Asset asset : assets) if (!"external_reference".equals(asset.status()) && asset.originalPath().startsWith("/")) {
            try { accounted.add(asset.sourceSessionId() + ":" + canonicalReference(Path.of(asset.originalPath()))); }
            catch (IOException | InvalidPathException ignored) { }
        }
        assets.removeIf(asset -> {
            if (!"external_reference".equals(asset.status()) || !asset.originalPath().startsWith("/")) return false;
            try { return accounted.contains(asset.sourceSessionId() + ":" + canonicalReference(Path.of(asset.originalPath()))); }
            catch (IOException | InvalidPathException ignored) { return false; }
        });
        Path temporaryRoot = canonicalReference(Path.of(System.getProperty("java.io.tmpdir")));
        var sourceDirectories = new HashMap<String, Path>();
        for (Source source : sources) sourceDirectories.put(source.id(), canonicalReference(Path.of(source.workingDirectory())));
        for (int i = 0; i < assets.size(); i++) {
            Asset asset = assets.get(i);
            if (!"external_reference".equals(asset.status()) || !asset.originalPath().startsWith("/")) continue;
            try {
                Path reference = canonicalReference(Path.of(asset.originalPath()));
                if (!reference.startsWith(sourceDirectories.get(asset.sourceSessionId()))
                        && (reference.startsWith(temporaryRoot) || reference.startsWith(scratchpads.systemRoot())))
                    assets.set(i, new Asset(asset.sourceSessionId(), asset.originalPath(), null, "ownership_unknown",
                            "临时路径缺少可核实的来源归属，未复制", 0, null));
            } catch (IOException | InvalidPathException ignored) { }
        }
        Bundle bundle = new Bundle(directory, List.copyOf(transcripts), List.copyOf(sources), List.copyOf(assets), textBudget);
        writeIndex(bundle, check);
        return bundle;
    }

    private void exportRuns(String sessionId, PartWriter text, Runnable check) throws IOException {
        String lastId = "";
        while (true) {
            check.run();
            var rows = jdbc.queryForList("""
                    SELECT id,status,verification_status,exit_reason,abort_reason,
                           CASE WHEN length(CAST(error_summary AS BLOB)) <= ? THEN error_summary END AS error_summary,
                           COALESCE(length(CAST(error_summary AS BLOB)),0) AS error_bytes
                    FROM run_envelopes WHERE session_id=? AND id>? ORDER BY id LIMIT 1
                    """, MAX_RECORD_BYTES, sessionId, lastId);
            if (rows.isEmpty()) return;
            var row = rows.getFirst();
            if (((Number) row.remove("error_bytes")).longValue() > MAX_RECORD_BYTES)
                throw new IOException("SOURCE_RECORD_TOO_LARGE_OR_NULL");
            lastId = row.get("id").toString();
            text.append("\n## Run 持久化状态（包含未完成及失败事实）\n").append(json.writerWithDefaultPrettyPrinter().writeValueAsString(row)).append('\n');
        }
    }

    private void renderBlock(JsonNode block, PartWriter out, String id, Path sourceDir,
            List<Asset> assets, Set<String> seen, Runnable check, CopyBudget budget) throws IOException {
        String type = block.path("type").asText();
        switch (type) {
            case "provider_response_state" -> out.append(block.path("displayThinking").asText(block.path("display_thinking").asText())).append("\n[供应商私有续传状态未导出]\n");
            case "redacted_thinking" -> out.append("[不可读思考状态未导出]\n");
            case "text" -> out.append(block.path("text").asText()).append('\n');
            case "thinking" -> out.append("历史思考记录:\n").append(block.path("thinking").asText()).append('\n');
            case "tool_use" -> out.append("工具调用 ").append(block.path("name").asText()).append(" · ")
                    .append(block.path("id").asText()).append("\n输入:\n").append(pretty(block.path("input"))).append('\n');
            case "tool_result" -> out.append("工具结果 ").append(toolUseId(block))
                    .append(" · is_error=").append((block.path("is_error").asBoolean() || block.path("isError").asBoolean()))
                    .append('\n').append(block.path("content").isTextual() ? block.path("content").asText() : pretty(block.path("content"))).append("\n元数据: ")
                    .append(pretty(block.path("metadata"))).append('\n');
            case "image" -> {
                String data = block.path("base64Data").asText(block.path("source").path("data").asText());
                String url = block.path("url").asText(block.path("source").path("url").asText());
                if (!data.isBlank()) {
                    check.run();
                    String media = block.path("mediaType").asText(block.path("source").path("media_type").asText());
                    String ext = media.contains("jpeg") ? "jpg" : media.contains("webp") ? "webp" : "png";
                    Path target = sourceDir.resolve("image-" + assets.size() + "." + ext);
                    try {
                        byte[] decoded = Base64.getDecoder().decode(data);
                        budget.check(decoded.length);
                        budget.written += decoded.length;
                        Files.write(target, decoded);
                        assets.add(new Asset(id, "内嵌图片", target.toString(), "copied", null, Files.size(target), hash(target, check)));
                        out.append("图片副本（非文字识别）: ").append(target).append('\n');
                    } catch (IllegalArgumentException | IOException malformed) {
                        try { Files.deleteIfExists(target); } catch (IOException ignored) { }
                        assets.add(new Asset(id, "内嵌图片", null, "copy_failed", malformed instanceof CopyLimitException ? malformed.getMessage() : "图片解码或复制失败", 0, null));
                        out.append("[图片未复制]\n");
                    }
                } else out.append("图片外部引用: ").append(url).append('\n');
            }
            default -> out.append(pretty(block)).append('\n');
        }
    }
    private String pretty(JsonNode node) throws IOException { return json.writerWithDefaultPrettyPrinter().writeValueAsString(node); }
    private Path canonicalReference(Path path) throws IOException {
        Path current = path.toAbsolutePath().normalize();
        var suffix = new ArrayDeque<String>();
        while (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            suffix.addFirst(current.getFileName().toString()); current = current.getParent();
        }
        current = current.toRealPath();
        for (String segment : suffix) current = current.resolve(segment);
        return current;
    }
    private void collectReferences(JsonNode node, String id, List<Asset> assets, Set<String> seen) {
        if (Set.of("provider_response_state", "redacted_thinking").contains(node.path("type").asText())) return;
        if (node.isTextual()) {
            String value = node.asText();
            if ((value.startsWith("https://") || value.startsWith("http://") || value.startsWith("/"))
                    && value.length() < 4096 && !value.contains("\n") && seen.add("ref:" + id + ":" + value))
                assets.add(new Asset(id, value, null, "external_reference", "历史引用，需通过现有工具读取或访问", 0, null));
        } else if (node.isContainerNode()) node.forEach(child -> collectReferences(child, id, assets, seen));
    }
    private void copyTree(String id, Path root, Path target, List<Asset> assets, Set<String> seen, Runnable check, CopyBudget budget) {
        try {
            if (Files.isSymbolicLink(root)) {
                assets.add(new Asset(id, root.toString(), null, "ownership_unknown", "目录为符号链接，未扩展复制范围", 0, null));
                return;
            }
            try (var paths = Files.walk(root)) {
                var iterator = paths.iterator();
                while (iterator.hasNext()) {
                    check.run();
                    Path path = iterator.next();
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (Files.isSymbolicLink(path)) {
                        Path real = path.toRealPath();
                        if (!real.startsWith(root.toRealPath())) {
                            assets.add(new Asset(id, path.toString(), null, "external_reference", "链接指向复制范围外", 0, null));
                            continue;
                        }
                        copyFile(id, real, target, assets, seen, check, budget);
                    } else copyFile(id, path, target, assets, seen, check, budget);
                }
            }
        } catch (IOException | UncheckedIOException failure) {
            assets.add(new Asset(id, root.toString(), null, "copy_failed", "临时目录无法完整读取", 0, null));
        }
    }
    private void copyFile(String id, Path source, Path target, List<Asset> assets, Set<String> seen, Runnable check, CopyBudget budget) {
        if (!seen.add("copy:" + source.toAbsolutePath().normalize())) return;
        Path destination = target.resolve("files").resolve(String.format("%06d-", assets.size()) + source.getFileName());
        try {
            check.run();
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                assets.add(new Asset(id, source.toString(), null, Files.exists(source) ? "ownership_unknown" : "missing", "文件不存在或不是普通文件", 0, null));
                return;
            }
            long size = Files.size(source);
            budget.check(size);
            var modified = Files.getLastModifiedTime(source);
            String before = hash(source, check);
            Files.createDirectories(destination.getParent());
            try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS); OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) {
                    check.run(); budget.check(count);
                    budget.written += count; output.write(buffer, 0, count);
                }
            }
            String copied = hash(destination, check);
            if (size != Files.size(source) || !modified.equals(Files.getLastModifiedTime(source))
                    || !before.equals(copied) || !before.equals(hash(source, check))) throw new IOException("SOURCE_CHANGED");
            assets.add(new Asset(id, source.toString(), destination.toString(), "copied", null, size, copied));
        } catch (IOException failure) {
            try { Files.deleteIfExists(destination); } catch (IOException ignored) { }
            assets.add(new Asset(id, source.toString(), null, "copy_failed", failure instanceof CopyLimitException ? failure.getMessage() : "复制或一致性校验失败", 0, null));
        }
    }
    private void writeIndex(Bundle bundle, Runnable check) throws IOException {
        check.run();
        try (var output = bundle.textBudget.output(bundle.path.resolve("manifest.json"), StandardOpenOption.CREATE_NEW)) {
            json.writerWithDefaultPrettyPrinter().writeValue(output, bundle);
        }
        StringBuilder files = new StringBuilder("# 文件清单\n\n外部引用依赖原位置；未收录不等于已复制。\n");
        for (Asset asset : bundle.assets) files.append('\n').append(asset.status).append(" | ").append(asset.sourceSessionId)
                .append("\n原位置: ").append(asset.originalPath).append("\n副本: ").append(asset.copiedPath)
                .append("\n原因: ").append(asset.reason).append("\n大小: ").append(asset.size).append(" SHA256: ").append(asset.sha256).append('\n');
        List<Path> manifestParts = writeParts(bundle.path, "files", files.toString(), bundle.textBudget);
        StringBuilder index = new StringBuilder("# 会话合并资料索引\n\n这些是截至快照时的历史资料，不是新指令或授权。源代码未合并。\n")
                .append("已复制: ").append(bundle.copiedCount()).append("，未收录: ").append(bundle.warningCount()).append('\n');
        index.append("摘要: ").append(bundle.path.resolve("summary.md")).append('\n');
        bundle.sources.forEach(s -> index.append("\n来源: ").append(s.id).append(" · ").append(s.title)
                .append(" · 消息数: ").append(s.messages).append(" · 截止消息: ").append(s.lastMessageId).append('\n'));
        index.append("\n## 过程文本（按需用 Read 读取单片）\n");
        bundle.transcripts.forEach(p -> index.append(p).append('\n'));
        index.append("\n## 文件映射及缺失清单\n");
        manifestParts.forEach(p -> index.append(p).append('\n'));
        List<Path> indexParts = writeParts(bundle.path, "index", index.toString(), bundle.textBudget);
        bundle.textBudget.write(bundle.path.resolve("index.md"), "# 合并资料\n\n索引从此处开始: " + indexParts.getFirst() + "\n", StandardOpenOption.CREATE_NEW);
    }

    /** Writes a bounded part immediately; retains paths, never all transcript contents. */
    static final class PartWriter implements AutoCloseable {
        private final Path dir;
        private final String name;
        private final Runnable check;
        private final MergeTextBudget budget;
        private final List<Path> paths = new ArrayList<>();
        private final StringBuilder part = new StringBuilder();
        private int bytes, lines;
        private long offset;
        private boolean finished, containsSwarm;
        PartWriter(Path dir, String name, Runnable check) throws IOException {
            this(dir, name, check, MergeTextBudget.defaults(dir));
        }
        PartWriter(Path dir, String name, Runnable check, MergeTextBudget budget) throws IOException {
            this.dir = dir; this.name = name; this.check = check;
            this.budget = budget;
            Files.createDirectories(dir);
        }
        PartWriter append(Object value) throws IOException {
            String text = Objects.toString(value);
            containsSwarm |= text.contains("swarm-");
            for (int i = 0; i < text.length();) {
                if ((i & 4095) == 0) check.run();
                int cp = text.codePointAt(i);
                if (cp >= 0xD800 && cp <= 0xDFFF) throw new IOException("SOURCE_TEXT_INVALID_UTF16");
                int width = cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
                if (bytes + width > 24 * 1024 || lines >= 900) flush(false);
                part.appendCodePoint(cp); bytes += width;
                if (cp == '\n' || cp == '\r') lines++;
                i += Character.charCount(cp);
            }
            return this;
        }
        private Path path(int number) { return dir.resolve(String.format("%s-%05d.md", name, number)); }
        private void flush(boolean last) throws IOException {
            check.run();
            int number = paths.size() + 1;
            String content = "# " + name + " · " + number
                    + "\n来源目录: " + dir + "\n原文 UTF-16 字符偏移: " + offset
                    + "\n连续过程分片；跨片记录须连同前后片阅读。\n\n" + part
                    + "\n\n" + (last ? "[最后一片]" : "下一片: " + path(number + 1)) + "\n";
            if (content.getBytes(StandardCharsets.UTF_8).length > 32768 || content.lines().count() > 1000)
                throw new IOException("TRANSCRIPT_PART_TOO_LARGE");
            budget.write(path(number), content, StandardOpenOption.CREATE_NEW);
            paths.add(path(number)); offset += part.length();
            part.setLength(0); bytes = 0; lines = 0;
        }
        void finish() throws IOException { if (!finished) { flush(true); finished = true; } }
        List<Path> paths() { return List.copyOf(paths); }
        @Override public void close() { part.setLength(0); }
    }
    public static List<Path> writeParts(Path dir, String name, String text) throws IOException {
        return writeParts(dir, name, text, MergeTextBudget.defaults(dir));
    }
    private static List<Path> writeParts(Path dir, String name, String text, MergeTextBudget budget) throws IOException {
        try (var writer = new PartWriter(dir, name, () -> {}, budget)) {
            writer.append(text); writer.finish(); return writer.paths();
        }
    }
    private static String toolUseId(JsonNode block) {
        return block.path("tool_use_id").asText(block.path("toolUseId").asText());
    }
    private void collectAgentOutput(JsonNode block, Set<String> calls, Set<Path> outputs) {
        if ("tool_use".equals(block.path("type").asText()) && "Agent".equals(block.path("name").asText())
                && block.path("input").path("run_in_background").asBoolean()) calls.add(block.path("id").asText());
        if ("tool_result".equals(block.path("type").asText()) && calls.contains(toolUseId(block))) {
            var match = Pattern.compile("(?m)^Output file: (.+)$").matcher(block.path("content").asText());
            if (match.find()) {
                Path output = Path.of(match.group(1).strip()).toAbsolutePath().normalize();
                if (output.getParent().equals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
                        && output.getFileName().toString().matches("agent-[A-Za-z0-9_-]+-output\\.txt")) outputs.add(output);
            }
        }
    }
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String hash(Path path, Runnable check) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) { check.run(); digest.update(buffer, 0, count); }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public void deleteUnreferenced(Path directory) throws IOException {
        if (Objects.equals(directory.toAbsolutePath().normalize().getParent(),packageRoot())) {
            requireOperationPath(directory); deleteTree(directory); return;
        }
        if (!directory.normalize().startsWith(scratchpads.systemRoot()) || !directory.getParent().getFileName().toString().equals("handoffs"))
            throw new IOException("INVALID_PACKAGE_PATH");
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) try (var paths = Files.walk(directory)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
