package com.aicodeassistant.controller;

import com.aicodeassistant.tts.TtsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TtsController 单元测试。
 */
@WebMvcTest(TtsController.class)
class TtsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TtsService ttsService;

    // ═══════════════ GET /api/tts/status ═══════════════

    @Test
    @WithMockUser
    @DisplayName("GET /api/tts/status — TTS 可用时返回 available=true")
    void status_whenAvailable_shouldReturnTrue() throws Exception {
        when(ttsService.isAvailable()).thenReturn(true);

        mockMvc.perform(get("/api/tts/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/tts/status — TTS 不可用时返回 available=false")
    void status_whenUnavailable_shouldReturnFalse() throws Exception {
        when(ttsService.isAvailable()).thenReturn(false);

        mockMvc.perform(get("/api/tts/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
    }

    // ═══════════════ POST /api/tts/synthesize ═══════════════

    @Test
    @WithMockUser
    @DisplayName("POST /api/tts/synthesize — 正常文本返回音频 URL")
    void synthesize_withValidText_shouldReturnAudioUrl() throws Exception {
        when(ttsService.synthesize(anyString())).thenReturn("http://xxx.wav");

        mockMvc.perform(post("/api/tts/synthesize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"你好\"}")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audioUrl").value("http://xxx.wav"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/tts/synthesize — 空文本返回 400")
    void synthesize_withEmptyText_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/tts/synthesize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"\"}")
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
