package com.aicodeassistant.engine;

import com.aicodeassistant.artifact.publication.ClipboardImagePublicationService;
import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.llm.CancellationSignal;
import com.aicodeassistant.llm.InlineImageBudget;
import com.aicodeassistant.llm.LlmApiException;
import com.aicodeassistant.llm.ModelCapabilities;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Prepares user images on a request copy. Persisted URL attachments are never rewritten. */
@Component
public class UserImageTranscoder {
    static final long MAX_DOWNLOAD_BYTES = ClipboardImagePublicationService.MAX_CLIPBOARD_IMAGE_BYTES;
    static final int MAX_INLINE_BYTES = InlineImageBudget.MAX_BYTES;
    static final int MAX_IMAGES_PER_CALL = 8;
    private final OssPublishProperties ossProperties;
    private final OkHttpClient httpClient;
    private final Cache<String, ContentBlock.ImageBlock> cache;

    public UserImageTranscoder(OssPublishProperties ossProperties) {
        this.ossProperties = ossProperties;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).build();
        this.cache = Caffeine.newBuilder().maximumWeight(32L * 1024 * 1024)
                .weigher((String key, ContentBlock.ImageBlock image) -> image.base64Data().length())
                .expireAfterWrite(30, TimeUnit.MINUTES).build();
    }

    public record TranscodeResult(List<Message> messages, int convertedCount, List<String> warnings) {}
    record DownloadedImage(byte[] bytes, String mediaType) {}
    private record Candidate(int messageIndex, int blockIndex, Message.UserMessage message,
                             ContentBlock.ImageBlock image) {}

    /** Only for callers without an explicit run boundary (and unit tests). */
    public TranscodeResult transcode(List<Message> messages, ModelCapabilities caps) {
        return transcode(messages, caps, currentRequestId(messages), CancellationSignal.none(), caps.contextWindow() - caps.maxOutputTokens());
    }

    /** Capture once at run entry: subsequent tool turns and compaction must not move this boundary. */
    static String currentRequestId(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.UserMessage user
                    && user.sourceToolAssistantUUID() == null && user.content() != null
                    && user.content().stream().noneMatch(ContentBlock.ToolResultBlock.class::isInstance))
                return user.uuid();
        }
        return null;
    }

    /** Estimate non-image content without changing the persisted messages or their boundaries. */
    static List<Message> withoutImagesForBudget(List<Message> messages) {
        return messages.stream().map(message -> {
            if (message instanceof Message.UserMessage user && user.content() != null) {
                return (Message) new Message.UserMessage(user.uuid(), user.timestamp(),
                        user.content().stream().filter(block -> !(block instanceof ContentBlock.ImageBlock)).toList(),
                        MessageContentAccessor.rawLegacyToolResult(user), user.sourceToolAssistantUUID(), user.meta());
            }
            return message;
        }).toList();
    }

    /** Tokens reserved by the mandatory attachment before admitting tool images. */
    static int imageTokens(List<Message> messages, String messageId) {
        return messages.stream().filter(message -> Objects.equals(message.uuid(), messageId))
                .filter(Message.UserMessage.class::isInstance).map(Message.UserMessage.class::cast)
                .filter(user -> user.content() != null).flatMap(user -> user.content().stream())
                .filter(ContentBlock.ImageBlock.class::isInstance).map(ContentBlock.ImageBlock.class::cast)
                .mapToInt(image -> InlineImageBudget.estimate(image.base64Data())).sum();
    }

    static Set<String> imageHashes(List<Message> messages, String messageId) {
        Set<String> result = new HashSet<>();
        for (Message message : messages) {
            if (message instanceof Message.UserMessage user && Objects.equals(user.uuid(), messageId) && user.content() != null) {
                for (ContentBlock block : user.content()) {
                    if (block instanceof ContentBlock.ImageBlock image && image.base64Data() != null) {
                        try {
                            result.add(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(Base64.getDecoder().decode(image.base64Data()))));
                        } catch (IllegalArgumentException | java.security.NoSuchAlgorithmException invalid) {
                            // Malformed attachments remain subject to the provider/input validation.
                        }
                    }
                }
            }
        }
        return result;
    }

    public TranscodeResult transcode(List<Message> messages, ModelCapabilities caps,
                                     String currentRequestId, CancellationSignal cancellation, int imageTokenBudget) {
        if (caps.imageInputMode() != ModelCapabilities.ImageInputMode.BASE64_ONLY)
            return new TranscodeResult(messages, 0, List.of());
        checkCancelled(cancellation);
        List<Candidate> candidates = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.UserMessage user && user.content() != null) {
                for (int j = 0; j < user.content().size(); j++) {
                    if (user.content().get(j) instanceof ContentBlock.ImageBlock image)
                        candidates.add(new Candidate(i, j, user, image));
                }
            }
        }
        // Stable sort: mandatory current attachments first, then newest history.
        candidates.sort(Comparator.comparing(c -> !Objects.equals(c.message().uuid(), currentRequestId)));
        int maxImages = Math.max(0, Math.min(MAX_IMAGES_PER_CALL, caps.maxImages()));
        long required = candidates.stream().filter(c -> Objects.equals(c.message().uuid(), currentRequestId)).count();
        if (required > maxImages) throw invalid("当前消息图片数量超过模型限制，请减少附件后重试。");
        List<Message> result = new ArrayList<>(messages);
        Map<Integer, List<ContentBlock>> replacements = new HashMap<>();
        List<String> warnings = new ArrayList<>();
        int retained = 0;
        int converted = 0;
        long imageTokens = 0;
        for (Candidate candidate : candidates) {
            checkCancelled(cancellation);
            ContentBlock replacement;
            try {
                if (retained >= maxImages) throw invalid("超过本次请求的图片数量限制");
                ContentBlock.ImageBlock prepared = prepare(candidate.image(), cancellation);
                int tokens = InlineImageBudget.estimate(prepared.base64Data());
                if (imageTokens + tokens > imageTokenBudget)
                    throw new LlmApiException("超过本次请求的图片上下文预算", false, 0, "IMAGE_CONTEXT_BUDGET_EXCEEDED", 0);
                imageTokens += tokens;
                replacement = prepared;
                retained++;
                if (candidate.image().url() != null && prepared.url() == null) converted++;
            } catch (IOException | LlmApiException failure) {
                checkCancelled(cancellation);
                String label = "消息 " + candidate.message().uuid() + " 的第 " + (imageOrdinal(candidate)) + " 张图片";
                if (Objects.equals(candidate.message().uuid(), currentRequestId)) {
                    String errorType = failure instanceof LlmApiException api
                            && "IMAGE_CONTEXT_BUDGET_EXCEEDED".equals(api.getErrorType())
                            ? "IMAGE_CONTEXT_BUDGET_EXCEEDED" : "IMAGE_INPUT_INVALID";
                    throw new LlmApiException(label + "未能处理，本轮已停止，原附件保留。请重试或重新上传。原因："
                            + safeReason(failure), false, 0, errorType, 0);
                }
                String warning = label + "本轮已省略，继续处理当前请求。原因：" + safeReason(failure);
                warnings.add(warning);
                replacement = new ContentBlock.TextBlock("[历史图片已省略：" + safeReason(failure) + "]");
            }
            replacements.computeIfAbsent(candidate.messageIndex(), ignored -> new ArrayList<>(candidate.message().content()))
                    .set(candidate.blockIndex(), replacement);
        }
        replacements.forEach((index, content) -> {
            Message.UserMessage original = (Message.UserMessage) messages.get(index);
            result.set(index, new Message.UserMessage(original.uuid(), original.timestamp(), content,
                    MessageContentAccessor.rawLegacyToolResult(original), original.sourceToolAssistantUUID(), original.meta()));
        });
        return new TranscodeResult(result, converted, List.copyOf(warnings));
    }

    private static int imageOrdinal(Candidate candidate) {
        return (int) candidate.message().content().subList(0, candidate.blockIndex() + 1).stream()
                .filter(ContentBlock.ImageBlock.class::isInstance).count();
    }

    private ContentBlock.ImageBlock prepare(ContentBlock.ImageBlock image, CancellationSignal cancellation) throws IOException {
        if (image.url() == null || image.url().isBlank()) {
            if (image.base64Data() == null || image.base64Data().length() > ((MAX_INLINE_BYTES + 2) / 3) * 4)
                throw invalid("图片超过 10 MiB 限制或数据为空");
            try {
                byte[] bytes = Base64.getDecoder().decode(image.base64Data());
                if (bytes.length > MAX_INLINE_BYTES) throw invalid("图片超过 10 MiB 限制");
                int[] size = InlineImageBudget.dimensions(bytes);
                if (size == null) throw invalid("图片头部无法读取，请重新上传");
                return new ContentBlock.ImageBlock(image.mediaType(), image.base64Data(), size[0], size[1], null);
            } catch (IllegalArgumentException malformed) {
                throw invalid("图片编码无效，请重新上传");
            }
        }
        if (!ossProperties.isTrustedClipboardImageUrl(image.url())) throw invalid("图片地址不在受信任的附件存储范围内");
        ContentBlock.ImageBlock prepared = cache.getIfPresent(image.url());
        if (prepared != null) return prepared;
        DownloadedImage downloaded = fetchImage(image.url(), cancellation);
        checkCancelled(cancellation);
        if (downloaded.bytes().length == 0 || downloaded.bytes().length > MAX_DOWNLOAD_BYTES)
            throw invalid("图片超过 5 MiB 限制或数据为空");
        int[] size = InlineImageBudget.dimensions(downloaded.bytes());
        if (size == null) throw invalid("图片头部无法读取，请重新上传");
        String mediaType = downloaded.mediaType() == null ? image.mediaType() : downloaded.mediaType();
        prepared = new ContentBlock.ImageBlock(mediaType, Base64.getEncoder().encodeToString(downloaded.bytes()),
                size[0], size[1], null);
        cache.put(image.url(), prepared);
        return prepared;
    }

    private static String safeReason(Exception failure) {
        return failure instanceof LlmApiException ? failure.getMessage() : "附件存储下载失败";
    }

    private static LlmApiException invalid(String reason) {
        return new LlmApiException(reason, false, 0, "IMAGE_INPUT_INVALID", 0);
    }

    private static void checkCancelled(CancellationSignal cancellation) {
        if (cancellation.isCancelled()) throw new LlmApiException("LLM_CALL_CANCELLED", false, 0, "cancelled", 0);
    }

    protected DownloadedImage fetchImage(String url, CancellationSignal cancellation) throws IOException {
        checkCancelled(cancellation);
        var call = httpClient.newCall(new Request.Builder().url(url).get().build());
        try (var registration = cancellation.register(call::cancel);
             Response response = call.execute()) {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            var body = response.body();
            if (body == null || body.contentLength() > MAX_DOWNLOAD_BYTES) throw new IOException("Invalid image response size");
            var contentType = body.contentType();
            if (contentType != null && !"image".equalsIgnoreCase(contentType.type())) throw new IOException("Non-image response");
            try (var input = body.byteStream(); var bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    checkCancelled(cancellation);
                    if ((long) bytes.size() + length > MAX_DOWNLOAD_BYTES) throw new IOException("Image exceeds download limit");
                    bytes.write(buffer, 0, length);
                }
                return new DownloadedImage(bytes.toByteArray(), contentType == null ? null : contentType.type() + "/" + contentType.subtype());
            }
        }
    }
}
