package com.aicodeassistant.memdir;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 记忆语义搜索引擎 — BM25 + 中文 N-gram 分词。
 * <p>
 * 纯 Java 实现，无外部依赖（不引入 Lucene/Jieba）。
 * 通过 Unigram + Bigram 实现中文分词的折中方案。
 * <p>
 * BM25 公式:
 *   score(q,d) = Σ IDF(qi) * (tf(qi,d) * (k1+1)) / (tf(qi,d) + k1*(1-b+b*(|d|/avgDL)))
 *
 * @see MemdirService#searchMemories(String, int)
 */
@Component
public class MemorySearchEngine {

    // ==================== BM25 参数 ====================
    private static final double K1 = 1.2;     // 词频饱和参数
    private static final double B = 0.75;      // 文档长度归一化权重
    private static final double TITLE_BOOST = 2.0;  // 标题匹配加权

    // ==================== 停用词表 ====================
    private static final Set<String> STOP_WORDS = Set.of(
            // 中文停用词
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都",
            "一", "一个", "上", "也", "很", "到", "说", "要", "去", "你",
            "会", "着", "没有", "看", "好", "自己", "这", "那", "被", "从",
            // 英文停用词
            "the", "is", "at", "which", "on", "a", "an", "and", "or", "but",
            "in", "to", "for", "of", "with", "it", "this", "that", "are", "was"
    );

    /** CJK Unicode 范围检测 */
    private static final Pattern CJK_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]");
    /** 英文/数字 token */
    private static final Pattern WORD_PATTERN = Pattern.compile("[a-zA-Z0-9_]+");

    /**
     * BM25 搜索。
     *
     * @param entries 全部记忆条目
     * @param query   查询文本
     * @param topK    返回前 K 条
     * @return 按 BM25 得分降序的条目索引 + 得分
     */
    public List<ScoredResult> search(List<DocumentEntry> entries, String query, int topK) {
        if (entries.isEmpty() || query == null || query.isBlank()) return List.of();

        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) return List.of();

        // 预计算：所有文档的 token 列表 + 平均文档长度
        List<List<String>> allDocTokens = new ArrayList<>();
        List<List<String>> allTitleTokens = new ArrayList<>();
        double totalLength = 0;

        for (DocumentEntry entry : entries) {
            List<String> bodyTokens = tokenize(entry.body());
            List<String> titleTokens = tokenize(entry.title());
            allDocTokens.add(bodyTokens);
            allTitleTokens.add(titleTokens);
            totalLength += bodyTokens.size();
        }
        double avgDL = totalLength / entries.size();

        // IDF 预计算
        Map<String, Double> idf = computeIDF(queryTokens, allDocTokens, entries.size());

        // BM25 评分
        List<ScoredResult> results = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            double bodyScore = computeBM25(
                    queryTokens, allDocTokens.get(i), idf, avgDL);
            double titleScore = computeBM25(
                    queryTokens, allTitleTokens.get(i), idf,
                    avgDL / 5.0);  // 标题平均长度远短于正文

            double totalScore = bodyScore + titleScore * TITLE_BOOST;
            if (totalScore > 0) {
                results.add(new ScoredResult(i, totalScore));
            }
        }

        return results.stream()
                .sorted(Comparator.comparingDouble(ScoredResult::score).reversed())
                .limit(topK)
                .toList();
    }

    /**
     * 分词器 — Unigram + Bigram 中文分词 + 英文标准分词。
     * <p>
     * 中文处理策略（无外部依赖）：
     * - Unigram: 每个汉字作为独立 token（"配置" → ["配", "置"]）
     * - Bigram: 相邻双字作为额外 token（"配置" → ["配置"]）
     * - 合并后去重，bigram 天然获得更高匹配权重
     */
    List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();

        List<String> tokens = new ArrayList<>();
        String lower = text.toLowerCase();

        // 提取英文/数字 token
        var wordMatcher = WORD_PATTERN.matcher(lower);
        while (wordMatcher.find()) {
            String word = wordMatcher.group();
            if (word.length() > 1 && !STOP_WORDS.contains(word)) {
                tokens.add(word);
            }
        }

        // 提取 CJK 字符 → Unigram + Bigram
        StringBuilder cjkBuffer = new StringBuilder();
        for (char c : lower.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                cjkBuffer.append(c);
            } else {
                if (!cjkBuffer.isEmpty()) {
                    extractCJKTokens(cjkBuffer.toString(), tokens);
                    cjkBuffer.setLength(0);
                }
            }
        }
        if (!cjkBuffer.isEmpty()) {
            extractCJKTokens(cjkBuffer.toString(), tokens);
        }

        return tokens;
    }

    /**
     * CJK 分词 — Unigram + Bigram。
     */
    private void extractCJKTokens(String cjkText, List<String> tokens) {
        // Unigram
        for (int i = 0; i < cjkText.length(); i++) {
            String ch = String.valueOf(cjkText.charAt(i));
            if (!STOP_WORDS.contains(ch)) {
                tokens.add(ch);
            }
        }
        // Bigram
        for (int i = 0; i < cjkText.length() - 1; i++) {
            tokens.add(cjkText.substring(i, i + 2));
        }
    }

    /**
     * IDF 计算 — log((N - df + 0.5) / (df + 0.5) + 1)
     */
    private Map<String, Double> computeIDF(List<String> queryTokens,
                                            List<List<String>> allDocTokens, int totalDocs) {
        Map<String, Double> idf = new HashMap<>();
        for (String token : queryTokens) {
            long df = allDocTokens.stream()
                    .filter(docTokens -> docTokens.contains(token))
                    .count();
            double val = Math.log((totalDocs - df + 0.5) / (df + 0.5) + 1.0);
            idf.put(token, val);
        }
        return idf;
    }

    /**
     * BM25 单文档评分。
     */
    private double computeBM25(List<String> queryTokens, List<String> docTokens,
                                Map<String, Double> idf, double avgDL) {
        double score = 0;
        int docLen = docTokens.size();
        Map<String, Long> termFreq = docTokens.stream()
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()));

        for (String qToken : queryTokens) {
            double tf = termFreq.getOrDefault(qToken, 0L);
            if (tf == 0) continue;

            double idfVal = idf.getOrDefault(qToken, 0.0);
            double numerator = tf * (K1 + 1);
            double denominator = tf + K1 * (1 - B + B * (docLen / Math.max(1, avgDL)));
            score += idfVal * (numerator / denominator);
        }
        return score;
    }

    // ==================== 数据类型 ====================

    public record DocumentEntry(String title, String body) {}
    public record ScoredResult(int index, double score) {}
}
