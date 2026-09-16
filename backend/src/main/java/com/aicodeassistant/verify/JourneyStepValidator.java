package com.aicodeassistant.verify;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validate the existing Python journey DSL before starting a server or making requests. */
public final class JourneyStepValidator {
    private static final Set<String> BROWSER = Set.of("navigate", "click", "type", "wait_for",
            "assert_text", "assert_url", "assert_no_console_error", "screenshot");
    private static final Set<String> HTTP = Set.of("http_get", "http_post", "http_put", "http_delete",
            "assert_status", "assert_json", "assert_header", "set_variable");

    private JourneyStepValidator() {}

    public static String validate(List<Map<String, Object>> steps, String mode) {
        Set<String> allowed = "browser".equals(mode) ? BROWSER : HTTP;
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            String action = (String) step.get("action");
            String prefix = "Step " + i + " [" + action + "]: ";
            if (!allowed.contains(action)) return prefix + "unsupported action for " + mode;
            if (step.containsKey("timeout") && (!(step.get("timeout") instanceof Number n)
                    || !Double.isFinite(n.doubleValue()) || n.doubleValue() <= 0)) {
                return prefix + "timeout must be a positive number";
            }
            List<String> required = switch (action) {
                case "navigate", "http_get", "http_post", "http_put", "http_delete" -> List.of("url");
                case "click" -> List.of("selector");
                case "type" -> List.of("selector", "text");
                case "assert_text" -> List.of("selector", "expected");
                case "assert_url" -> List.of("contains");
                case "assert_json" -> List.of("path");
                case "assert_header" -> List.of("name", "contains");
                case "set_variable" -> List.of("name", "from_response_path");
                default -> List.of();
            };
            for (String field : required) {
                if (!(step.get(field) instanceof String s) || (s.isBlank() && !field.equals("text"))) {
                    return prefix + "requires string field '" + field + "'";
                }
            }
            if (action.equals("assert_status") && (!(step.get("expected_code") instanceof Number n)
                    || n.doubleValue() != n.intValue() || n.intValue() < 100 || n.intValue() > 599)) {
                return prefix + "requires expected_code (integer 100..599), for example expected_code: 200";
            }
            if (action.equals("assert_json") && !step.containsKey("expected")) {
                return prefix + "requires expected";
            }
            if (action.equals("wait_for")) {
                if (step.containsKey("js")) return prefix + "js is unsupported; use selector/state or wait_until";
                boolean selector = step.get("selector") instanceof String s && !s.isBlank();
                boolean loadState = step.get("wait_until") instanceof String s
                        && Set.of("load", "domcontentloaded", "networkidle").contains(s);
                if (!selector && !loadState) return prefix + "requires selector or wait_until (load/domcontentloaded/networkidle)";
                if (step.containsKey("state") && (!(step.get("state") instanceof String s)
                        || !Set.of("attached", "detached", "visible", "hidden").contains(s))) {
                    return prefix + "state must be attached, detached, visible or hidden";
                }
            }
        }
        return null;
    }
}
