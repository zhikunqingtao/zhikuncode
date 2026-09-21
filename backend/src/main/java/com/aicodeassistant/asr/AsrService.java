package com.aicodeassistant.asr;

import com.aicodeassistant.llm.LlmProvidersProperties;
import com.aicodeassistant.llm.LlmProvidersProperties.ProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ASR（自动语音识别）服务 — 通过 DashScope qwen3-asr-flash 模型实现语音转文字。
 * <p>
 * 使用 OpenAI 兼容协议调用 DashScope ASR API。ASR 模型不在 Token Plan 白名单中（实测返回 model_not_found），
 * 故仅支持标准 dashscope provider。
 * <p>
 * 热词纠正规则（配置驱动）：{@code asr.corrections}（环境变量 ASR_CORRECTIONS），格式
 * {@code 标准写法1:变体1,变体2;标准写法2:变体3} —— 条目间分号分隔（容忍全角 {@code ；}），标准写法
 * 与变体间冒号分隔（容忍全角 {@code ：}），变体间逗号分隔（容忍全角 {@code ，}）。语义为
 * 「标准写法 ← 逗号分隔的误识别变体」。这一份配置统一驱动两层热词机制：
 * <ul>
 *   <li><b>词表偏置（请求侧）</b>：各条目的标准写法（canonical）注入 system 消息的
 *       「实体词表：…」前缀（{@link #buildSystemText}）。qwen3-asr-flash 无独立的 hotwords 请求参数，
 *       唯一的词表偏置机制是 system 消息上下文 —— 阿里云文档说明 system 消息"用于为语音识别提供
 *       上下文，如背景文本和实体词表等参考信息"，模型的词表匹配机制会使上下文中出现过的词识别更准；</li>
 *   <li><b>确定性后处理（响应侧）</b>：各变体经 {@link #variantToPattern} 编译为宽容匹配正则，
 *       识别结果返回前统一替换回标准写法（{@link #applyCorrections}）。</li>
 * </ul>
 * 双层并用的原因：实测词表偏置对中文发音误识别（如"智坤code"）力不能及，仅靠请求侧偏置无法保证
 * 品牌词最终以标准写法输出，需要响应侧后处理兜底。出现新的误识别变体时，在配置串对应条目追加
 * 变体即可，无需改代码；只有标准写法没有变体的条目同样支持（纯词表偏置用法）。
 * <p>
 * 对话上下文注入：{@link #recognize(byte[], String, String)} 接受可选的 context 文本（最近对话的
 * query+回复），非空时拼接在热词词表之后作为 system 消息内容，置于请求 messages 列表【首位】，
 * 用于提高专有名词识别准确率（依据：阿里云 Qwen-ASR API 参考文档中 qwen3-asr-flash 的
 * chat/completions 请求对 system 上下文消息的支持）。
 */
@Service
public class AsrService {

    private static final Logger log = LoggerFactory.getLogger(AsrService.class);

    /** base64 编码后最大允许大小：约 10MB（DashScope 限制） */
    private static final long MAX_BASE64_SIZE = 10 * 1024 * 1024;

    /** 原始音频数据最大大小：约 7.5MB（base64 编码膨胀约 33%） */
    private static final long MAX_AUDIO_SIZE = (long) (MAX_BASE64_SIZE * 0.75);

    private static final String ASR_MODEL = "qwen3-asr-flash";

    /** 永不匹配的防御性模式：变体仅由空白/连字符构成时使用，避免空模式匹配空串导致误替换 */
    private static final Pattern NEVER_MATCH = Pattern.compile("(?!)");

    /** token 字符类别：ASCII 字母数字（[a-zA-Z0-9]） */
    private static final int CLASS_ASCII_ALNUM = 0;

    /** token 字符类别：CJK 字符（\u4e00-\u9fff） */
    private static final int CLASS_CJK = 1;

    /** token 字符类别：其他字符（不构成 ASCII/CJK 切分边界） */
    private static final int CLASS_OTHER = 2;

    /**
     * ASR 热词纠正规则配置（环境变量 ASR_CORRECTIONS 可覆盖），格式与默认值见类 javadoc。
     * 统一驱动 system 词表偏置（canonical）与识别结果后处理替换（variants）两层。
     */
    @Value("${asr.corrections:zhikuncode:zhi kun code,zkun code,智坤code,志鲲Code,智kuncode,zhi坤code}")
    private String correctionsConfig;

    /**
     * 解析后的纠正规则（懒加载缓存）。@Value 字段在构造完成后才注入，故不在构造函数中解析，
     * 首次识别时才 parseCorrections 并缓存；check-then-act 并发下可能重复解析，结果确定且只读，无害。
     */
    private volatile List<CorrectionRule> cachedRules;

    private final LlmProvidersProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /**
     * 热词纠正规则：canonical 为标准写法（用于 system 词表偏置），patterns 为其误识别变体的
     * 宽容匹配正则（由 {@link #variantToPattern} 编译，用于确定性后处理替换）。
     */
    record CorrectionRule(String canonical, List<Pattern> patterns) {}

    public AsrService(LlmProvidersProperties llmProperties, ObjectMapper objectMapper) {
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * 检查 ASR 服务是否可用（标准 dashscope provider 配置了有效的 apiKey）。
     * <p>
     * ASR 模型不在 Token Plan 白名单中，故不检查 dashscope-token-plan。
     */
    public boolean isAvailable() {
        Map<String, ProviderConfig> providers = llmProperties.providers();
        if (providers == null) {
            return false;
        }
        ProviderConfig dashscope = providers.get("dashscope");
        return dashscope != null && hasApiKey(dashscope);
    }

    /**
     * 识别音频数据，返回转录文本（识别结果会先经 {@link #applyCorrections} 热词纠正）。
     *
     * @param audioData 原始音频字节数组
     * @param mimeType  MIME 类型（如 audio/webm）
     * @param context   识别上下文（最近对话文本），可为 null/空；非空时作为 system 消息置于请求 messages 首位
     * @return 转录并纠正后的文本
     * @throws IllegalArgumentException 音频过大或参数无效
     * @throws RuntimeException         API 调用失败
     */
    public String recognize(byte[] audioData, String mimeType, String context) {
        if (audioData == null || audioData.length == 0) {
            throw new IllegalArgumentException("音频数据不能为空");
        }
        if (audioData.length > MAX_AUDIO_SIZE) {
            throw new IllegalArgumentException(
                    "音频数据过大（" + (audioData.length / 1024 / 1024) + "MB），最大允许 " + (MAX_AUDIO_SIZE / 1024 / 1024) + "MB");
        }
        if (mimeType == null || !mimeType.startsWith("audio/")) {
            throw new IllegalArgumentException("无效的 MIME 类型: " + mimeType);
        }

        String base64Audio = Base64.getEncoder().encodeToString(audioData);
        String dataUri = "data:" + mimeType + ";base64," + base64Audio;

        // 热词纠正规则（懒加载：@Value 注入后首次调用时解析并缓存）
        List<CorrectionRule> rules = getCorrectionRules();

        // 按优先级选择 provider
        ProviderConfig provider = resolveProvider();
        String baseUrl = provider.baseUrl().endsWith("/")
                ? provider.baseUrl().substring(0, provider.baseUrl().length() - 1)
                : provider.baseUrl();
        String url = baseUrl + "/chat/completions";

        try {
            // 构造 messages：词表（各规则 canonical）+ 上下文非空时首位插入 system 消息（词表匹配机制，
            // 上下文中出现过的词识别更准），其后为携带 input_audio 的 user 消息。
            // 注意：system 的 content 必须是数组格式 [{"type":"text","text":...}]；
            // 纯字符串 content 会被阿里云拒绝（400 InvalidParameter，实测验证）。
            List<Map<String, Object>> messages = new ArrayList<>();
            LinkedHashSet<String> canonicalHotwords = new LinkedHashSet<>();
            int variantCount = 0;
            for (CorrectionRule rule : rules) {
                canonicalHotwords.add(rule.canonical());
                variantCount += rule.patterns().size();
            }
            String systemText = buildSystemText(List.copyOf(canonicalHotwords), context);
            if (systemText != null) {
                messages.add(Map.of(
                        "role", "system",
                        "content", List.of(Map.of("type", "text", "text", systemText))
                ));
                log.debug("ASR 请求 system 消息：热词 {} 个，纠正规则 {} 条（变体 {} 个），systemText 长度 {} 字符",
                        canonicalHotwords.size(), rules.size(), variantCount, systemText.length());
            }
            messages.add(Map.of(
                    "role", "user",
                    "content", List.of(Map.of(
                            "type", "input_audio",
                            "input_audio", Map.of("data", dataUri)
                    ))
            ));

            Map<String, Object> requestBody = Map.of(
                    "model", ASR_MODEL,
                    "messages", messages,
                    "stream", false,
                    "asr_options", Map.of("enable_itn", true)
            );

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + provider.apiKey())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            log.debug("发送 ASR 请求到 {}", url);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("ASR API 返回错误状态 {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("ASR API 调用失败，状态码: " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new RuntimeException("ASR API 响应中缺少 choices[0].message.content");
            }

            // 后处理纠正：词表偏置对中文发音等场景纠偏有限，对配置中的已知变体做确定性替换（asr.corrections）
            return applyCorrections(content.asText(), rules);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("ASR 识别失败", e);
            throw new RuntimeException("ASR 识别失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取解析后的热词纠正规则（懒加载缓存）。
     * <p>
     * @Value 字段在构造完成后由 Spring 注入，故首次调用（识别请求时）才解析配置；并发下可能重复
     * 解析，结果确定且只读，无害。
     */
    private List<CorrectionRule> getCorrectionRules() {
        List<CorrectionRule> rules = cachedRules;
        if (rules == null) {
            rules = parseCorrections(correctionsConfig);
            cachedRules = rules;
        }
        return rules;
    }

    /**
     * 构造 ASR 请求的 system 消息文本（热词词表前缀 + 可选对话上下文）。
     * <p>
     * qwen3-asr-flash 没有独立的 hotwords 请求参数，唯一的词表（偏置）机制是 system 消息上下文：
     * 阿里云 Qwen-ASR API 文档说明 system 消息"用于为语音识别提供上下文，如背景文本和实体词表等
     * 参考信息"，模型的词表匹配机制会使上下文中出现过的词识别更准。因此将 canonical 热词组装为
     * "实体词表：…"前缀行注入 system 消息。canonical 列表来自 {@link #parseCorrections} 各规则的
     * canonical 字段，LinkedHashSet 保序去重。
     * <p>
     * 行为（纯函数，便于无 Spring 上下文单测）：
     * <ul>
     *   <li>canonical 列表非空 + context 非空白：返回 {@code 实体词表：词1、词2、…} + "\n\n" + context</li>
     *   <li>canonical 列表非空 + context 空白/null：仅返回前缀行 {@code 实体词表：词1、词2、…}</li>
     *   <li>canonical 列表为空 + context 非空白：直接返回 context 原文（向后兼容，纯上下文用法）</li>
     *   <li>canonical 列表为空 + context 空白/null：返回 null（不插入 system 消息）</li>
     * </ul>
     *
     * @param canonicalHotwords 热词标准写法列表（可为 null；内部 trim、去空、去重保序）
     * @param context           前端传来的对话上下文，可为 null/空白
     * @return system 消息文本；无需 system 消息时返回 null
     */
    static String buildSystemText(List<String> canonicalHotwords, String context) {
        LinkedHashSet<String> hotwords = new LinkedHashSet<>();
        if (canonicalHotwords != null) {
            for (String word : canonicalHotwords) {
                if (word == null) {
                    continue;
                }
                String trimmed = word.trim();
                if (!trimmed.isEmpty()) {
                    hotwords.add(trimmed);
                }
            }
        }
        boolean hasContext = context != null && !context.isBlank();
        if (hotwords.isEmpty()) {
            return hasContext ? context : null;
        }
        String prefix = "实体词表：" + String.join("、", hotwords);
        return hasContext ? prefix + "\n\n" + context : prefix;
    }

    /**
     * 解析热词纠正配置串（静态包私有纯函数，便于无 Spring 上下文单测）。
     * <p>
     * 格式 {@code 标准写法1:变体1,变体2;标准写法2:变体3}：条目按分号（半/全角）分隔，每条按第一个
     * 冒号（半/全角）分为 canonical 与变体部分，变体按逗号（半/全角）分隔，各自 trim、去空、
     * 去重保序。
     * <ul>
     *   <li>null/空白配置 → 空列表；</li>
     *   <li>缺冒号、或 canonical trim 后为空的条目跳过；</li>
     *   <li>canonical 非空但无有效变体的条目保留（纯词表偏置用法，patterns 为空列表）；</li>
     *   <li>每个有效变体经 {@link #variantToPattern} 编译为正则；仅由空白/连字符构成的变体丢弃
     *       （编译不出有意义的模式）。</li>
     * </ul>
     *
     * @param config 配置串，如 {@code zhikuncode:zhi kun code,zkuncode}，可为 null/空白
     * @return 解析后的规则列表（不可变，按配置顺序）
     */
    static List<CorrectionRule> parseCorrections(String config) {
        if (config == null || config.isBlank()) {
            return List.of();
        }
        List<CorrectionRule> rules = new ArrayList<>();
        for (String entry : config.split("[;；]")) {
            // 每条按第一个冒号（半/全角）分为 canonical 与变体部分
            int colonIndex = -1;
            for (int i = 0; i < entry.length(); i++) {
                char c = entry.charAt(i);
                if (c == ':' || c == '：') {
                    colonIndex = i;
                    break;
                }
            }
            if (colonIndex < 0) {
                continue; // 缺冒号条目跳过
            }
            String canonical = entry.substring(0, colonIndex).trim();
            if (canonical.isEmpty()) {
                continue; // canonical 为空条目跳过
            }
            String variantsPart = entry.substring(colonIndex + 1);
            LinkedHashSet<String> variants = new LinkedHashSet<>();
            for (String variant : variantsPart.split("[,，]")) {
                String trimmed = variant.trim();
                if (!trimmed.isEmpty()) {
                    variants.add(trimmed);
                }
            }
            List<Pattern> patterns = new ArrayList<>();
            for (String variant : variants) {
                if (tokenizeVariant(variant).isEmpty()) {
                    continue; // 仅空白/连字符构成的变体丢弃
                }
                patterns.add(variantToPattern(variant));
            }
            rules.add(new CorrectionRule(canonical, List.copyOf(patterns)));
        }
        return List.copyOf(rules);
    }

    /**
     * 将单个误识别变体编译为宽容匹配正则（静态包私有纯函数，便于无 Spring 上下文单测）。
     * <p>
     * 宽容规则：
     * <ul>
     *   <li>整体大小写不敏感（{@code (?i)} 前缀；仅 ASCII 大小写折叠，CJK 无大小写不受影响）；</li>
     *   <li>先按空格/连字符分词，再在每个 token 内部按「ASCII 字母数字 ↔ CJK 字符」边界进一步切分
     *       （如 {@code 智坤code} → {@code 智坤} + {@code code}，{@code zhi坤code} → {@code zhi} +
     *       {@code 坤} + {@code code}）；</li>
     *   <li>所有切出的 token 之间以 {@code [\s\-]*} 连接 —— 变体各部分之间允许空格、连字符或零分隔，
     *       因此 "zhi kun code" 可匹配 "zhi-kun-code"、"zhiKunCode"、"zhikuncode"（对已纠正文本幂等）；</li>
     *   <li>不加词边界 {@code \b}，接受子串匹配 —— ASR 转录中品牌词常与中文紧邻无空格（如
     *       "智坤code这个项目"），加 \b 会漏判；代价是更长包含串中的命中也会被整体替换，对品牌
     *       纠正场景可接受。</li>
     * </ul>
     *
     * @param variant 误识别变体（非空白），如 {@code zhi kun code}、{@code 智坤code}
     * @return 宽容匹配正则；变体仅由空白/连字符构成时返回永不匹配的模式
     */
    static Pattern variantToPattern(String variant) {
        List<String> tokens = tokenizeVariant(variant);
        if (tokens.isEmpty()) {
            return NEVER_MATCH;
        }
        StringBuilder regex = new StringBuilder("(?i)");
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                regex.append("[\\s\\-]*");
            }
            regex.append(Pattern.quote(tokens.get(i)));
        }
        return Pattern.compile(regex.toString());
    }

    /**
     * 对识别结果应用热词纠正规则（确定性后处理，静态包私有纯函数便于单测）。
     * <p>
     * 逐规则将各变体的宽容正则匹配到的文本统一替换为该规则的 canonical。规则按配置顺序依次应用
     * （后一条规则作用于前一条替换后的结果）；替换值经 {@link Matcher#quoteReplacement} 转义，
     * canonical 含 {@code $}/{@code \} 时亦按字面量处理。替换幂等 —— 已是 canonical 写法的文本再
     * 应用规则不变（如 zhikuncode 会被 "zhi kun code" 模式以零分隔匹配后替换回自身）。
     *
     * @param text  识别原始文本，可为 null
     * @param rules {@link #parseCorrections} 解析出的规则列表，可为 null/空
     * @return 纠正后的文本；null/空串或规则列表为空时原样返回
     */
    static String applyCorrections(String text, List<CorrectionRule> rules) {
        if (text == null || text.isEmpty() || rules == null || rules.isEmpty()) {
            return text;
        }
        String result = text;
        for (CorrectionRule rule : rules) {
            String replacement = Matcher.quoteReplacement(rule.canonical());
            for (Pattern pattern : rule.patterns()) {
                result = pattern.matcher(result).replaceAll(replacement);
            }
        }
        return result;
    }

    /**
     * 变体分词：先按空格/连字符切分，再在每段内部按「ASCII 字母数字 ↔ CJK」相邻边界进一步切分
     * （其他字符不构成切分边界，与相邻字符合并成一个 token 整体字面匹配）。
     */
    private static List<String> tokenizeVariant(String variant) {
        List<String> tokens = new ArrayList<>();
        for (String chunk : variant.split("[\\s\\-]+")) {
            if (chunk.isEmpty()) {
                continue;
            }
            StringBuilder token = new StringBuilder();
            int prevClass = CLASS_OTHER;
            for (int i = 0; i < chunk.length(); i++) {
                char c = chunk.charAt(i);
                int cls = charClass(c);
                if ((prevClass == CLASS_ASCII_ALNUM && cls == CLASS_CJK)
                        || (prevClass == CLASS_CJK && cls == CLASS_ASCII_ALNUM)) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }
                token.append(c);
                prevClass = cls;
            }
            if (!token.isEmpty()) {
                tokens.add(token.toString());
            }
        }
        return tokens;
    }

    /** 字符分类：0 = ASCII 字母数字，1 = CJK（\u4e00-\u9fff），2 = 其他 */
    private static int charClass(char c) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
            return CLASS_ASCII_ALNUM;
        }
        if (c >= '\u4e00' && c <= '\u9fff') {
            return CLASS_CJK;
        }
        return CLASS_OTHER;
    }

    private ProviderConfig resolveProvider() {
        Map<String, ProviderConfig> providers = llmProperties.providers();
        // ASR 模型不在 Token Plan 白名单中，仅标准 dashscope provider 可用
        ProviderConfig dashscope = providers.get("dashscope");
        if (dashscope != null && hasApiKey(dashscope)) {
            return dashscope;
        }
        throw new IllegalStateException("没有可用的 DashScope provider（未配置 apiKey）");
    }

    private boolean hasApiKey(ProviderConfig config) {
        return config.apiKey() != null && !config.apiKey().isBlank();
    }
}
