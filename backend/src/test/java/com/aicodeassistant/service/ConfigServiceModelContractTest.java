package com.aicodeassistant.service;

import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.model.UserConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ConfigServiceModelContractTest {

    @Test
    void returnsEffectiveFallbackForStaleDefaultWithoutWritingDatabase() throws Exception {
        JdbcTemplate global = mock(JdbcTemplate.class);
        LlmProviderRegistry providers = mock(LlmProviderRegistry.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        UserConfig stored = new UserConfig(
                "localhost", null, null, "retired-model", Map.of(),
                "dark", "zh-CN", PermissionMode.DEFAULT, List.of(), List.of(),
                Map.of(), false, true, 80);
        when(global.queryForList(anyString(), eq(String.class), eq("user_config")))
                .thenReturn(List.of(objectMapper.writeValueAsString(stored)));
        when(providers.supportsModel("retired-model")).thenReturn(false);
        when(providers.getDefaultModel()).thenReturn("available-model");
        ConfigService service = service(global, objectMapper, providers);

        UserConfig effective = service.getUserConfig();

        assertThat(effective.defaultModel()).isEqualTo("available-model");
        verify(global).queryForList(anyString(), eq(String.class), eq("user_config"));
        verifyNoMoreInteractions(global);
    }

    private static ConfigService service(
            JdbcTemplate global, ObjectMapper objectMapper,
            LlmProviderRegistry providers) {
        return new ConfigService(global, mock(JdbcTemplate.class), objectMapper,
                mock(SqliteConfig.class), providers);
    }
}
