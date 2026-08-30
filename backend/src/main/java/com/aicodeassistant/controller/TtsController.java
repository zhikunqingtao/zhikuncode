package com.aicodeassistant.controller;

import com.aicodeassistant.tts.TtsService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/tts")
public class TtsController {

    private final TtsService ttsService;

    public TtsController(TtsService ttsService) {
        this.ttsService = ttsService;
    }

    /** 查询 TTS 服务是否可用 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("available", ttsService.isAvailable());
    }

    /** 提交文本进行语音合成，返回音频文件 URL */
    @PostMapping(value = "/synthesize", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> synthesize(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("文本不能为空");
        }
        return ResponseEntity.ok(Map.of("audioUrl", ttsService.synthesize(text)));
    }
}
