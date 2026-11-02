package io.github.xiaou61.ai.internal.resume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.ai.ResumeData;
import io.github.xiaou61.ai.ResumeProposal;
import io.github.xiaou61.ai.internal.support.ModelJson;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 提案清单的解析与**结构校验**（WORK-018 TASK-003）。
 *
 * <p>这里做三件事，全部是"模型不可靠"推出来的：
 * <ol>
 *   <li><b>`entryRef` 必须指向主简历里真实存在的位置</b>。指不到就丢——否则闸门无法回查原文，
 *       那条提案就成了"没有任何出处的一段文字"，而它最终会进定稿。</li>
 *   <li><b>`originalText` 必须能在该位置找到</b>（归一化后包含或相等）。模型把原文改一两个字
 *       再"当作原文"是常见形态；不校验的话，用户看到的"原文"就不是他的原文。</li>
 *   <li><b>缺口词一律不许出现</b>。这一条在模型侧已经写过，但只在代码里检查才算数——
 *       提示词是请求，不是保证。</li>
 * </ol>
 *
 * <p>`changeType` 不认识的值一律归 `rewrite`：多一个枚举值不值得让整次调用失败。
 */
@Component
public class ResumeProposalParser {

    /** 一次最多接受多少条提案。超过就截断——界面也放不下更多。 */
    static final int MAX_PROPOSALS = 40;
    private static final int MAX_TEXT = 2000;
    private static final int MAX_RATIONALE = 500;
    private static final int MAX_KEYWORDS = 10;

    private static final Set<String> CHANGE_TYPES = Set.of("rewrite", "reorder", "add", "remove");

    private final ObjectMapper mapper = new ObjectMapper();

    /** 解析结果：通过的提案 + 被丢弃的条数（给界面提示"模型给了 N 条，其中 M 条没通过校验"）。 */
    public record ParsedProposals(List<ResumeProposal> proposals, int dropped) {
    }

    /**
     * @param rawModelOutput 模型输出
     * @param resume         主简历，用来校验 entryRef 与 originalText
     * @param gaps           缺口关键词（归一化后的），出现在 proposedText 里就丢
     */
    public ParsedProposals parse(String rawModelOutput, ResumeData resume, List<String> gaps) {
        JsonNode root = readObject(rawModelOutput);
        JsonNode proposalsNode = root.get("proposals");
        if (proposalsNode == null || !proposalsNode.isArray()) {
            throw failure("模型没有返回 proposals 数组");
        }

        List<String> normalizedGaps = gaps.stream()
                .map(TextNormalizer::normalize)
                .filter(value -> !value.isEmpty())
                .toList();

        List<ResumeProposal> proposals = new ArrayList<>();
        int dropped = 0;
        for (JsonNode node : proposalsNode) {
            if (proposals.size() >= MAX_PROPOSALS) {
                break;
            }
            if (!node.isObject()) {
                dropped++;
                continue;
            }
            String entryRef = text(node, "entryRef");
            String originalText = text(node, "originalText");
            String proposedText = text(node, "proposedText");
            if (entryRef.isBlank() || proposedText.isBlank()) {
                dropped++;
                continue;
            }
            // ① 位置必须真实存在
            String sourceAtRef = ResumeRefs.read(resume, entryRef);
            if (sourceAtRef == null) {
                dropped++;
                continue;
            }
            // ② 原文必须能对上（模型篡改原文是最常见的一类不诚实）
            if (!originalText.isBlank() && !matches(sourceAtRef, originalText)) {
                dropped++;
                continue;
            }
            // ③ 缺口词不许出现
            String normalizedProposed = TextNormalizer.normalize(proposedText);
            if (normalizedGaps.stream().anyMatch(normalizedProposed::contains)) {
                dropped++;
                continue;
            }
            proposals.add(new ResumeProposal(
                    entryRef,
                    text(node, "field").isBlank() ? "bullet" : text(node, "field"),
                    clamp(originalText.isBlank() ? sourceAtRef : originalText, MAX_TEXT),
                    clamp(proposedText, MAX_TEXT),
                    changeType(text(node, "changeType")),
                    clamp(text(node, "rationale"), MAX_RATIONALE),
                    keywords(node)));
        }
        if (proposals.isEmpty()) {
            throw failure("模型给出的提案全部没有通过校验");
        }
        return new ParsedProposals(List.copyOf(proposals), dropped);
    }

    /** 原文对得上：任一方包含另一方即算（模型常只截取原文的一小段）。 */
    private static boolean matches(String source, String claimed) {
        String normalizedSource = TextNormalizer.normalize(source);
        String normalizedClaim = TextNormalizer.normalize(claimed);
        if (normalizedClaim.isEmpty()) {
            return false;
        }
        return normalizedSource.contains(normalizedClaim) || normalizedClaim.contains(normalizedSource);
    }

    private static String changeType(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return CHANGE_TYPES.contains(value) ? value : "rewrite";
    }

    private static List<String> keywords(JsonNode node) {
        JsonNode array = node == null ? null : node.get("coversKeywords");
        if (array == null || !array.isArray()) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode element : array) {
            if (values.size() >= MAX_KEYWORDS) {
                break;
            }
            if (element.isValueNode()) {
                String value = element.asText().trim();
                if (!value.isEmpty()) {
                    values.add(value.length() > 60 ? value.substring(0, 60) : value);
                }
            }
        }
        return List.copyOf(values);
    }

    private static String clamp(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private JsonNode readObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw failure("模型没有返回任何内容");
        }
        try {
            JsonNode node = mapper.readTree(stripFence(raw));
            if (node == null || !node.isObject()) {
                throw failure("模型返回的不是一个 JSON 对象");
            }
            return node;
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure("模型返回的内容不是合法 JSON");
        }
    }

        private static String stripFence(String raw) {
        return ModelJson.stripFence(raw);
    }

        private static String text(JsonNode node, String field) {
        return ModelJson.textOrEmpty(node, field);
    }

    private static BizException failure(String reason) {
        return new BizException(ErrorCode.INTERNAL, "没有拿到可用的改写提案（" + reason + "）");
    }
}
