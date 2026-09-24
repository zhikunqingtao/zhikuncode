package com.aicodeassistant.controller;

import com.aicodeassistant.model.ProjectConfig;
import com.aicodeassistant.model.UserConfig;
import com.aicodeassistant.service.ConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ConfigController — 配置管理 REST API。
 * <p>
 * 端点:
 * <ul>
 *   <li>GET /api/config — 获取用户全局配置</li>
 *   <li>PUT /api/config — 更新用户全局配置 (PATCH 语义)</li>
 *   <li>GET /api/config/project — 获取项目级配置</li>
 *   <li>PUT /api/config/project — 更新项目级配置</li>
 *   <li>GET /api/config/delete-confirm — 删除会话是否需要验证码</li>
 * </ul>
 *
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService configService;

    /**
     * 删除会话二次确认验证码（环境变量 ZHIKUN_DELETE_CONFIRM_CODE）。
     * 仅用于判断是否要求验证码——任何接口都不得返回验证码值本身。
     */
    private final String deleteConfirmCode;

    public ConfigController(ConfigService configService,
                            @Value("${zhikun.delete-confirm-code:}") String deleteConfirmCode) {
        this.configService = configService;
        this.deleteConfirmCode = deleteConfirmCode;
    }

    /**
     * 获取用户全局配置。
     */
    @GetMapping
    public ResponseEntity<UserConfig> getUserConfig() {
        return ResponseEntity.ok(configService.getUserConfig());
    }

    /**
     * 更新用户全局配置 — 支持部分更新 (PATCH 语义)。
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> updateUserConfig(
            @RequestBody Map<String, Object> updates) {
        UserConfig updated = configService.updateUserConfig(updates);
        return ResponseEntity.ok(Map.of("success", true, "config", updated));
    }

    /**
     * 删除会话是否需要验证码 — 只返回 required 布尔值，绝不暴露验证码本身。
     */
    @GetMapping("/delete-confirm")
    public ResponseEntity<Map<String, Boolean>> getDeleteConfirmConfig() {
        return ResponseEntity.ok(Map.of("required", deleteConfirmCode != null && !deleteConfirmCode.isBlank()));
    }

    /**
     * 获取项目级配置。
     */
    @GetMapping("/project")
    public ResponseEntity<ProjectConfig> getProjectConfig() {
        return ResponseEntity.ok(configService.getProjectConfig());
    }

    /**
     * 更新项目级配置。
     */
    @PutMapping("/project")
    public ResponseEntity<Map<String, Object>> updateProjectConfig(
            @RequestBody Map<String, Object> updates) {
        ProjectConfig updated = configService.updateProjectConfig(updates);
        return ResponseEntity.ok(Map.of("success", true, "config", updated));
    }
}
