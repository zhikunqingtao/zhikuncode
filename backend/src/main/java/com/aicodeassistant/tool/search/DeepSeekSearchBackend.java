package com.aicodeassistant.tool.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DeepSeek 官方 Search API 后端 — POST /v1/tools/search。
 * <p>
 * 仅在 {@code web-search.backend=deepseek} 时启用（不参与 auto 优先级）。
 * <p>
 * 计费 5 元 / 千次成功请求；账号级 QPS 限制 5。408 / 429 / 5xx 由应用层重试，
 * 最多发送 3 次搜索请求；基础退避 300ms、900ms 加 0-150ms 抖动，并遵守 Retry-After
 * （单次等待上限 30 秒）。每次尝试使用一次性请求体，禁止 OkHttp 在发送后隐式重放。
 * <p>
 * 内容深度：默认只返回 summary（need_content=false）；调用方显式开启 includeContent
 * 时请求网页正文并截断，避免一次性灌入过多 token。
 *
 * @see WebSearchBackend
 */
public class DeepSeekSearchBackend implements WebSearchBackend {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekSearchBackend.class);

    static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    static final String SEARCH_PATH = "/v1/tools/search";

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json");

    /** 最多尝试次数（含首次），即最多 3 次实际搜索请求 */
    private static final int MAX_ATTEMPTS = 3;
    /** 第 2 / 第 3 次尝试前的退避毫秒数 */
    private static final long[] RETRY_DELAYS_MS = {300L, 900L};
    /** 退避时附加的随机抖动上限（独占上界，0-150ms） */
    private static final int RETRY_JITTER_MS = 150;
    /** 单次 Retry-After 允许的最长等待；超过则停止重试，避免拖长工具调用 */
    private static final long MAX_RETRY_AFTER_MS = 30_000L;

    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 50;
    private static final int DEFAULT_COUNT = 10;
    /** 搜索语法中允许拼接的站点数上限（site:a OR site:b ...） */
    private static final int MAX_ALLOWED_DOMAINS = 5;
    /** 单条正文最大字符数（含截断标记） */
    private static final int MAX_CONTENT_CHARS = 3000;
    /** 单条摘要最大字符数（含截断标记）— 实测 summary 单条可达 3200 字符，必须截断 */
    private static final int MAX_SUMMARY_CHARS = 1200;
    private static final String TRUNCATION_SUFFIX = "…(truncated)";

    private final String apiKey;
    private final String endpoint;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DeepSeekSearchBackend(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, new OkHttpClient());
    }

    // Package-visible to test connection routing without public configuration changes.
    DeepSeekSearchBackend(String apiKey, String baseUrl, OkHttpClient client) {
        this.apiKey = apiKey;
        this.endpoint = buildEndpoint(baseUrl);
        this.httpClient = client.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(45))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<SearchResult> search(String query, SearchOptions options) throws IOException {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        boolean includeContent = options != null && options.includeContent();
        String composedQuery = composeQuery(query.trim(), options);
        int count = resolveCount(options);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("query", composedQuery);
        payload.put("count", count);
        payload.put("need_content", includeContent);

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new IOException("Failed to serialize DeepSeek search request: " + e.getMessage(), e);
        }

        IOException lastRetryableError = null;
        String lastRetryAfter = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            checkInterrupted();
            if (attempt > 1) {
                backoffBeforeRetry(attempt, lastRetryAfter, lastRetryableError);
            }

            Request request = new Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .post(oneShot(RequestBody.create(requestBody, JSON_MEDIA)))
                    .build();

            int status;
            String responseBody;
            String retryAfter;
            try (Response response = httpClient.newCall(request).execute()) {
                status = response.code();
                retryAfter = response.header("Retry-After");
                ResponseBody body = response.body();
                responseBody = body == null ? "" : body.string();
            }

            if (status >= 200 && status < 300) {
                return parseResults(responseBody, includeContent);
            }

            IOException failure = new IOException(
                    "DeepSeek search failed with HTTP " + status + describeError(responseBody));
            boolean retryable = status == 408 || status == 429 || (status >= 500 && status < 600);
            if (!retryable || attempt == MAX_ATTEMPTS) {
                throw failure;
            }
            log.warn("DeepSeek search attempt {}/{} failed: HTTP {}", attempt, MAX_ATTEMPTS, status);
            lastRetryableError = failure;
            lastRetryAfter = retryAfter;
        }
        throw new IOException("DeepSeek search exhausted retries", lastRetryableError);
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String name() {
        return "deepseek-search";
    }

    // ═══════════════ 请求组装 ═══════════════

    /**
     * 组装搜索 query：原始 query + allowedDomains + blockedDomains + 时间过滤。
     * 各段之间用单个空格分隔。
     */
    private String composeQuery(String query, SearchOptions options) {
        StringBuilder sb = new StringBuilder(query);
        if (options == null) {
            return sb.toString();
        }

        List<String> allowed = sanitizeDomains(options.allowedDomains());
        if (!allowed.isEmpty()) {
            List<String> siteTerms = allowed.stream()
                    .limit(MAX_ALLOWED_DOMAINS)
                    .map(domain -> "site:" + domain)
                    .toList();
            sb.append(' ').append(String.join(" OR ", siteTerms));
        }

        for (String blocked : sanitizeDomains(options.blockedDomains())) {
            sb.append(" -site:").append(blocked);
        }

        String afterDate = recencyAfterDate(options.recencyFilter());
        if (afterDate != null) {
            sb.append(" after:").append(afterDate);
        }

        return sb.toString();
    }

    /** 过滤 null / 空白域名并 trim */
    private List<String> sanitizeDomains(List<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>(domains.size());
        for (String domain : domains) {
            if (domain != null && !domain.isBlank()) {
                cleaned.add(domain.trim());
            }
        }
        return cleaned;
    }

    /** recencyFilter → after:YYYY-MM-DD；未知值返回 null（不追加） */
    private String recencyAfterDate(String recencyFilter) {
        if (recencyFilter == null || recencyFilter.isBlank()) {
            return null;
        }
        int days = switch (recencyFilter.trim()) {
            case "oneDay" -> 1;
            case "oneWeek" -> 7;
            case "oneMonth" -> 30;
            case "oneYear" -> 365;
            default -> -1;
        };
        if (days < 0) {
            return null;
        }
        return LocalDate.now().minusDays(days).toString();
    }

    private int resolveCount(SearchOptions options) {
        int count = options == null ? DEFAULT_COUNT : options.maxResults();
        return Math.min(MAX_COUNT, Math.max(MIN_COUNT, count));
    }

    // ═══════════════ 响应处理 ═══════════════

    private List<SearchResult> parseResults(String responseBody, boolean includeContent) throws IOException {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IOException("Invalid DeepSeek search response: empty body");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (IOException e) {
            throw new IOException("Failed to parse DeepSeek search response", e);
        }
        if (root == null || !root.isObject()) {
            throw new IOException("Invalid DeepSeek search response: expected an object");
        }
        JsonNode resultsNode = root.get("results");
        if (resultsNode == null || !resultsNode.isArray()) {
            throw new IOException("Invalid DeepSeek search response: expected a results array");
        }

        List<SearchResult> results = new ArrayList<>();
        for (JsonNode item : resultsNode) {
            if (!item.isObject()) {
                continue;
            }
            JsonNode urlNode = item.get("url");
            if (urlNode == null || !urlNode.isTextual() || urlNode.asText().isBlank()) {
                continue;
            }
            String url = urlNode.asText();
            String title = item.path("title").asText("");
            String summary = item.path("summary").asText("");
            // summary 是更完整的摘要；缺失时回退到 snippet。两条路径都要截断。
            String rawSnippet = summary.isBlank() ? item.path("snippet").asText("") : summary;
            String snippet = truncate(rawSnippet, MAX_SUMMARY_CHARS);
            String content = includeContent ? truncate(item.path("content").asText(""), MAX_CONTENT_CHARS) : "";
            results.add(new SearchResult(title, url, snippet, content));
        }
        if (!resultsNode.isEmpty() && results.isEmpty()) {
            throw new IOException("Invalid DeepSeek search response: no usable result in a non-empty results array");
        }

        log.debug("DeepSeek search returned {} results", results.size());
        return results;
    }

    /**
     * 通用截断：最终长度不超过 maxChars（含截断标记）。
     * 正文用 MAX_CONTENT_CHARS，摘要用 MAX_SUMMARY_CHARS。
     */
    private String truncate(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        int keep = maxChars - TRUNCATION_SUFFIX.length();
        return text.substring(0, keep) + TRUNCATION_SUFFIX;
    }

    /**
     * 提取服务端错误的可读信息：优先 error.message，其次原始响应片段。
     * 返回值可直接拼在 "HTTP xxx" 之后。
     */
    private String describeError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode error = objectMapper.readTree(responseBody).path("error");
            String message = error.path("message").asText("");
            if (!message.isBlank()) {
                String code = error.path("code").asText("");
                return code.isBlank() ? ": " + message : ": " + message + " (" + code + ")";
            }
        } catch (IOException ignored) {
            // 非 JSON 响应，退回原文
        }
        return ": " + abbreviate(responseBody);
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String singleLine = text.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 200 ? singleLine : singleLine.substring(0, 200) + "...";
    }

    // ═══════════════ 重试 ═══════════════

    /** A fresh one-shot body per application attempt prevents OkHttp from replaying it. */
    private static RequestBody oneShot(RequestBody body) {
        return new RequestBody() {
            @Override public MediaType contentType() { return body.contentType(); }
            @Override public long contentLength() throws IOException { return body.contentLength(); }
            @Override public boolean isOneShot() { return true; }
            @Override public void writeTo(BufferedSink sink) throws IOException { body.writeTo(sink); }
        };
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Interrupted before DeepSeek search attempt");
        }
    }

    /** Missing/past values use local backoff; an unrecognized non-empty header stops retrying. */
    static long retryAfterMillis(String value, Instant now) throws IOException {
        if (value == null || value.isBlank()) return 0;
        String text = value.trim();
        if (text.matches("[0-9]+")) {
            try {
                return Math.multiplyExact(Long.parseLong(text), 1000L);
            } catch (NumberFormatException | ArithmeticException overflow) {
                return Long.MAX_VALUE;
            }
        }
        // RFC 9110 requires all three HTTP-date forms, including one-digit asctime days.
        // Local formatters avoid sharing SimpleDateFormat's mutable state across requests.
        for (String pattern : List.of("EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM d HH:mm:ss yyyy")) {
            SimpleDateFormat parser = new SimpleDateFormat(pattern, Locale.US);
            parser.setTimeZone(TimeZone.getTimeZone("GMT"));
            parser.setLenient(false);
            parser.set2DigitYearStart(Date.from(now.atZone(ZoneOffset.UTC).minusYears(50).toInstant()));
            ParsePosition position = new ParsePosition(0);
            Date parsed = parser.parse(text, position);
            if (parsed != null && position.getIndex() == text.length()) {
                Duration delay = Duration.between(now, parsed.toInstant());
                if (delay.isNegative() || delay.isZero()) return 0;
                return delay.toMillis() + (delay.getNano() % 1_000_000 == 0 ? 0 : 1);
            }
        }
        throw new IOException("Unrecognized Retry-After; refusing an early retry");
    }

    private void backoffBeforeRetry(int attempt, String retryAfter, IOException cause) throws IOException {
        long localDelay = RETRY_DELAYS_MS[attempt - 2]
                + ThreadLocalRandom.current().nextLong(RETRY_JITTER_MS + 1);
        long serverDelay = retryAfterMillis(retryAfter, Instant.now());
        if (serverDelay > MAX_RETRY_AFTER_MS) {
            SocketTimeoutException exceeded = new SocketTimeoutException(
                    "DeepSeek search Retry-After exceeds the 30s retry limit");
            exceeded.initCause(cause);
            throw exceeded;
        }
        long delay = Math.max(localDelay, serverDelay);
        log.debug("Retrying DeepSeek search in {}ms (attempt {} of {}, previous: {})",
                delay, attempt, MAX_ATTEMPTS, cause == null ? "n/a" : cause.getMessage());
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("Interrupted while retrying DeepSeek search");
            interrupted.initCause(e);
            throw interrupted;
        }
    }

    private static String buildEndpoint(String baseUrl) {
        String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + SEARCH_PATH;
    }
}
