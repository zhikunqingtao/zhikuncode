package com.aicodeassistant.mcp.roots;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3 — McpRootsProvider 单元测试。
 *
 * <p>覆盖：updateRoots 替换语义、addRoot 追加语义、
 * RootDescriptor.fromPath 路径标准化（file:// URI），以及 getCurrentRoots
 * 返回不可变快照的契约。
 */
@DisplayName("M3 McpRootsProvider 单元测试")
class McpRootsProviderTest {

    private McpRootsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new McpRootsProvider();
    }

    @Test
    @DisplayName("updateRoots 替换全部 roots，getCurrentRoots 返回不可变快照")
    void testUpdateRootsReplacesAll() {
        // 第一次设置
        provider.updateRoots("/Users/foo/projectA", "projectA");
        List<RootDescriptor> first = provider.getCurrentRoots();
        assertEquals(1, first.size(), "首次 updateRoots 应包含 1 个 root");
        assertEquals("projectA", first.get(0).name());

        // 第二次设置应当替换而非追加
        provider.updateRoots("/Users/foo/projectB", "projectB");
        List<RootDescriptor> second = provider.getCurrentRoots();
        assertEquals(1, second.size(), "updateRoots 应替换而非追加");
        assertEquals("projectB", second.get(0).name());
        assertEquals("file:///Users/foo/projectB", second.get(0).uri());

        // 不可变快照：对返回 list 修改应抛 UnsupportedOperationException
        assertThrows(UnsupportedOperationException.class,
                () -> second.add(RootDescriptor.fromPath("/x", "x")),
                "getCurrentRoots 返回的应为不可变列表");

        // 空白路径不应注入 root
        provider.updateRoots("   ", "blank");
        assertTrue(provider.getCurrentRoots().isEmpty(),
                "空白 workspacePath 不应产生 root");

        // null 路径同样静默
        provider.updateRoots(null, "nullName");
        assertTrue(provider.getCurrentRoots().isEmpty(),
                "null workspacePath 不应产生 root");
    }

    @Test
    @DisplayName("addRoot 追加（多工程场景）")
    void testAddRootAppends() {
        provider.updateRoots("/Users/foo/projectA", "projectA");
        provider.addRoot("/Users/foo/projectB", "projectB");

        List<RootDescriptor> roots = provider.getCurrentRoots();
        assertEquals(2, roots.size(), "addRoot 应在已有 roots 上追加");
        assertEquals("projectA", roots.get(0).name());
        assertEquals("projectB", roots.get(1).name());

        // null / 空白路径不会追加
        provider.addRoot(null, "ignored");
        provider.addRoot("", "ignored");
        provider.addRoot("   ", "ignored");
        assertEquals(2, provider.getCurrentRoots().size(),
                "null/空白路径不应追加 root");

        // clear 应清空
        provider.clear();
        assertTrue(provider.getCurrentRoots().isEmpty(), "clear 后应为空");
    }

    @Test
    @DisplayName("RootDescriptor.fromPath 路径标准化为 file:// URI")
    void testFromPathNormalization() {
        // Unix 绝对路径 → file://+path
        RootDescriptor unix = RootDescriptor.fromPath("/Users/foo/project", "demo");
        assertEquals("file:///Users/foo/project", unix.uri(),
                "Unix 绝对路径应规范为 file:///...");
        assertEquals("demo", unix.name());

        // Windows 风格路径 → file:///+path 且反斜杠转正斜杠
        RootDescriptor win = RootDescriptor.fromPath("C:\\workspace\\proj", "winProj");
        assertEquals("file:///C:/workspace/proj", win.uri(),
                "Windows 路径应规范为 file:///C:/... 并将反斜杠转换为正斜杠");
        assertEquals("winProj", win.name());

        // null 路径 → file:/// 兜底，不抛异常
        RootDescriptor nullPath = RootDescriptor.fromPath(null, "noPath");
        assertEquals("file:///", nullPath.uri(),
                "null 路径应兜底为 file:///");
        assertEquals("noPath", nullPath.name());
    }
}
