package io.github.xiaou61.ai.internal.essay;

import java.util.List;

/**
 * 自检+分流的最终结论（WORK-084）。
 *
 * <p>status ∈ auto_passed|revised|pending_review。finalAnswer 为 null 表示未修订（直接用原答案或进复核）。
 */
public record VerificationOutcome(String status, String finalAnswer, double confidence,
                                  List<String> issues, List<ReferenceSnippet> snippets,
                                  String promptVersion, String model) {
}
