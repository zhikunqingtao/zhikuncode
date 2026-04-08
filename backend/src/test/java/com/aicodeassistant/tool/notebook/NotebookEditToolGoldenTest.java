package com.aicodeassistant.tool.notebook;

import com.aicodeassistant.tool.PermissionRequirement;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NotebookEditTool 黄金测试。
 * 覆盖 §4.1.5 NotebookEditTool 全部操作。
 */
class NotebookEditToolGoldenTest {

    @TempDir
    Path tempDir;

    private NotebookEditTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        tool = new NotebookEditTool();
        mapper = new ObjectMapper();
    }

    // ==================== 基础属性 ====================

    @Test
    @DisplayName("工具名称为 NotebookEdit")
    void toolName() {
        assertEquals("NotebookEdit", tool.getName());
    }

    @Test
    @DisplayName("工具描述包含 Jupyter Notebook")
    void toolDescription() {
        assertTrue(tool.getDescription().contains("Jupyter Notebook"));
    }

    @Test
    @DisplayName("权限要求 ALWAYS_ASK")
    void permissionAlwaysAsk() {
        assertEquals(PermissionRequirement.ALWAYS_ASK,
                tool.getPermissionRequirement());
    }

    @Test
    @DisplayName("isReadOnly = false")
    void notReadOnly() {
        assertFalse(tool.isReadOnly(ToolInput.from(Map.of())));
    }

    @Test
    @DisplayName("isConcurrencySafe = false")
    void notConcurrencySafe() {
        assertFalse(tool.isConcurrencySafe(ToolInput.from(Map.of())));
    }

    @Test
    @DisplayName("分组为 edit")
    void groupIsEdit() {
        assertEquals("edit", tool.getGroup());
    }

    @Test
    @DisplayName("getPath 返回 notebook_path")
    void getPathReturnsNotebookPath() {
        String path = tool.getPath(ToolInput.from(Map.of("notebook_path", "/test.ipynb")));
        assertEquals("/test.ipynb", path);
    }

    @Test
    @DisplayName("LOCK_TIMEOUT_SECONDS = 5")
    void lockTimeout() {
        assertEquals(5, NotebookEditTool.LOCK_TIMEOUT_SECONDS);
    }

    // ==================== Cell 创建辅助 ====================

    @Test
    @DisplayName("createCell code 类型")
    void createCodeCell() {
        ObjectNode cell = tool.createCell("code", "print('hello')");
        assertEquals("code", cell.get("cell_type").asText());
        assertNotNull(cell.get("source"));
        assertNotNull(cell.get("outputs"));
        assertTrue(cell.get("execution_count").isNull());
        assertNotNull(cell.get("id"));
    }

    @Test
    @DisplayName("createCell markdown 类型")
    void createMarkdownCell() {
        ObjectNode cell = tool.createCell("markdown", "# Title");
        assertEquals("markdown", cell.get("cell_type").asText());
        assertNotNull(cell.get("source"));
        assertFalse(cell.has("outputs"));
    }

    @Test
    @DisplayName("toSourceArray 多行拆分")
    void toSourceArrayMultiLine() {
        ArrayNode source = tool.toSourceArray("line1\nline2\nline3");
        assertEquals(3, source.size());
        assertEquals("line1\n", source.get(0).asText());
        assertEquals("line2\n", source.get(1).asText());
        assertEquals("line3", source.get(2).asText());
    }

    @Test
    @DisplayName("toSourceArray 单行")
    void toSourceArraySingleLine() {
        ArrayNode source = tool.toSourceArray("hello");
        assertEquals(1, source.size());
        assertEquals("hello", source.get(0).asText());
    }

    @Test
    @DisplayName("toSourceArray 空内容")
    void toSourceArrayEmpty() {
        ArrayNode source = tool.toSourceArray("");
        assertEquals(0, source.size());
    }

    @Test
    @DisplayName("toSourceArray null")
    void toSourceArrayNull() {
        ArrayNode source = tool.toSourceArray(null);
        assertEquals(0, source.size());
    }

    // ==================== 端到端操作 ====================

    private Path createTestNotebook(String... cellContents) throws IOException {
        ObjectNode notebook = mapper.createObjectNode();
        notebook.put("nbformat", 4);
        notebook.put("nbformat_minor", 5);
        notebook.set("metadata", mapper.createObjectNode());

        ArrayNode cells = mapper.createArrayNode();
        for (String content : cellContents) {
            ObjectNode cell = tool.createCell("code", content);
            cells.add(cell);
        }
        notebook.set("cells", cells);

        Path path = tempDir.resolve("test.ipynb");
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), notebook);
        return path;
    }

    @Test
    @DisplayName("add_cell 添加到末尾")
    void addCellAtEnd() throws IOException {
        Path nb = createTestNotebook("cell0", "cell1");

        ToolResult result = tool.call(
                ToolInput.from(Map.of(
                        "notebook_path", nb.toString(),
                        "command", "add_cell",
                        "content", "new cell")),
                ToolUseContext.of(tempDir.toString(), "s1"));

        assertFalse(result.isError());
        JsonNode updated = mapper.readTree(nb.toFile());
        assertEquals(3, updated.get("cells").size());
    }

    @Test
    @DisplayName("add_cell 指定位置")
    void addCellAtIndex() throws IOException {
        Path nb = createTestNotebook("cell0", "cell1");

        ToolResult result = tool.call(
                ToolInput.from(Map.of(
                        "notebook_path", nb.toString(),
                        "command", "add_cell",
                        "cell_index", 1,
                        "content", "inserted")),
                ToolUseContext.of(tempDir.toString(), "s1"));

        assertFalse(result.isError());
        JsonNode updated = mapper.readTree(nb.toFile());
        assertEquals(3, updated.get("cells").size());
    }

    @Test
    @DisplayName("edit_cell 替换内容")
    void editCell() throws IOException {
        Path nb = createTestNotebook("old content");

        ToolResult result = tool.call(
                ToolInput.from(Map.of(
                        "notebook_path", nb.toString(),
                        "command", "edit_cell",
                        "cell_index", 0,
                        "content", "new content")),
                ToolUseContext.of(tempDir.toString(), "s1"));

        assertFalse(result.isError());
        JsonNode updated = mapper.readTree(nb.toFile());
        String source = updated.get("cells").get(0).get("source").get(0).asText();
        assertTrue(source.contains("new content"));
    }

    @Test
    @DisplayName("edit_cell 清除 execution_count")
    void editCellClearsExecution() throws IOException {
        Path nb = createTestNotebook("code");

        tool.call(
                ToolInput.from(Map.of(
                        "notebook_path", nb.toString(),
                        "command", "edit_cell",
                        "cell_index", 0,
                        "content", "updated")),
                ToolUseContext.of(tempDir.toString(), "s1"));

        JsonNode updated = mapper.readTree(nb.toFile());
        assertTrue(updated.get("cells").get(0).get("execution_count").isNull());
    }

    @Test
    @DisplayName("delete_cell 删除")
    void deleteCell() throws IOException {
        Path nb = createTestNotebook("cell0", "cell1", "cell2");

        ToolResult result = tool.call(
                ToolInput.from(Map.of(
                        "notebook_path", nb.toString(),
                        "command", "delete_cell",
                        "cell_index", 1)),
                ToolUseContext.of(tempDir.toString(), "s1"));

        assertFalse(result.isError());
        JsonNode updated = mapper.readTree(nb.toFile());
        assertEquals(2, updated.get("cells").size());
    }

    @Test
    @DisplayName("move_cell down")
    void moveCellDown() throws IOException {
        Path nb = createTestNotebook("cell0", "cell1", "cell2");

        ToolResult result = tool.call(
                ToolInput.from(Map.of(
                        "notebook_path", nb.toString(),
                        "command", "move_cell",
                        "cell_index", 0,
                        "direction", "down")),
                ToolUseContext.of(tempDir.toString(), "s1"));

        assertFalse(result.isError());
    }

    @Test
    @DisplayName("change_cell_type code → markdown")
    void changeCellType() throws IOException {
        Path nb = createTestNotebook("# Title");

        ToolResult result = tool.call(
                ToolInput.from(Map.of(
                        "notebook_path", nb.toString(),
                        "command", "change_cell_type",
                        "cell_index", 0,
                        "cell_type", "markdown")),
                ToolUseContext.of(tempDir.toString(), "s1"));

        assertFalse(result.isError());
        JsonNode updated = mapper.readTree(nb.toFile());
        assertEquals("markdown", updated.get("cells").get(0).get("cell_type").asText());
    }

    @Test
    @DisplayName("不存在的文件返回错误")
    void nonExistentFile() {
        ToolResult result = tool.call(
                ToolInput.from(Map.of(
                        "notebook_path", tempDir.resolve("nonexistent.ipynb").toString(),
                        "command", "add_cell",
                        "content", "test")),
                ToolUseContext.of(tempDir.toString(), "s1"));
        assertTrue(result.isError());
    }

    @Test
    @DisplayName("输入 Schema 包含 notebook_path 和 command")
    void inputSchemaFields() {
        Map<String, Object> schema = tool.getInputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("notebook_path"));
        assertTrue(props.containsKey("command"));
        assertTrue(props.containsKey("cell_index"));
        assertTrue(props.containsKey("content"));
    }
}
