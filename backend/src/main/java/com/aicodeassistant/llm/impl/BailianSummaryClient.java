package com.aicodeassistant.llm.impl;

import com.aicodeassistant.llm.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/** Non-streaming, summary-only transport. No global retries, key rotation, or chatSync fallback. */
final class BailianSummaryClient {
    static final String ENDPOINT = "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";
    private BailianSummaryClient() {}

    static SummaryResult execute(OkHttpClient client, ObjectMapper mapper, String key,
                                 ConcurrentMap<String, Call> active, SummaryRequest input,
                                 LlmCallContext context) {
        var body = mapper.createObjectNode();
        body.put("model", input.model()).put("stream", false)
                .put("max_completion_tokens", input.maxCompletionTokens());
        body.set("messages", mapper.valueToTree(List.of(
                Map.of("role", "system", "content", input.systemPrompt()),
                Map.of("role", "user", "content", input.userContent()))));
        boolean thinking = input.thinkingMode() != SummaryRequest.ThinkingMode.OFF;
        if (input.model().startsWith("deepseek-")) {
            body.putObject("thinking").put("type", thinking ? "enabled" : "disabled");
        } else body.put("enable_thinking", thinking);
        if (thinking) body.put("reasoning_effort", input.thinkingMode().name().toLowerCase(java.util.Locale.ROOT));
        Request request = new Request.Builder().url(ENDPOINT + "/chat/completions")
                .header("Authorization", "Bearer " + key)
                .post(RequestBody.create(body.toString(), MediaType.get("application/json"))).build();
        for (int attempt = 0; attempt < 2; attempt++) {
            checkCancelled(context);
            long remaining = input.deadlineNanos() - System.nanoTime();
            if (remaining <= 0) return SummaryResult.failed("summary_timeout");
            Call call = client.newBuilder().retryOnConnectionFailure(false)
                    .callTimeout(Duration.ofNanos(remaining)).readTimeout(Duration.ofNanos(remaining))
                    .build().newCall(request);
            long waitMillis = -1;
            try (var lease = LlmCallRegistration.register(active, context.requestId(), call,
                    context.cancellation(), Call::cancel); Response response = call.execute()) {
                checkCancelled(context);
                if (response.code() == 429 && attempt == 0) {
                    waitMillis = retryAfterMillis(response.header("Retry-After"));
                } else if (!response.isSuccessful()) {
                    return SummaryResult.failed("summary_http_" + response.code());
                } else {
                    if (response.body() == null) return SummaryResult.failed("summary_empty_response");
                    JsonNode root = mapper.readTree(response.body().string());
                    checkCancelled(context);
                    if (System.nanoTime() >= input.deadlineNanos()) return SummaryResult.failed("summary_timeout");
                    if (root == null) return SummaryResult.failed("summary_invalid_response");
                    JsonNode choice = root.path("choices").path(0);
                    return new SummaryResult(text(choice.path("message").get("content")),
                            text(choice.get("finish_reason")), root.get("usage"),
                            text(root.get("model")), text(root.get("id")), null);
                }
            } catch (CancellationException e) { throw e;
            } catch (Exception e) {
                checkCancelled(context);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt(); throw new CancellationException("summary_interrupted");
                }
                return SummaryResult.failed(System.nanoTime() >= input.deadlineNanos()
                        ? "summary_timeout" : "summary_transport_or_parse_error");
            }
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(input.deadlineNanos() - System.nanoTime());
            if (waitMillis >= remainingMillis) return SummaryResult.failed("summary_retry_after_deadline");
            CountDownLatch cancelled = new CountDownLatch(1);
            try (var registration = context.cancellation().register(cancelled::countDown)) {
                checkCancelled(context);
                cancelled.await(waitMillis, TimeUnit.MILLISECONDS);
                checkCancelled(context);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new CancellationException("summary_interrupted");
            }
        }
        return SummaryResult.failed("summary_retry_exhausted");
    }

    static void checkCancelled(LlmCallContext context) {
        if (context.cancellation().isCancelled() || Thread.currentThread().isInterrupted())
            throw new CancellationException("summary_cancelled");
    }
    private static String text(JsonNode node) { return node != null && node.isTextual() ? node.textValue() : null; }
    static long retryAfterMillis(String value) {
        if (value == null) return 1000;
        try { long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? 1000 : Math.multiplyExact(seconds, 1000); }
        catch (ArithmeticException e) { return Long.MAX_VALUE;
        } catch (NumberFormatException ignored) {
            try { return Math.max(0, Duration.between(Instant.now(),
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis()); }
            catch (RuntimeException e) { return 1000; }
        }
    }
}
