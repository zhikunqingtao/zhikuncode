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

    /** 预审批域名白名单*/
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
    public long getMaxExecutionTimeMs() {
        return 300_000L; // 5 minutes for network fetch + LLM summarization
    }

    @Override
    public String getDescription() {
        return "Fetch the content of a URL and convert HTML to Markdown for easy reading.";
    }

    @Override
    public String prompt() {
        return """
                ## 核心能力（一句话）
                获取技术文档、文章等纯文本网页的内容，将 HTML 转为 Markdown 格式。
                
                ## ⚠️ 严格限制（前置，必读）
                **此工具仅限于以下场景使用：**
                  ✓ 技术文档（Python/Java/React 官方文档）
                  ✓ 技术博客和教程文章
                  ✓ API 参考文档
                  ✓ 开发者社区文章（Stack Overflow）
                
                **此工具不能处理（绝对禁止使用）：**
                  ✗ 任何文件下载（视频、PDF、图片、压缩包）
                  ✗ 需要 JavaScript 渲染的动态网站（如小红书、抖音、微博、Instagram、TikTok 等社交/视频平台）
                  ✗ 视频/音频平台内容（如 YouTube、Bilibili、优酷等）— 需要用 yt-dlp
                  ✗ 需要用户登录的页面
                  ✗ JavaScript 动态渲染的单页应用（SPA）
                  ✗ 电商网站爬取
                  ✗ API 调用（应使用 curl/requests）
                
                ## 工具选择决策树（按优先级）
                IF 用户要求涉及 "下载/抓取/爬" + 目标是(视频/文件/图片/PDF/二进制内容 OR 社交媒体/视频平台)
                  → 必须使用 Bash (curl/wget/yt-dlp) 或 Python (requests/Playwright)
                  → 禁止使用 WebFetch
                
                ELSE IF 用户要求涉及 "下载/抓取" + 目标是(文本内容/文档/文章/博客)
                  → 可以使用 WebFetch（这属于读取文本内容，WebFetch 的正常功能）
                
                ELSE IF 用户要求涉及需要 JavaScript 渲染的动态网站（如小红书、抖音、微博、YouTube、Bilibili、Instagram、TikTok 等社交/视频平台）
                  → 必须使用 Python Playwright 或 Bash yt-dlp
                  → 禁止使用 WebFetch（这些需要 JavaScript 渲染）
                
                ELSE IF 用户要求涉及"阅读/查看/理解"且来源是"技术文档/博客/文章/API参考"
                  → 使用 WebFetch，这是最佳使用场景
                
                ELSE
                  → 不确定时，可以询问用户具体需求是什么（下载/阅读/实时数据），再选择合适工具
                
                ## 技术原因（为什么这些场景不适合）
                  1. 不能执行 JavaScript → 无法渲染动态网站（小红书、抖音等）
                  2. 返回 Markdown 文本 → 无法获取二进制文件（视频、PDF、图片）
                  3. 单次请求 → 无法爬取多个页面或分页内容
                  4. 无认证能力 → 受保护的页面（需登录）无法访问
                
                ## 使用参数
                  - URL 必须是完整格式的有效 URL
                  - HTTP 自动升级为 HTTPS
                  - 包含 15 分钟自清理缓存
                  - 超时：30秒，最大内容：100K 字符
                  - IMPORTANT: 如果 MCP 提供了 web fetch 工具，优先使用 MCP 工具
                  - 对于 GitHub URL，优先使用 gh CLI（如 gh pr view, gh issue view）
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
        return PermissionRequirement.NONE;
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
