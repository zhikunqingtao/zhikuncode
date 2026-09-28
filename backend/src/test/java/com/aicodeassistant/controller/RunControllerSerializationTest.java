package com.aicodeassistant.controller;

import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.run.RunEnvelopeRepository;
import com.aicodeassistant.run.RunEventRepository;
import com.aicodeassistant.run.RunTerminationCoordinator;
import com.aicodeassistant.security.SessionAccessAuthorizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Run API 序列化契约：observed usage（R-03 usageStatus/退出原因）与范围说明（R-13
 * verificationScope）必须出现在真实 HTTP 响应中。
 */
@WebMvcTest(RunController.class)
class RunControllerSerializationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RunEnvelopeRepository envelopes;

    @MockitoBean
    private RunEventRepository events;

    @MockitoBean
    private SessionAccessAuthorizer access;

    @MockitoBean
    private RunTerminationCoordinator termination;

    @Test
    @WithMockUser
    void runDetailExposesObservedUsageStatusAndLimitedVerificationScope() throws Exception {
        Instant now = Instant.parse("2026-09-27T10:00:00Z");
        RunEnvelope run = new RunEnvelope(
                "run-1", "session-1", null, RunEnvelope.RunStatus.FAILED,
                "main", "kimi-k3", null, now, now, null,
                123, 0.5, 2, 3, "MAX_TURNS: maximum turn count reached",
                now, now, 4,
                RunEnvelope.RunExitReason.MAX_TURNS, null,
                RunEnvelope.VerificationStatus.VERIFIED, RunEnvelope.UsageStatus.PARTIAL,
                now, null);
        when(access.accessibleRun("run-1", "session-1")).thenReturn(Optional.of(run));

        mockMvc.perform(get("/api/runs/run-1").header("X-Session-Id", "session-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usageStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.verificationScope").value("artifact_manifest"))
                .andExpect(jsonPath("$.totalTokens").value(123))
                .andExpect(jsonPath("$.exitReason").value("MAX_TURNS"));
    }
}
