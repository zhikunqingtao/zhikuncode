package com.aicodeassistant.controller;

import com.aicodeassistant.model.ProjectConfig;
import com.aicodeassistant.model.UserConfig;
import com.aicodeassistant.service.ConfigService;
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
 * </ul>
 *
 * @see <a href="SPEC §6.1.3">配置管理 API</a>
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
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
