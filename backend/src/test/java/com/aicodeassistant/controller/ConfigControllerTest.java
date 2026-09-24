package com.aicodeassistant.controller;

import com.aicodeassistant.service.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * GET /api/config/delete-confirm — 只返回是否需要验证码（required 布尔值），
 * 任何情况下都不得暴露验证码值本身。
 */
class ConfigControllerTest {

    @Test
    void reportsRequiredWhenCodeConfigured() {
        ConfigController controller = new ConfigController(mock(ConfigService.class), "secret");

        var response = controller.getDeleteConfirmConfig();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("required", true);
    }

    @Test
    void reportsNotRequiredWhenCodeBlank() {
        ConfigController controller = new ConfigController(mock(ConfigService.class), "  ");

        var response = controller.getDeleteConfirmConfig();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("required", false);
    }

    @Test
    void reportsNotRequiredWhenCodeEmpty() {
        // 未配置 ZHIKUN_DELETE_CONFIRM_CODE 时注入默认值 ""
        ConfigController controller = new ConfigController(mock(ConfigService.class), "");

        var response = controller.getDeleteConfirmConfig();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("required", false);
    }

    @Test
    void responseBodyDoesNotExposeCodeValue() throws Exception {
        ConfigController controller = new ConfigController(mock(ConfigService.class), "super-secret-code");

        var response = controller.getDeleteConfirmConfig();

        // 响应体只有 required 字段，序列化后的 JSON 不含验证码值本身
        assertThat(response.getBody()).containsOnlyKeys("required");
        String json = new ObjectMapper().writeValueAsString(response.getBody());
        assertThat(json).doesNotContain("super-secret-code");
    }
}
