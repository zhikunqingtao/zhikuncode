package com.aicodeassistant.controller;

import com.aicodeassistant.asr.AsrService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
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

    /**
     * 上传音频进行语音识别。
     * <p>
     * context 为可选的识别上下文（最近对话文本，含中文）。application.yml 未配置
     * server.servlet.encoding 强制 UTF-8，multipart 文本字段经容器按 @RequestParam
     * 解码可能乱码，故以 byte[] 原始字节接收并按 UTF-8 解码（浏览器 FormData
     * 文本部分的原始字节即 UTF-8，绕开容器解码最可靠）。
     */
    @PostMapping(value = "/recognize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> recognize(
            @RequestParam("audio") MultipartFile audio,
            @RequestPart(value = "context", required = false) byte[] contextBytes) {
        if (audio == null || audio.isEmpty()) {
            throw new IllegalArgumentException("音频文件不能为空");
        }
        String contentType = audio.getContentType();
        if (contentType == null || !contentType.startsWith("audio/")) {
            throw new IllegalArgumentException("无效的音频类型: " + contentType);
        }
        String context = (contextBytes != null && contextBytes.length > 0)
                ? new String(contextBytes, StandardCharsets.UTF_8)
                : null;
        try {
            String result = asrService.recognize(audio.getBytes(), contentType, context);
            return ResponseEntity.ok(Map.of("text", result));
        } catch (java.io.IOException e) {
            throw new RuntimeException("读取音频文件失败", e);
        }
    }
}
