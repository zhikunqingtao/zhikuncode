package com.aicodeassistant.controller;

import com.aicodeassistant.asr.AsrService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/asr")
public class AsrController {

    private final AsrService asrService;

    public AsrController(AsrService asrService) {
        this.asrService = asrService;
    }

    /** 查询 ASR 服务是否可用 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("available", asrService.isAvailable());
    }

    /** 上传音频进行语音识别 */
    @PostMapping(value = "/recognize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> recognize(@RequestParam("audio") MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            throw new IllegalArgumentException("音频文件不能为空");
        }
        String contentType = audio.getContentType();
        if (contentType == null || !contentType.startsWith("audio/")) {
            throw new IllegalArgumentException("无效的音频类型: " + contentType);
        }
        try {
            String result = asrService.recognize(audio.getBytes(), contentType);
            return ResponseEntity.ok(Map.of("text", result));
        } catch (java.io.IOException e) {
            throw new RuntimeException("读取音频文件失败", e);
        }
    }
}
