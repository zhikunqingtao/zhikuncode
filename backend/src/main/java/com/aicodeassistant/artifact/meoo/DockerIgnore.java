package com.aicodeassistant.artifact.meoo;

import java.util.*;
import java.util.regex.Pattern;

/** Git-style ordered ignore rules, including negation and parent-directory exclusion. */
final class DockerIgnore {
    private record Rule(Pattern pattern, boolean negate, boolean directoryOnly) {}
    private final List<Rule> rules = new ArrayList<>();
    DockerIgnore(List<String> lines) {
        for (String line : lines) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#") || s.equals(".")) continue;
            boolean negate = s.startsWith("!");
            if (negate) s = s.substring(1);
            boolean directory = s.endsWith("/");
            if (directory) s = s.substring(0, s.length()-1);
            boolean anchored = s.startsWith("/");
            if (anchored) s = s.substring(1);
            if (s.isEmpty()) continue;
            StringBuilder regex = new StringBuilder(anchored || s.contains("/") ? "^" : "^(?:.*/)?");
            for (int i=0; i<s.length(); i++) {
                char c=s.charAt(i);
                if(c=='*') {
                    if(i+1<s.length() && s.charAt(i+1)=='*') {
                        i++;
                        if(i+1<s.length() && s.charAt(i+1)=='/') { i++; regex.append("(?:.*/)?"); }
                        else regex.append(".*");
                    } else regex.append("[^/]*");
                } else if(c=='?') regex.append("[^/]");
                else if(c=='[' || c=='\\') throw new MeooException("MEOO_IGNORE_RULE_UNSUPPORTED");
                else regex.append(Pattern.quote(String.valueOf(c)));
            }
            // CLI 0.5.4 uses npm ignore with its default ignorecase=true.
            rules.add(new Rule(Pattern.compile(regex+"$",Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),negate,directory));
        }
    }
    boolean ignored(String path, boolean directory) {
        String[] parts=path.split("/");
        String parent="";
        for(int i=0;i<parts.length;i++) {
            parent += (i==0?"":"/")+parts[i];
            boolean isDir=i<parts.length-1 || directory, ignored=false;
            for(Rule rule:rules)
                if((isDir || !rule.directoryOnly()) && rule.pattern().matcher(parent).matches()) ignored=!rule.negate();
            if(ignored) return true;
        }
        return false;
    }
}
