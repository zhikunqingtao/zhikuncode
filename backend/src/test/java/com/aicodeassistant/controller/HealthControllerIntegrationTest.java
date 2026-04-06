package com.aicodeassistant.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HealthController 集成测试 — 验证 4 个健康检查端点。
 */
@WebMvcTest(HealthController.class)
class HealthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // ═══════════════ GET /api/health ═══════════════

    @Test
    @WithMockUser
    @DisplayName("GET /api/health — 综合健康检查返回 status + subsystems")
    void health_shouldReturnComprehensiveStatus() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("ai-code-assistant-backend"))
                .andExpect(jsonPath("$.version").isString())
                .andExpect(jsonPath("$.uptime").isNumber())
                .andExpect(jsonPath("$.java").isString())
                .andExpect(jsonPath("$.subsystems.database.status").value("UP"))
                .andExpect(jsonPath("$.subsystems.jvm.status").isString())
                .andExpect(jsonPath("$.timestamp").isString());
    }

    // ═══════════════ GET /api/health/live ═══════════════

    @Test
    @WithMockUser
    @DisplayName("GET /api/health/live — 存活探针返回 OK")
    void liveness_shouldReturnOk() throws Exception {
        mockMvc.perform(get("/api/health/live"))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));
    }

    // ═══════════════ GET /api/health/ready ═══════════════

    @Test
    @WithMockUser
    @DisplayName("GET /api/health/ready — 就绪探针返回 READY")
    void readiness_shouldReturnReady() throws Exception {
        mockMvc.perform(get("/api/health/ready"))
                .andExpect(status().isOk())
                .andExpect(content().string("READY"));
    }

    // ═══════════════ GET /api/doctor ═══════════════

    @Test
    @WithMockUser
    @DisplayName("GET /api/doctor — 环境诊断返回 checks 数组")
    void doctor_shouldReturnChecks() throws Exception {
        mockMvc.perform(get("/api/doctor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks").isArray())
                .andExpect(jsonPath("$.checks[?(@.name=='java')].status").value("ok"))
                .andExpect(jsonPath("$.checks[?(@.name=='jvm_memory')]").isNotEmpty());
    }
}
