package com.aicodeassistant.controller;

import com.aicodeassistant.asr.AsrService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AsrController 单元测试。
 */
@WebMvcTest(AsrController.class)
class AsrControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AsrService asrService;

    // ═══════════════ GET /api/asr/status ═══════════════

    @Test
    @WithMockUser
    @DisplayName("GET /api/asr/status — ASR 可用时返回 available=true")
    void status_whenAvailable_shouldReturnTrue() throws Exception {
        when(asrService.isAvailable()).thenReturn(true);

        mockMvc.perform(get("/api/asr/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/asr/status — ASR 不可用时返回 available=false")
    void status_whenUnavailable_shouldReturnFalse() throws Exception {
        when(asrService.isAvailable()).thenReturn(false);

        mockMvc.perform(get("/api/asr/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
    }

    // ═══════════════ POST /api/asr/recognize ═══════════════

    @Test
    @WithMockUser
    @DisplayName("POST /api/asr/recognize — 正常音频返回识别文本")
    void recognize_withValidAudio_shouldReturnText() throws Exception {
        when(asrService.recognize(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("audio/webm")))
                .thenReturn("你好世界");

        MockMultipartFile audioFile = new MockMultipartFile(
                "audio", "test.webm", "audio/webm", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/asr/recognize").file(audioFile).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("你好世界"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/asr/recognize — 空音频文件返回 400")
    void recognize_withEmptyAudio_shouldReturn400() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "audio", "empty.webm", "audio/webm", new byte[0]);

        mockMvc.perform(multipart("/api/asr/recognize").file(emptyFile).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
