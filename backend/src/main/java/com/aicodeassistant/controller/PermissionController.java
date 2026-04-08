package com.aicodeassistant.controller;

import com.aicodeassistant.model.PermissionBehavior;
import com.aicodeassistant.model.PermissionRule;
import com.aicodeassistant.model.PermissionRuleSource;
import com.aicodeassistant.model.PermissionRuleValue;
import com.aicodeassistant.permission.PermissionRuleRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Stream;

/**
 * 权限规则管理 Controller — CRUD 权限规则。
 *
 * @see <a href="SPEC §6.1.8 #13-#14">权限规则端点</a>
 */
@RestController
@RequestMapping("/api/permissions/rules")
public class PermissionController {

    private final PermissionRuleRepository ruleRepository;

    public PermissionController(PermissionRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    /** 获取权限规则列表 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getRules(
            @RequestParam(defaultValue = "global") String scope) {
        // 合并所有规则并返回
        List<RuleDto> rules = new ArrayList<>();

        // allow rules
        ruleRepository.getAllowRules().forEach((toolName, ruleList) ->
                ruleList.forEach(r -> rules.add(new RuleDto(
                        UUID.randomUUID().toString(),
                        r.ruleValue().toolName(),
                        r.ruleValue().ruleContent(),
                        "allow",
                        scopeFromSource(r.source()),
                        null))));

        // deny rules
        ruleRepository.getDenyRules().forEach((toolName, ruleList) ->
                ruleList.forEach(r -> rules.add(new RuleDto(
                        UUID.randomUUID().toString(),
                        r.ruleValue().toolName(),
                        r.ruleValue().ruleContent(),
                        "deny",
                        scopeFromSource(r.source()),
                        null))));

        // Filter by scope
        List<RuleDto> filtered = rules.stream()
                .filter(r -> "all".equals(scope) || scope.equals(r.scope()))
                .toList();

        return ResponseEntity.ok(Map.of("rules", filtered));
    }

    /** 更新权限规则（批量覆盖） */
    @PutMapping
    public ResponseEntity<Map<String, Object>> updateRules(
            @RequestBody UpdateRulesRequest request) {
        // 清除对应 scope 的规则并重新添加
        if ("global".equals(request.scope())) {
            ruleRepository.clearAll();
        } else if ("session".equals(request.scope())) {
            ruleRepository.clearSessionRules();
        }

        for (RuleDto rule : request.rules()) {
            PermissionRuleSource source = "session".equals(rule.scope())
                    ? PermissionRuleSource.USER_SESSION
                    : PermissionRuleSource.USER_GLOBAL;
            PermissionRuleValue value = new PermissionRuleValue(
                    rule.toolName(), rule.ruleContent());
            PermissionRule permRule = new PermissionRule(
                    source,
                    "allow".equals(rule.decision()) ? PermissionBehavior.ALLOW : PermissionBehavior.DENY,
                    value);

            if ("allow".equals(rule.decision())) {
                ruleRepository.addAllowRule(permRule);
            } else {
                ruleRepository.addDenyRule(permRule);
            }
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "rules", request.rules()));
    }

    private String scopeFromSource(PermissionRuleSource source) {
        return switch (source) {
            case USER_SESSION, SESSION -> "session";
            case USER_GLOBAL, USER_SETTINGS -> "global";
            case PROJECT_SETTINGS, USER_PROJECT -> "project";
            default -> "global";
        };
    }

    // ═══ DTO Records ═══
    public record RuleDto(String id, String toolName, String ruleContent,
                          String decision, String scope, String createdAt) {}
    public record UpdateRulesRequest(String scope, List<RuleDto> rules) {}
}
