package com.aicodeassistant.controller;

import com.aicodeassistant.engine.QueryEngine;
import com.aicodeassistant.engine.TokenCounter;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.llm.ModelRegistry;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.prompt.EffectiveSystemPromptBuilder;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.session.SessionExecutionGate;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * maxBudgetUsd 未实现契约: 三个 REST 入口在创建会话 / 取得运行资格 / 创建 SSE 之前
 * 显式拒绝非空金额预算 (400 + QUERY_BUDGET_USD_UNSUPPORTED), 而不是静默忽略;
 * 字段缺省或显式 null 时行为保持不变。
 */
class QueryControllerMaxBudgetUsdTest {

    private static final String BUDGET_CODE = "QUERY_BUDGET_USD_UNSUPPORTED";
    private static final String BUDGET_MESSAGE =
            "当前不支持金额预算；请省略该字段或传 null，系统不会执行金额上限。";

    @Test
    void allRestEntrypointsRejectAnyNonNullMaxBudgetUsdBeforeAnyWork() throws Exception {
        SessionManager sessions = mock(SessionManager.class);
        ProjectWorkspaceService projects = mock(ProjectWorkspaceService.class);
        QueryEngine engine = mock(QueryEngine.class);
        PermissionModeManager modes = mock(PermissionModeManager.class);
        SessionExecutionGate gate = mock(SessionExecutionGate.class);
        MockMvc mvc = mvc(controller(sessions, projects, engine, modes, gate));

        for (String budget : List.of("0.0", "-1.0", "5.0")) {
            mvc.perform(post("/api/query")
                            .accept(MediaType.APPLICATION_JSON)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"prompt\":\"hello\",\"sessionId\":\"session-1\","
                                    + "\"maxBudgetUsd\":" + budget + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.error.code").value(BUDGET_CODE))
                    .andExpect(jsonPath("$.error.message").value(BUDGET_MESSAGE));

            // CLI 以 "text/event-stream, application/json" 调用 SSE 入口;
            // 拒绝必须返回 JSON 400, 不能开始 text/event-stream 响应。
            mvc.perform(post("/api/query/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.ACCEPT, "text/event-stream, application/json")
                            .content("{\"prompt\":\"hello\",\"sessionId\":\"session-1\","
                                    + "\"maxBudgetUsd\":" + budget + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(result -> assertThat(result.getResponse().getContentType())
                            .contains("application/json")
                            .doesNotContain("text/event-stream"))
                    .andExpect(jsonPath("$.error.code").value(BUDGET_CODE));

            mvc.perform(post("/api/query/conversation")
                            .accept(MediaType.APPLICATION_JSON)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sessionId\":\"session-1\",\"prompt\":\"hello\","
                                    + "\"maxBudgetUsd\":" + budget + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.error.code").value(BUDGET_CODE))
                    .andExpect(jsonPath("$.error.message").value(BUDGET_MESSAGE));
        }

        // 拒绝必须发生在会话创建 / 会话加载 / 运行资格 / 模型调用之前。
        verifyNoInteractions(sessions, projects, engine, modes, gate);
    }

    @Test
    void pureSseAcceptStillReturnsJsonForBudgetRejectionAndSessionNotFound() throws Exception {
        SessionManager sessions = mock(SessionManager.class);
        ProjectWorkspaceService projects = mock(ProjectWorkspaceService.class);
        QueryEngine engine = mock(QueryEngine.class);
        PermissionModeManager modes = mock(PermissionModeManager.class);
        SessionExecutionGate gate = new SessionExecutionGate();
        DataSource ds = mock(DataSource.class);
        when(sessions.dataSourceIdentity()).thenReturn(ds);
        when(sessions.loadSession("missing-session")).thenReturn(Optional.empty());
        MockMvc mvc = mvc(controller(sessions, projects, engine, modes, gate));

        // 纯 SSE Accept + 非空预算：必须仍是约定的 JSON 400（曾因 Accept 协商失败逃逸为 ServletException）。
        mvc.perform(post("/api/query/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ACCEPT, "text/event-stream")
                        .content("{\"prompt\":\"hello\",\"sessionId\":\"session-1\","
                                + "\"maxBudgetUsd\":5.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value(BUDGET_CODE))
                .andExpect(jsonPath("$.error.message").value(BUDGET_MESSAGE));

        // 同一显式 JSON 落点覆盖 session-not-found：纯 SSE Accept 下同样必须返回 JSON 404。
        mvc.perform(post("/api/query/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ACCEPT, "text/event-stream")
                        .content("{\"prompt\":\"hello\",\"sessionId\":\"missing-session\","
                                + "\"maxBudgetUsd\":null}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));

        // 同步入口纯 SSE Accept 走同一 RequestValidationException 落点，同样回归为 JSON 400。
        mvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ACCEPT, "text/event-stream")
                        .content("{\"prompt\":\"hello\",\"sessionId\":\"session-1\","
                                + "\"maxBudgetUsd\":5.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value(BUDGET_CODE));

        verifyNoInteractions(engine);
    }

    @Test
    void explicitNullMaxBudgetUsdStillRunsOriginalSessionResolution() throws Exception {
        SessionManager sessions = mock(SessionManager.class);
        ProjectWorkspaceService projects = mock(ProjectWorkspaceService.class);
        QueryEngine engine = mock(QueryEngine.class);
        PermissionModeManager modes = mock(PermissionModeManager.class);
        SessionExecutionGate gate = new SessionExecutionGate();
        DataSource ds = mock(DataSource.class);
        when(sessions.dataSourceIdentity()).thenReturn(ds);
        when(sessions.loadSession("missing-session")).thenReturn(Optional.empty());
        MockMvc mvc = mvc(controller(sessions, projects, engine, modes, gate));

        mvc.perform(post("/api/query")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"hello\",\"sessionId\":\"missing-session\","
                                + "\"maxBudgetUsd\":null}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));

        mvc.perform(post("/api/query/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ACCEPT, "text/event-stream, application/json")
                        .content("{\"prompt\":\"hello\",\"sessionId\":\"missing-session\","
                                + "\"maxBudgetUsd\":null}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));

        mvc.perform(post("/api/query/conversation")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"missing-session\",\"prompt\":\"hello\","
                                + "\"maxBudgetUsd\":null}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));

        // 显式 null 进入既有会话解析路径(每个请求都查询了会话), 而不是被预算检查拦截。
        verify(sessions, times(3)).loadSession("missing-session");
        verifyNoInteractions(engine);
        assertThat(gate.isBusy(ds, "missing-session")).isFalse();
    }

    private static MockMvc mvc(QueryController controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static QueryController controller(
            SessionManager sessions,
            ProjectWorkspaceService projects,
            QueryEngine engine,
            PermissionModeManager modes,
            SessionExecutionGate gate) {
        return new QueryController(
                engine,
                mock(ToolRegistry.class),
                sessions,
                mock(LlmProviderRegistry.class),
                mock(TokenCounter.class),
                new ObjectMapper(),
                mock(EffectiveSystemPromptBuilder.class),
                modes,
                mock(ModelRegistry.class),
                projects,
                gate);
    }
}
