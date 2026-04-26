package com.aicodeassistant.controller;

import com.aicodeassistant.exception.ResourceNotFoundException;
import com.aicodeassistant.skill.SkillRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRegistry skillRegistry;

    public SkillController(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    /** 列出所有已注册技能（用于 CommandPalette 动态加载） */
    @GetMapping
    public List<Map<String, String>> listSkills() {
        return skillRegistry.getAllSkills().stream()
            .map(s -> Map.of(
                "name", s.effectiveName(),
                "description", s.effectiveDescription(),
                "source", s.source().name()
            ))
            .toList();
    }

    /** 获取单个技能详情（用于技能详情弹窗） */
    @GetMapping("/{name}")
    public ResponseEntity<Map<String, Object>> getSkillDetail(@PathVariable String name) {
        return skillRegistry.getAllSkills().stream()
            .filter(s -> s.effectiveName().equals(name))
            .findFirst()
            .map(s -> ResponseEntity.ok(Map.<String, Object>of(
                "name", s.effectiveName(),
                "description", s.effectiveDescription(),
                "source", s.source().name(),
                "content", s.content(),
                "filePath", s.filePath() != null ? s.filePath() : ""
            )))
            .orElseThrow(() -> new ResourceNotFoundException(
                "SKILL_NOT_FOUND", "Skill not found: " + name));
    }
}
