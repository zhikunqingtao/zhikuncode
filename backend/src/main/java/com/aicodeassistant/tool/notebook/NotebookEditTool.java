package com.aicodeassistant.tool.notebook;

import com.aicodeassistant.model.PermissionBehavior;
import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.aicodeassistant.tool.impl.AtomicFileWriter;
import com.aicodeassistant.tool.impl.FileVersionTracker;
import com.aicodeassistant.security.ManagedWorkspacePathResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NotebookEditTool — 编辑 Jupyter Notebook (.ipynb) 文件。
 * <p>
 * 支持的操作:
 * - add_cell: 在指定位置插入新 cell
 * - edit_cell: 替换 cell 源码
 * - delete_cell: 删除指定 cell
 * - move_cell: 移动 cell 位置 (up/down)
 * - change_cell_type: 切换 cell 类型 (code/markdown)
 * <p>
 * 双层锁策略 (v1.49.0):
 * - Layer 1: JVM 内 — ConcurrentHashMap 路径级 ReentrantLock
 * - Layer 2: 跨进程 — FileChannel.tryLock() 文件级锁
 *
 */
@Component
public class NotebookEditTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(NotebookEditTool.class);

    /** Retained public constant for the notebook golden contract. Atomic writes no longer wait on a separate lock. */
    static final int LOCK_TIMEOUT_SECONDS = 5;
    private static final long MAX_NOTEBOOK_BYTES = 100L * 1024 * 1024;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicFileWriter atomicFileWriter;
    private final ManagedWorkspacePathResolver managedPaths;

    @Autowired
    public NotebookEditTool(AtomicFileWriter atomicFileWriter, ManagedWorkspacePathResolver managedPaths) {
        this.atomicFileWriter = atomicFileWriter;
        this.managedPaths = managedPaths;
    }

    public NotebookEditTool() {
        this(new AtomicFileWriter(new FileVersionTracker()), new ManagedWorkspacePathResolver());
    }

    @Override
    public String getName() {
        return "NotebookEdit";
    }

    @Override
    public String getDescription() {
        return "Edit Jupyter Notebook (.ipynb) files. Supports adding, editing, " +
                "deleting, moving cells and changing cell types.";
    }

    @Override
    public String prompt() {
        return """
                Completely replaces the contents of a specific cell in a Jupyter notebook (.ipynb file) \
                with new source. Jupyter notebooks are interactive documents that combine code, text, \
                and visualizations, commonly used for data analysis and scientific computing. The \
                notebook_path parameter must be an absolute path, not a relative path. The cell_number \
                is 0-indexed. Use edit_mode=insert to add a new cell at the index specified by cell_number. \
                Use edit_mode=delete to delete the cell at the index specified by cell_number.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "notebook_path", Map.of(
                                "type", "string",
                                "description", "Path to the .ipynb file"
                        ),
                        "command", Map.of(
                                "type", "string",
                                "enum", List.of("add_cell", "edit_cell", "delete_cell",
                                        "move_cell", "change_cell_type"),
                                "description", "Operation to perform"
                        ),
                        "cell_index", Map.of(
                                "type", "integer",
                                "description", "Index of the cell to operate on"
                        ),
                        "content", Map.of(
                                "type", "string",
                                "description", "Content for add/edit operations"
                        )
                ),
                "required", List.of("notebook_path", "command")
        );
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String notebookPath = input.getString("notebook_path");
        String command = input.getString("command");

        try {
            Path path = managedPaths.resolveProspective(Path.of(notebookPath), context.workingDirectory());
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
                return ToolResult.validationError("NOTEBOOK_NOT_REGULAR_FILE", "Notebook is not a regular file: " + notebookPath);
            if (Files.size(path) > MAX_NOTEBOOK_BYTES)
                return ToolResult.validationError("NOTEBOOK_SIZE_LIMIT", "Notebook exceeds 100MiB limit: " + notebookPath);
            byte[] original = Files.readAllBytes(path);
            String expectedHash = sha256(original);
                JsonNode notebook = objectMapper.readTree(original);
                ArrayNode cells = (ArrayNode) notebook.get("cells");
                if (cells == null) {
                    return ToolResult.validationError("NOTEBOOK_CELLS_MISSING", "Invalid notebook: no 'cells' array found");
                }

                // 执行操作
                String result = switch (command) {
                    case "add_cell" -> addCell(input, cells);
                    case "edit_cell" -> editCell(input, cells);
                    case "delete_cell" -> deleteCell(input, cells);
                    case "move_cell" -> moveCell(input, cells);
                    case "change_cell_type" -> changeCellType(input, cells);
                    default -> throw new IllegalArgumentException("Unknown command: " + command);
                };

                byte[] outputBytes = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(notebook);
            AtomicFileWriter.WriteResult write = atomicFileWriter.write(path, outputBytes,
                    context.sessionId(), AtomicFileWriter.ExpectedOldState.sha256(expectedHash),
                    context.workingDirectory());
            if (!write.success()) return ToolResult.failed(ToolResult.ToolFailureType.INTERNAL,
                    "NOTEBOOK_ATOMIC_WRITE_FAILED", write.error(), ToolResult.Retryability.NEVER,
                    switch (write.effect()) {
                        case NOT_STARTED -> ToolResult.EffectState.NOT_STARTED;
                        case APPLIED -> ToolResult.EffectState.APPLIED;
                        case UNKNOWN -> ToolResult.EffectState.UNKNOWN;
                    }, null, Map.of());
            return ToolResult.successWithEffect("Notebook updated: " + result, ToolResult.EffectState.APPLIED)
                    .withMetadata("sealedHash", write.newHash());
        } catch (IllegalArgumentException e) {
            return ToolResult.validationError("NOTEBOOK_INPUT_INVALID", e.getMessage());
        } catch (IOException e) {
            log.error("Notebook edit failed: {}", e.getMessage(), e);
            return ToolResult.internalError("NOTEBOOK_EDIT_IO_FAILED", "Notebook edit failed: " + e.getMessage(),
                    ToolResult.EffectState.NOT_STARTED);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    // ==================== Cell 操作 ====================

    private String addCell(ToolInput input, ArrayNode cells) {
        String cellType = input.getString("cell_type", "code");
        String content = input.getString("content", "");
        int index = input.getInt("cell_index", cells.size());

        ObjectNode newCell = createCell(cellType, content);
        cells.insert(index, newCell);
        return "add_cell at index " + index;
    }

    private String editCell(ToolInput input, ArrayNode cells) {
        int idx = input.getInt("cell_index");
        if (idx < 0 || idx >= cells.size()) {
            throw new IllegalArgumentException("Cell index out of range: " + idx);
        }

        ObjectNode cell = (ObjectNode) cells.get(idx);
        cell.set("source", toSourceArray(input.getString("content")));

        // 清除执行状态 (code cell)
        if ("code".equals(cell.path("cell_type").asText())) {
            cell.putNull("execution_count");
            cell.set("outputs", objectMapper.createArrayNode());
        }
        return "edit_cell at index " + idx;
    }

    private String deleteCell(ToolInput input, ArrayNode cells) {
        int idx = input.getInt("cell_index");
        if (idx < 0 || idx >= cells.size()) {
            throw new IllegalArgumentException("Cell index out of range: " + idx);
        }
        cells.remove(idx);
        return "delete_cell at index " + idx;
    }

    private String moveCell(ToolInput input, ArrayNode cells) {
        int from = input.getInt("cell_index");
        String direction = input.getString("direction");
        int to = "up".equals(direction) ? from - 1 : from + 1;

        if (from < 0 || from >= cells.size()) {
            throw new IllegalArgumentException("Cell index out of range: " + from);
        }
        if (to < 0 || to >= cells.size()) {
            throw new IllegalArgumentException("Cannot move cell " + direction + ": target index " + to + " out of range");
        }

        JsonNode moving = cells.remove(from);
        cells.insert(to, moving);
        return "move_cell from " + from + " to " + to;
    }

    private String changeCellType(ToolInput input, ArrayNode cells) {
        int idx = input.getInt("cell_index");
        if (idx < 0 || idx >= cells.size()) {
            throw new IllegalArgumentException("Cell index out of range: " + idx);
        }

        String newType = input.getString("cell_type");
        ObjectNode cell = (ObjectNode) cells.get(idx);
        cell.put("cell_type", newType);

        // markdown → code: 添加 outputs 和 execution_count
        if ("code".equals(newType) && !cell.has("outputs")) {
            cell.set("outputs", objectMapper.createArrayNode());
            cell.putNull("execution_count");
        }
        // code → markdown: 移除 outputs 和 execution_count
        if ("markdown".equals(newType)) {
            cell.remove("outputs");
            cell.remove("execution_count");
        }

        return "change_cell_type at index " + idx + " to " + newType;
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建新 cell — nbformat 4 格式。
     */
    ObjectNode createCell(String cellType, String source) {
        ObjectNode cell = objectMapper.createObjectNode();
        cell.put("cell_type", cellType);
        cell.set("source", toSourceArray(source));
        cell.set("metadata", objectMapper.createObjectNode());
        // nbformat 4.5+ 要求 cell_id
        cell.put("id", UUID.randomUUID().toString().substring(0, 8));

        if ("code".equals(cellType)) {
            cell.set("outputs", objectMapper.createArrayNode());
            cell.putNull("execution_count");
        }
        return cell;
    }

    /**
     * 将内容转换为 nbformat source 数组格式。
     * <p>
     * nbformat 规范: source 是字符串数组，每行一个元素（含换行符）。
     */
    ArrayNode toSourceArray(String content) {
        ArrayNode source = objectMapper.createArrayNode();
        if (content == null || content.isEmpty()) return source;

        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i < lines.length - 1) {
                source.add(lines[i] + "\n");
            } else {
                source.add(lines[i]);
            }
        }
        return source;
    }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.ALWAYS_ASK;
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        return false;
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return false;
    }

    @Override
    public String getGroup() {
        return "edit";
    }

    @Override
    public String getPath(ToolInput input) {
        return input.has("notebook_path") ? input.getString("notebook_path") : null;
    }
}
