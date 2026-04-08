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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

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
 * @see <a href="SPEC §4.1.5">NotebookEditTool</a>
 */
@Component
public class NotebookEditTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(NotebookEditTool.class);

    /** JVM 内路径级线程锁 (v1.49.0 F3-03) */
    private static final ConcurrentHashMap<Path, ReentrantLock> pathLocks = new ConcurrentHashMap<>();
    /** 锁定超时 (秒) */
    static final int LOCK_TIMEOUT_SECONDS = 5;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

        Path path = Path.of(notebookPath).toAbsolutePath().normalize();

        // Layer 1: JVM 内路径级锁
        ReentrantLock jvmLock = pathLocks.computeIfAbsent(path, p -> new ReentrantLock());
        try {
            if (!jvmLock.tryLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return ToolResult.error("Timeout acquiring JVM lock on " + notebookPath);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted while acquiring lock on " + notebookPath);
        }

        try {
            // Layer 2: 跨进程文件级锁
            try (FileChannel channel = FileChannel.open(path,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock fileLock = channel.tryLock()) {

                if (fileLock == null) {
                    return ToolResult.error("Failed to acquire file lock on " + notebookPath);
                }

                // 解析 notebook JSON — 先读取全部字节再解析
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate((int) channel.size());
                channel.read(buf);
                buf.flip();
                JsonNode notebook = objectMapper.readTree(buf.array(), 0, buf.limit());
                ArrayNode cells = (ArrayNode) notebook.get("cells");
                if (cells == null) {
                    return ToolResult.error("Invalid notebook: no 'cells' array found");
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

                // 写回文件
                channel.position(0);
                channel.truncate(0);
                byte[] outputBytes = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(notebook);
                channel.write(java.nio.ByteBuffer.wrap(outputBytes));

                return ToolResult.success("Notebook updated: " + result);
            }
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            log.error("Notebook edit failed: {}", e.getMessage(), e);
            return ToolResult.error("Notebook edit failed: " + e.getMessage());
        } finally {
            jvmLock.unlock();
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
