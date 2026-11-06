package io.github.xiaou61.ai;

import java.util.List;

/**
 * 文档蒸馏产物（WORK-076）：从一份原始文档抽出的「知识条目草稿」。
 *
 * <p>{@code title} 与 {@code bodyMarkdown} 已由 {@code DistilledEntryValidator} 校验非空；
 * {@code suggestedConcepts} 只是建议，落库前由调用方决定是否采用。签名不含任何框架类型（D-09）。
 */
public record DistilledEntry(String title, String bodyMarkdown, List<String> suggestedConcepts) {

    public DistilledEntry {
        suggestedConcepts = suggestedConcepts == null ? List.of() : List.copyOf(suggestedConcepts);
    }
}
