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
        CopyLimitException(String reason) { super(reason); }
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

    public Path packagePath(String target, String operation) {
        return scratchpads.resolveChild(target).resolve("handoffs").resolve(UUID.fromString(operation).toString());
    }

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
        if (!directory.normalize().startsWith(scratchpads.systemRoot()) || !directory.getParent().getFileName().toString().equals("handoffs"))
            throw new IOException("INVALID_PACKAGE_PATH");
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) try (var paths = Files.walk(directory)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
