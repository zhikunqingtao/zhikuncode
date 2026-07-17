package com.aicodeassistant.permission;

import com.aicodeassistant.model.PermissionBehavior;
import com.aicodeassistant.model.PermissionContext;
import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.model.PermissionRule;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only aggregation of administrator policy and plugin permission rules.
 *
 * <p>User decisions are deliberately excluded. Their sole authority is
 * {@link PersistentPermissionGrantStore}; keeping another mutable in-memory
 * rule map would permit broad rules to bypass exact grant matching.</p>
 */
@Repository
public class PermissionRuleRepository {

    private final PolicySettingsSource policySettingsSource;
    private final PluginSettingsSource pluginSettingsSource;

    public PermissionRuleRepository(PolicySettingsSource policySettingsSource,
                                    PluginSettingsSource pluginSettingsSource) {
        this.policySettingsSource = policySettingsSource;
        this.pluginSettingsSource = pluginSettingsSource;
    }

    public Map<String, List<PermissionRule>> getAllowRules() {
        return rulesFor(PermissionBehavior.ALLOW);
    }

    public Map<String, List<PermissionRule>> getDenyRules() {
        return rulesFor(PermissionBehavior.DENY);
    }

    public Map<String, List<PermissionRule>> getAskRules() {
        return rulesFor(PermissionBehavior.ASK);
    }

    public PermissionContext buildContext(PermissionMode mode,
                                          boolean isBypassAvailable,
                                          boolean isAutoAvailable) {
        return new PermissionContext(
                mode,
                java.util.Set.of(),
                getAllowRules(),
                getDenyRules(),
                getAskRules(),
                isBypassAvailable,
                isAutoAvailable
        );
    }

    private Map<String, List<PermissionRule>> rulesFor(PermissionBehavior behavior) {
        Map<String, List<PermissionRule>> grouped = new LinkedHashMap<>();
        append(grouped, policySettingsSource.loadRules(policySettingsSource.getDefaultPolicyPath()), behavior);
        append(grouped, pluginSettingsSource.getRules(), behavior);
        grouped.replaceAll((ignored, rules) -> List.copyOf(rules));
        return Collections.unmodifiableMap(grouped);
    }

    private static void append(Map<String, List<PermissionRule>> grouped,
                               List<PermissionRule> candidates,
                               PermissionBehavior behavior) {
        for (PermissionRule rule : candidates) {
            if (rule.ruleBehavior() == behavior) {
                grouped.computeIfAbsent(rule.ruleValue().toolName(), ignored -> new ArrayList<>())
                        .add(rule);
            }
        }
    }
}
