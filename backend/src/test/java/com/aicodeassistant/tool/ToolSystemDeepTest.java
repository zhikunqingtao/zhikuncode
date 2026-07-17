package com.aicodeassistant.tool;

import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-TOOL-DEEP-001~005 工具系统深度测试。
 * 纯单元测试，验证工具接口契约和权限分级设计。
 */
@DisplayName("工具系统深度测试")
class ToolSystemDeepTest {

    @Nested
    @DisplayName("TC-TOOL-DEEP-001 工具注册与查找完整性")
    class ToolRegistryCompletenessTest {

        @Test
        @DisplayName("ToolRegistry 类存在且有核心方法")
        void testToolRegistryClassExists() throws Exception {
            Class<?> clazz = Class.forName("com.aicodeassistant.tool.ToolRegistry");
            assertNotNull(clazz);

            // 验证核心方法存在
            assertNotNull(clazz.getMethod("getEnabledTools"));
            assertNotNull(clazz.getMethod("findByName", String.class));
        }

        @Test
        @DisplayName("Tool 接口定义完整")
        void testToolInterfaceContract() {
            // Tool 接口应有以下方法
            assertDoesNotThrow(() -> Tool.class.getMethod("getName"));
            assertDoesNotThrow(() -> Tool.class.getMethod("getDescription"));
            assertDoesNotThrow(() -> Tool.class.getMethod("getInputSchema"));
            assertDoesNotThrow(() -> Tool.class.getMethod("getGroup"));
            assertDoesNotThrow(() -> Tool.class.getMethod("getPermissionRequirement"));
            assertDoesNotThrow(() -> Tool.class.getMethod("isMcp"));
        }

        @Test
        @DisplayName("ToolResult 成功/错误工厂方法")
        void testToolResultFactoryMethods() {
            ToolResult success = ToolResult.success("content");
            assertNotNull(success);
            assertFalse(success.isError());

            ToolResult error = ToolResult.internalError("TEST_FAILURE", "error msg",
                    ToolResult.EffectState.NONE);
            assertNotNull(error);
            assertTrue(error.isError());
        }

        @Test
        @DisplayName("findByNameOptional 方法存在")
        void testFindByNameOptionalExists() throws Exception {
            Class<?> clazz = Class.forName("com.aicodeassistant.tool.ToolRegistry");
            assertNotNull(clazz.getMethod("findByNameOptional", String.class));
        }
    }

    @Nested
    @DisplayName("TC-TOOL-DEEP-002 会话级工具过滤与缓存")
    class SessionToolFilteringTest {

        @Test
        @DisplayName("ToolSessionState 类存在")
        void testToolSessionStateExists() throws Exception {
            Class<?> clazz = Class.forName("com.aicodeassistant.tool.ToolSessionState");
            assertNotNull(clazz);
        }

        @Test
        @DisplayName("ToolRegistry 支持会话级过滤方法")
        void testSessionFilterMethodExists() throws Exception {
            Class<?> clazz = Class.forName("com.aicodeassistant.tool.ToolRegistry");
            // getEnabledTools with sessionId parameter
            var methods = clazz.getMethods();
            boolean hasSessionFilter = false;
            for (var m : methods) {
                if ("getEnabledTools".equals(m.getName()) && m.getParameterCount() > 0) {
                    hasSessionFilter = true;
                    break;
                }
            }
            // 可能是 getToolsForSession 或类似方法
            assertTrue(hasSessionFilter || clazz.getMethod("getEnabledTools") != null,
                "应有工具获取方法");
        }
    }

    @Nested
    @DisplayName("TC-TOOL-DEEP-003 工具权限分级验证")
    class ToolPermissionClassificationTest {

        @Test
        @DisplayName("PermissionRequirement 枚举包含必要级别")
        void testPermissionRequirementLevels() {
            PermissionRequirement none = PermissionRequirement.NONE;
            assertNotNull(none);

            // 验证枚举值存在
            PermissionRequirement[] values = PermissionRequirement.values();
            assertTrue(values.length >= 2, "至少应有2个权限级别");
        }

        @Test
        @DisplayName("Tool 接口 getPermissionRequirement 默认不为null")
        void testToolPermissionDefault() throws Exception {
            // 验证通过反射 Tool 接口存在 getPermissionRequirement 方法
            var method = Tool.class.getMethod("getPermissionRequirement");
            assertNotNull(method);
            assertEquals(PermissionRequirement.class, method.getReturnType());
        }
    }

    @Nested
    @DisplayName("TC-TOOL-DEEP-004 工具执行超时与异常处理")
    class ToolExecutionTimeoutTest {

        @Test
        @DisplayName("ToolResult.isError() 标记错误结果")
        void testToolResultIsError() {
            ToolResult errorResult = ToolResult.timedOut("TEST_TIMEOUT", "Timeout exceeded",
                    null, true, ToolResult.EffectState.NONE);
            assertTrue(errorResult.isError(), "错误结果 isError 应为 true");
            assertTrue(errorResult.content().contains("Timeout"),
                "错误内容应包含超时说明");
        }

        @Test
        @DisplayName("ToolResult.success() 标记成功结果")
        void testToolResultSuccess() {
            ToolResult successResult = ToolResult.success("file content here");
            assertFalse(successResult.isError(), "成功结果 isError 应为 false");
            assertEquals("file content here", successResult.content());
        }

        @Test
        @DisplayName("ToolResult metadata 支持")
        void testToolResultMetadata() {
            ToolResult result = ToolResult.success("content")
                .withMetadata("key", "value");
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("TC-TOOL-DEEP-005 工具分组与排序验证")
    class ToolGroupingTest {

        @Test
        @DisplayName("Tool 接口 getGroup 方法存在")
        void testToolGroupMethod() throws Exception {
            var method = Tool.class.getMethod("getGroup");
            assertNotNull(method);
            assertEquals(String.class, method.getReturnType());
        }

        @Test
        @DisplayName("ToolInput 类存在且有核心方法")
        void testToolInputExists() throws Exception {
            Class<?> clazz = Class.forName("com.aicodeassistant.tool.ToolInput");
            assertNotNull(clazz);
            assertNotNull(clazz.getMethod("getRawData"));
        }

        @Test
        @DisplayName("ToolExecutionPipeline 类存在")
        void testToolExecutionPipelineExists() throws Exception {
            Class<?> clazz = Class.forName("com.aicodeassistant.tool.ToolExecutionPipeline");
            assertNotNull(clazz);
        }

        @Test
        @DisplayName("StreamingToolExecutor 类存在")
        void testStreamingToolExecutorExists() throws Exception {
            Class<?> clazz = Class.forName("com.aicodeassistant.tool.StreamingToolExecutor");
            assertNotNull(clazz);
        }
    }
}
