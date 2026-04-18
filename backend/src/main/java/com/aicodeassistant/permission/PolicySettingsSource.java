package com.aicodeassistant.permission;

import com.aicodeassistant.model.PermissionBehavior;
import com.aicodeassistant.model.PermissionRule;
import com.aicodeassistant.model.PermissionRuleSource;
import com.aicodeassistant.model.PermissionRuleValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 企业策略权限规则源 — 从 policy.json 加载企业级(MDM)权限规则。
 * <p>
 * policy.json 格式示例:
 * <pre>
 * {
 *   "permissions": {
 *     "allow": [
 *       {"tool": "FileRead", "rule": null},
 *       {"tool": "Bash", "rule": "git *"}
 *     ],
 *     "deny": [
 *       {"tool": "Bash", "rule": "rm *"}
 *     ],
 *     "ask": [
 *       {"tool": "FileWrite"}
 *     ]
 *   }
 * }
 * </pre>
 * <p>
 * 企业策略优先级高于用户设置和项目设置。
 *
 * @see <a href="SPEC §4.9">权限规则来源</a>
 */
@Component
public class PolicySettingsSource {

    private static final Logger log = LoggerFactory.getLogger(PolicySettingsSource.class);

    private final ObjectMapper objectMapper;
    private volatile List<PermissionRule> cachedRules = Collections.emptyList();
    private final AtomicLong lastModified = new AtomicLong(0L);

    public PolicySettingsSource(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从指定路径加载企业策略规则。
     *
     * @param policyFilePath policy.json 文件路径
     * @return 加载的规则列表
     */
    public List<PermissionRule> loadRules(Path policyFilePath) {
        if (policyFilePath == null || !Files.exists(policyFilePath)) {
            log.debug("Policy file not found: {}", policyFilePath);
            return Collections.emptyList();
        }

        try {
            long currentModified = Files.getLastModifiedTime(policyFilePath).toMillis();
            if (currentModified == lastModified.get() && !cachedRules.isEmpty()) {
                return cachedRules;
            }

            String content = Files.readString(policyFilePath);
            JsonNode root = objectMapper.readTree(content);
            JsonNode permissions = root.path("permissions");

            List<PermissionRule> rules = new ArrayList<>();

            // 解析 allow 规则
            parseRuleArray(permissions.path("allow"), PermissionBehavior.ALLOW, rules);
            // 解析 deny 规则
            parseRuleArray(permissions.path("deny"), PermissionBehavior.DENY, rules);
            // 解析 ask 规则
            parseRuleArray(permissions.path("ask"), PermissionBehavior.ASK, rules);

            cachedRules = Collections.unmodifiableList(rules);
            lastModified.set(currentModified);

            log.info("Loaded {} enterprise policy rules from {}", rules.size(), policyFilePath);
            return cachedRules;

        } catch (IOException e) {
            log.error("Failed to load policy file: {}", policyFilePath, e);
            return cachedRules.isEmpty() ? Collections.emptyList() : cachedRules;
        }
    }

    /**
     * 获取默认策略文件路径。
     * 在 macOS 上为 /Library/Application Support/zhikuncode/policy.json
     * 在 Linux 上为 /etc/zhikuncode/policy.json
     */
    public Path getDefaultPolicyPath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return Path.of("/Library/Application Support/zhikuncode/policy.json");
        } else if (os.contains("win")) {
            return Path.of(System.getenv("ProgramData"), "zhikuncode", "policy.json");
        } else {
            return Path.of("/etc/zhikuncode/policy.json");
        }
    }

    /**
     * 强制重新加载（清除缓存）。
     */
    public void invalidateCache() {
        lastModified.set(0L);
        cachedRules = Collections.emptyList();
    }

    // ── 内部解析 ──────────────────────────────────────────────

    private void parseRuleArray(JsonNode array, PermissionBehavior behavior, List<PermissionRule> rules) {
        if (array == null || !array.isArray()) return;

        for (JsonNode item : array) {
            String toolName = item.path("tool").asText(null);
            if (toolName == null || toolName.isBlank()) continue;

            String ruleContent = item.has("rule") && !item.get("rule").isNull()
                    ? item.get("rule").asText() : null;

            rules.add(new PermissionRule(
                    PermissionRuleSource.POLICY_SETTINGS,
                    behavior,
                    new PermissionRuleValue(toolName, ruleContent)
            ));
        }
    }
}
