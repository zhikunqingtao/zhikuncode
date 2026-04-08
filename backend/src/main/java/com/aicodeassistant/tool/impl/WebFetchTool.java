package com.aicodeassistant.tool.impl;

import com.aicodeassistant.tool.*;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * WebFetchTool — 获取 URL 内容并转换 HTML 为 Markdown。
 * <p>
 * 使用 OkHttp 进行 HTTP 请求，Jsoup 解析 HTML 并转换为 Markdown。
 * 100K 字符 Markdown 内容截断限制, 10MB HTTP 响应上限。
 *
 * @see <a href="SPEC §3.2.3">WebFetchTool 规范</a>
 */
@Component
public class WebFetchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);
    private static final int MAX_CONTENT_CHARS = 100_000;
    private static final int TIMEOUT_SECONDS = 30;
    private static final long MAX_RESPONSE_BYTES = 10_485_760; // 10MB (SPEC: MAX_HTTP_CONTENT)

    private final OkHttpClient httpClient;

    public WebFetchTool() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    @Override
    public String getName() {
        return "WebFetch";
    }

    @Override
    public String getDescription() {
        return "Fetch the content of a URL and convert HTML to Markdown for easy reading.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "url", Map.of("type", "string", "description", "The URL to fetch"),
                        "prompt", Map.of("type", "string", "description", "Content extraction hint")
                ),
                "required", List.of("url")
        );
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        return true;
    }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.CONDITIONAL;
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String url = input.getString("url");
        long start = System.currentTimeMillis();

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "AI-Code-Assistant/1.0")
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .build();

        try {
            Response response = httpClient.newCall(request).execute();
            int statusCode = response.code();
            String contentType = response.header("Content-Type", "text/html");

            byte[] bodyBytes;
            try (ResponseBody body = response.body()) {
                if (body == null) {
                    return ToolResult.error("Empty response from " + url);
                }
                bodyBytes = body.bytes();
                if (bodyBytes.length > MAX_RESPONSE_BYTES) {
                    return ToolResult.error("Response too large: " + bodyBytes.length + " bytes");
                }
            }

            // HTML → Markdown 转换
            String rawHtml = new String(bodyBytes);
            String markdown;
            if (contentType != null && (contentType.contains("text/html") || contentType.contains("xhtml"))) {
                Document doc = Jsoup.parse(rawHtml);
                doc.select("script, style, nav, footer, header, aside, iframe").remove();
                markdown = htmlToMarkdown(doc.body());
            } else {
                markdown = rawHtml;
            }

            // 内容截断
            boolean truncated = false;
            if (markdown.length() > MAX_CONTENT_CHARS) {
                markdown = markdown.substring(0, MAX_CONTENT_CHARS);
                truncated = true;
            }

            long durationMs = System.currentTimeMillis() - start;

            return ToolResult.success(markdown)
                    .withMetadata("url", url)
                    .withMetadata("statusCode", statusCode)
                    .withMetadata("bytes", bodyBytes.length)
                    .withMetadata("durationMs", durationMs)
                    .withMetadata("truncated", truncated);

        } catch (SocketTimeoutException e) {
            return ToolResult.error("Request timed out after " + TIMEOUT_SECONDS + "s: " + url);
        } catch (IOException e) {
            return ToolResult.error("Failed to fetch URL: " + e.getMessage());
        }
    }

    /** HTML → Markdown 转换器 */
    private String htmlToMarkdown(Element body) {
        if (body == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Node child : body.childNodes()) {
            convertNode(child, sb, 0);
        }
        return sb.toString().trim();
    }

    private void convertNode(Node node, StringBuilder sb, int listDepth) {
        if (node instanceof TextNode text) {
            sb.append(text.getWholeText());
        } else if (node instanceof Element el) {
            String tag = el.tagName().toLowerCase();
            switch (tag) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    int level = tag.charAt(1) - '0';
                    sb.append("\n").append("#".repeat(level)).append(" ")
                            .append(el.text()).append("\n\n");
                }
                case "p" -> {
                    sb.append("\n");
                    traverseChildren(el, sb, listDepth);
                    sb.append("\n\n");
                }
                case "a" -> sb.append("[").append(el.text()).append("](")
                        .append(el.attr("href")).append(")");
                case "img" -> sb.append("![").append(el.attr("alt")).append("](")
                        .append(el.attr("src")).append(")");
                case "pre" -> {
                    Element code = el.selectFirst("code");
                    String lang = (code != null && code.hasAttr("class"))
                            ? code.className().replace("language-", "") : "";
                    sb.append("\n```").append(lang).append("\n")
                            .append(code != null ? code.text() : el.text())
                            .append("\n```\n\n");
                }
                case "ul" -> {
                    for (Element li : el.children()) {
                        sb.append("  ".repeat(listDepth)).append("- ");
                        traverseChildren(li, sb, listDepth + 1);
                        sb.append("\n");
                    }
                    sb.append("\n");
                }
                case "ol" -> {
                    int i = 1;
                    for (Element li : el.children()) {
                        sb.append("  ".repeat(listDepth)).append(i++).append(". ");
                        traverseChildren(li, sb, listDepth + 1);
                        sb.append("\n");
                    }
                    sb.append("\n");
                }
                case "blockquote" -> {
                    sb.append("\n> ");
                    traverseChildren(el, sb, listDepth);
                    sb.append("\n\n");
                }
                case "br" -> sb.append("\n");
                case "hr" -> sb.append("\n---\n\n");
                case "strong", "b" -> {
                    sb.append("**");
                    traverseChildren(el, sb, listDepth);
                    sb.append("**");
                }
                case "em", "i" -> {
                    sb.append("*");
                    traverseChildren(el, sb, listDepth);
                    sb.append("*");
                }
                case "code" -> {
                    sb.append("`").append(el.text()).append("`");
                }
                default -> traverseChildren(el, sb, listDepth);
            }
        }
    }

    private void traverseChildren(Element el, StringBuilder sb, int depth) {
        for (Node child : el.childNodes()) {
            convertNode(child, sb, depth);
        }
    }
}
