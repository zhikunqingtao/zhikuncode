package com.aicodeassistant.controller;

import com.aicodeassistant.exception.ResourceNotFoundException;
import com.aicodeassistant.skill.SkillRegistry;
import com.aicodeassistant.skill.SkillDefinition;
import com.aicodeassistant.skill.SkillStateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.LinkedHashMap;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRegistry skillRegistry;

    public SkillController(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    /** 运行时目录：仅返回启用技能（用于 CommandPalette 动态加载）。 */
    @GetMapping
    public List<Map<String, String>> listSkills() {
        return skillRegistry.getEnabledSkills().stream()
            .map(s -> Map.of(
                "name", s.effectiveName(),
                "description", s.effectiveDescription(),
                "source", s.source().name()
            ))
            .toList();
    }

    /** 获取单个技能详情（用于技能详情弹窗） */
    @GetMapping({"/{name}", "/detail/{name}"})
    public ResponseEntity<Map<String, Object>> getSkillDetail(@PathVariable String name) {
        SkillDefinition skill = skillRegistry.resolve(name);
        if (skill == null) throw new ResourceNotFoundException("SKILL_NOT_FOUND", "Skill not found: " + name);
        return ResponseEntity.ok(detail(skill));
    }

    /** 管理目录保留关闭项，供用户重新启用。 */
    @GetMapping("/manage")
    public Map<String, Object> listManagedSkills() {
        List<Map<String, Object>> items = skillRegistry.getAllSkills().stream()
                .sorted(Comparator.comparing(SkillDefinition::id))
                .map(this::item).toList();
        long enabledCount = items.stream().filter(s -> Boolean.TRUE.equals(s.get("enabled"))).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skills", items);
        result.put("total", items.size());
        result.put("enabledCount", enabledCount);
        result.put("stateError", skillRegistry.getStateError());
        return result;
    }

    @GetMapping("/manage/{id}")
    public Map<String, Object> getManagedSkillDetail(@PathVariable String id) {
        return detail(skillRegistry.getManagedSkill(id));
    }

    @PatchMapping("/manage/{id}/toggle")
    public Map<String, Object> toggleSkill(@PathVariable String id, @RequestParam boolean enabled) {
        return item(skillRegistry.setEnabled(id, enabled));
    }

    @ExceptionHandler(SkillStateService.SettingsUnavailableException.class)
    public ResponseEntity<Map<String, Object>> settingsUnavailable(SkillStateService.SettingsUnavailableException error) {
        return ResponseEntity.status(503).body(Map.of("error", Map.of(
                "code", "SKILL_STATE_UNAVAILABLE", "message", error.getMessage())));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> invalidParameter(MethodArgumentTypeMismatchException error) {
        return ResponseEntity.badRequest().body(Map.of("error", Map.of(
                "code", "INVALID_PARAMETER", "message", "参数 " + error.getName() + " 格式无效")));
    }

    @ExceptionHandler(SkillStateService.SettingsSaveException.class)
    public ResponseEntity<Map<String, Object>> settingsSaveFailed(SkillStateService.SettingsSaveException error) {
        return ResponseEntity.status(500).body(Map.of("error", Map.of(
                "code", "SKILL_SETTINGS_SAVE_FAILED", "message", error.getMessage())));
    }

    private Map<String, Object> item(SkillDefinition skill) {
        return Map.of("id", skill.id(), "name", skill.effectiveName(),
                "description", skill.effectiveDescription(), "source", skill.source().name(),
                "enabled", skillRegistry.isEnabled(skill));
    }

    private Map<String, Object> detail(SkillDefinition skill) {
        Map<String, Object> detail = new LinkedHashMap<>(item(skill));
        detail.put("content", skill.content());
        detail.put("filePath", skill.filePath() == null ? "" : skill.filePath());
        return detail;
    }
}
