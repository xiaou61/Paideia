package io.github.xiaou61.ai;

/**
 * 一次文档蒸馏的结果：条目草稿 + 用量 + 是否截断。
 *
 * <p>与 {@link GeneratedSummary} 同一套分层：端口只负责“调一次模型并返回已校验的结果”，
 * 计费、落库、去重都在调用方（{@code internal.importing.KnowledgeImportService}）。
 */
public record DistilledDocument(DistilledEntry entry, TokenUsage usage, boolean truncated) {
}
