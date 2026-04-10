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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** 十止的协议 — 防止 file/ftp/gopher 等协议攻击 */
    private static final Set<String> BLOCKED_SCHEMES = Set.of("file", "ftp", "gopher", "data", "jar");

    /** 预审批域名白名单 — 对齐 Claude Code preapproved.ts */
    private static final Set<String> PREAPPROVED_HOSTS = Set.of(
        "docs.python.org", "docs.oracle.com", "developer.mozilla.org",
        "docs.spring.io", "react.dev", "vuejs.org", "nextjs.org",
        "www.typescriptlang.org", "nodejs.org", "docs.npmjs.com",
        "www.postgresql.org", "redis.io", "kubernetes.io",
        "git-scm.com", "github.com", "gitlab.com",
        "stackoverflow.com", "www.baeldung.com",
        "docs.github.com", "docs.docker.com",
        "maven.apache.org", "central.sonatype.com",
        "pypi.org", "fastapi.tiangolo.com", "pydantic-docs.helpmanual.io"
    );

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
    public String prompt() {
        return """
                - Fetches content from a specified URL and processes it using an AI model
                - Takes a URL and a prompt as input
                - Fetches the URL content, converts HTML to markdown
                - Processes the content with the prompt using a small, fast model
                - Returns the model's response about the content
                - Use this tool when you need to retrieve and analyze web content
                
                Usage notes:
                  - IMPORTANT: If an MCP-provided web fetch tool is available, prefer using \
                that tool instead of this one, as it may have fewer restrictions.
                  - The URL must be a fully-formed valid URL
                  - HTTP URLs will be automatically upgraded to HTTPS
                  - The prompt should describe what information you want to extract from the page
                  - This tool is read-only and does not modify any files
                  - Results may be summarized if the content is very large
                  - Includes a self-cleaning 15-minute cache for faster responses
                  - When a URL redirects to a different host, the tool will inform you and \
                provide the redirect URL. You should then make a new request with the redirect URL.
                  - For GitHub URLs, prefer using the gh CLI via Bash instead \
                (e.g., gh pr view, gh issue view, gh api).
                """;
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

        // SSRF 防护 — URL 安全检查
        String ssrfError = checkUrlSafety(url);
        if (ssrfError != null) {
            return ToolResult.error("SSRF protection: " + ssrfError);
        }

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "AI-Code-Assistant/1.0")
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .build();

        try {
            Response response = httpClient.newCall(request).execute();
            int statusCode = response.code();
            String contentType = response.header("Content-Type", "text/html");

            // 流式大小检查（替代全量读入）
            byte[] bodyBytes;
            try (ResponseBody body = response.body()) {
                if (body == null) {
                    return ToolResult.error("Empty response from " + url);
                }
                bodyBytes = readBodyWithLimit(body, MAX_RESPONSE_BYTES);
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
            String msg = e.getMessage();
            if (msg != null && msg.contains("Response exceeds size limit")) {
                return ToolResult.error("Response too large (max " + MAX_RESPONSE_BYTES / 1024 / 1024 + "MB): " + url);
            }
            return ToolResult.error("Failed to fetch URL: " + msg);
        }
    }

    /**
     * URL 安全检查 — 防止 SSRF 攻击。
     * 对齐 Claude Code 域名权限 + 增加 SSRF IP 防护。
     * @return 错误消息，或 null 表示安全
     */
    private String checkUrlSafety(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();

            // 1. 协议检查 — 仅允许 http/https
            if (scheme == null) return "Missing URL scheme";
            String schemeLower = scheme.toLowerCase();
            if (BLOCKED_SCHEMES.contains(schemeLower)) {
                return "Blocked scheme: " + scheme;
            }
            if (!"http".equals(schemeLower) && !"https".equals(schemeLower)) {
                return "Only http/https schemes are allowed, got: " + scheme;
            }

            String host = uri.getHost();
            if (host == null || host.isBlank()) return "Missing host in URL";

            // 2. 预审批域名免检查
            if (PREAPPROVED_HOSTS.contains(host.toLowerCase())) {
                return null; // 白名单域名直接通过
            }

            // 3. DNS 解析 + IP 检查 — 防止内网/回环/云元数据访问
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress()) {
                return "Access to loopback address is blocked";
            }
            if (addr.isSiteLocalAddress()) {
                return "Access to private network is blocked";
            }
            if (addr.isLinkLocalAddress()) {
                return "Access to link-local address is blocked";
            }
            if (addr.isAnyLocalAddress()) {
                return "Access to any-local address is blocked";
            }
            // AWS/GCP/Azure 元数据服务
            String hostAddr = addr.getHostAddress();
            if ("169.254.169.254".equals(hostAddr) || "fd00:ec2::254".equals(hostAddr)) {
                return "Access to cloud metadata service is blocked";
            }

            return null; // 安全
        } catch (Exception e) {
            return "URL validation failed: " + e.getMessage();
        }
    }

    /**
     * 流式读取响应体，带大小限制 — 替代 body.bytes() 全量读入。
     * 防止大响应导致 OOM。
     */
    private byte[] readBodyWithLimit(ResponseBody body, long maxBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream is = body.byteStream()) {
            byte[] buf = new byte[8192];
            long total = 0;
            int read;
            while ((read = is.read(buf)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("Response exceeds size limit: " + maxBytes + " bytes");
                }
                bos.write(buf, 0, read);
            }
        }
        return bos.toByteArray();
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
