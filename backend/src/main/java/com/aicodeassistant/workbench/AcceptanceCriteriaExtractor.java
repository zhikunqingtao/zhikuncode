package com.aicodeassistant.workbench;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从用户原始要求中保守提取可追踪条款，不进行语义改写。 */
@Component
public final class AcceptanceCriteriaExtractor {
    private static final int MAX_CRITERIA = 20;
    private static final Pattern LIST_ITEM = Pattern.compile(
            "^\\s*(?:[-*+]\\s+|\\d{1,3}[.)、．]\\s*)(.+?)\\s*$");
    private static final Pattern CONSTRAINT = Pattern.compile(
            "必须|需要|不要|不得|至少|确保");

    public List<String> extract(String requestText) {
        if (requestText == null || requestText.isBlank()) return List.of();
        List<String> ordered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String line : requestText.split("\\R")) {
            Matcher item = LIST_ITEM.matcher(line);
            if (!item.matches()) continue;
            add(ordered, seen, item.group(1));
            if (ordered.size() >= MAX_CRITERIA) return List.copyOf(ordered);
        }

        String normalized = requestText.replace('\r', '\n');
        for (String sentence : normalized.split("(?<=[。！？!?；;])|\\n+")) {
            String candidate = sentence.strip();
            Matcher item = LIST_ITEM.matcher(candidate);
            if (item.matches()) candidate = item.group(1).strip();
            if (!CONSTRAINT.matcher(candidate).find()) continue;
            add(ordered, seen, candidate);
            if (ordered.size() >= MAX_CRITERIA) break;
        }
        return List.copyOf(ordered);
    }

    private static void add(List<String> ordered, Set<String> seen, String value) {
        String text = value == null ? "" : value.strip();
        if (text.isBlank() || text.startsWith("#") || text.length() > 1000) return;
        String key = text.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (seen.add(key)) ordered.add(text);
    }
}
