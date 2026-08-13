package com.aicodeassistant.workbench;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 保守地把最终 Markdown 回复投影为结果区块；原文始终另外保留。 */
@Component
public final class StructuredSummaryExtractor {
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+(.+?)\\s*$");
    private static final Pattern LIST = Pattern.compile("^\\s*(?:[-*+]\\s+|\\d+[.)、．]\\s*)(.+?)\\s*$");

    public Summary extract(String markdown) {
        String text = markdown == null ? "" : markdown
                .replaceFirst("(?i)^\\s*\\[(?:skeleton|final)]\\s*", "").strip();
        if (text.isBlank()) return new Summary(null, List.of(), List.of(), List.of());

        String conclusion = firstParagraph(text);
        List<String> completed = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        List<String> next = new ArrayList<>();
        Target target = Target.NONE;
        boolean fenced = false;
        for (String line : text.split("\\R")) {
            if (line.stripLeading().startsWith("```")) {
                fenced = !fenced;
                continue;
            }
            if (fenced) continue;
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                target = targetFor(heading.group(1));
                continue;
            }
            if (target == Target.NONE || line.isBlank()) continue;
            Matcher item = LIST.matcher(line);
            String value = item.matches() ? item.group(1).strip() : line.strip();
            if (value.isBlank() || value.startsWith("#")) continue;
            target.list(completed, issues, next).add(value);
        }
        if (next.isEmpty()) {
            String last = lastParagraph(text);
            if (last != null && (last.endsWith("？") || last.endsWith("?"))) next.add(last);
        }
        return new Summary(conclusion, List.copyOf(completed),
                List.copyOf(issues), List.copyOf(next));
    }

    private static String firstParagraph(String text) {
        boolean fenced = false;
        StringBuilder paragraph = new StringBuilder();
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) { fenced = !fenced; continue; }
            if (fenced || trimmed.isBlank()) {
                if (!paragraph.isEmpty()) break;
                continue;
            }
            if (HEADING.matcher(line).matches() || LIST.matcher(line).matches()) {
                if (!paragraph.isEmpty()) break;
                continue;
            }
            if (!paragraph.isEmpty()) paragraph.append(' ');
            paragraph.append(trimmed);
        }
        return paragraph.isEmpty() ? null : paragraph.toString();
    }

    private static String lastParagraph(String text) {
        String[] paragraphs = text.split("(?:\\R\\s*){2,}");
        for (int index = paragraphs.length - 1; index >= 0; index--) {
            String value = paragraphs[index].strip();
            if (!value.isBlank() && !value.startsWith("```")
                    && !HEADING.matcher(value).matches()) return value;
        }
        return null;
    }

    private static Target targetFor(String raw) {
        String value = raw.replaceAll("[*_`：:]", "").strip().toLowerCase(Locale.ROOT);
        if (containsAny(value, "已完成", "完成内容", "改动", "结果", "交付")) return Target.COMPLETED;
        if (containsAny(value, "问题", "风险", "未完成", "限制", "待解决")) return Target.ISSUES;
        if (containsAny(value, "下一步", "建议", "后续")) return Target.NEXT;
        return Target.NONE;
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private enum Target {
        NONE, COMPLETED, ISSUES, NEXT;
        List<String> list(List<String> completed, List<String> issues, List<String> next) {
            return switch (this) {
                case COMPLETED -> completed;
                case ISSUES -> issues;
                case NEXT -> next;
                case NONE -> throw new IllegalStateException("No summary target");
            };
        }
    }

    public record Summary(String conclusion, List<String> completed,
                          List<String> issues, List<String> nextSteps) { }
}
