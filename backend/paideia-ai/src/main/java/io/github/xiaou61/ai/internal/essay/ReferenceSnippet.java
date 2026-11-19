package io.github.xiaou61.ai.internal.essay;

/**
 * 一条依据片段（WORK-084）。本期不接外部检索，先留出类型，后续 TASK-005 直接填充。
 */
public record ReferenceSnippet(String url, String title, String excerpt) {
}
