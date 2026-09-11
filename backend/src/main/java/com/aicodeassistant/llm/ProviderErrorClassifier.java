package com.aicodeassistant.llm;

import java.net.ConnectException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider HTTP 错误分类器 — 将 LLM Provider 返回的 HTTP 错误映射为结构化错误码，
 * 供 WebSocket error 事件与子代理失败链路统一透出。
 * <p>
 * 分类规则（与前端契约一致，HTTP 状态码优先于连接异常）:
 * <ul>
 *   <li>402 → PROVIDER_PAYMENT_REQUIRED（余额不足）</li>
 *   <li>403 → PROVIDER_FORBIDDEN（权限/配额拒绝）</li>
 *   <li>429 → PROVIDER_RATE_LIMITED（限流）</li>
 *   <li>其他 4xx/5xx → PROVIDER_ERROR</li>
 *   <li>无 HTTP 状态且 cause 链含 ConnectException → PROVIDER_UNREACHABLE（连接拒绝/不可达）</li>
 * </ul>
 */
public final class ProviderErrorClassifier {

    public static final String PROVIDER_PAYMENT_REQUIRED = "PROVIDER_PAYMENT_REQUIRED";
    public static final String PROVIDER_FORBIDDEN = "PROVIDER_FORBIDDEN";
    public static final String PROVIDER_RATE_LIMITED = "PROVIDER_RATE_LIMITED";
    public static final String PROVIDER_ERROR = "PROVIDER_ERROR";
    public static final String PROVIDER_UNREACHABLE = "PROVIDER_UNREACHABLE";

    /** cause 链遍历上限，防御自引用异常链 */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** 从异常消息中提取目标地址（host:port），如 OkHttp 的 "Failed to connect to /<host>:<port>" */
    private static final Pattern ADDRESS_PATTERN = Pattern.compile("([\\w.\\-]+:\\d{1,5})");

    /**
     * 分类结果 — errorCode 为结构化错误码，message 为人类可读中文描述，
     * httpStatus 为原始 HTTP 状态码，retryable 表示重试是否可能成功。
     */
    public record ClassifiedError(String errorCode, int httpStatus,
                                  String message, boolean retryable) {}

    private ProviderErrorClassifier() {}

    /**
     * 沿 cause 链查找 {@link LlmApiException} 并按 HTTP 状态码分类；
     * 无 HTTP 状态时再检查 cause 链中的 {@link ConnectException}（连接拒绝/不可达）。
     *
     * @return 分类结果；非 Provider 错误（无 HTTP 状态且无连接异常）返回 null
     */
    public static ClassifiedError classify(Throwable error) {
        LlmApiException llm = findLlmApiException(error);
        if (llm == null || llm.getHttpStatus() < 400) {
            return classifyConnectFailure(error);
        }
        int status = llm.getHttpStatus();
        return switch (status) {
            case 402 -> new ClassifiedError(PROVIDER_PAYMENT_REQUIRED, status,
                    "模型账户余额不足，请充值或切换模型", false);
            case 403 -> new ClassifiedError(PROVIDER_FORBIDDEN, status,
                    "模型服务拒绝访问，请检查 API Key 权限或配额", false);
            case 429 -> new ClassifiedError(PROVIDER_RATE_LIMITED, status,
                    "模型请求频率超限，请稍后重试或切换模型", true);
            default -> new ClassifiedError(PROVIDER_ERROR, status,
                    "模型服务返回错误（HTTP " + status + "）："
                            + (llm.getMessage() == null || llm.getMessage().isBlank()
                                    ? "Unknown error" : llm.getMessage()),
                    status >= 500);
        };
    }

    /** 无 HTTP 状态时的连接异常分类 — cause 链含 ConnectException → PROVIDER_UNREACHABLE。 */
    private static ClassifiedError classifyConnectFailure(Throwable error) {
        ConnectException connect = findConnectException(error);
        if (connect == null) {
            return null;
        }
        String address = extractAddress(connect.getMessage());
        String message = address == null
                ? "无法连接 LLM 服务，请检查本地代理或网络设置"
                : "无法连接 LLM 服务（" + address + " 连接失败），请检查本地代理或网络设置";
        return new ClassifiedError(PROVIDER_UNREACHABLE, 0, message, true);
    }

    /**
     * 沿 cause 链查找 {@link ConnectException}（连接拒绝/不可达）。
     * 公开给重试层（ApiRetryService）复用，避免重复实现解包逻辑。
     */
    public static ConnectException findConnectException(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < MAX_CAUSE_DEPTH) {
            if (current instanceof ConnectException connect) {
                return connect;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String extractAddress(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = ADDRESS_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static LlmApiException findLlmApiException(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < MAX_CAUSE_DEPTH) {
            if (current instanceof LlmApiException llm) {
                return llm;
            }
            current = current.getCause();
        }
        return null;
    }
}
