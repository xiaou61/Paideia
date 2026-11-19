package io.github.xiaou61.ai.internal.essay;

import java.util.List;

/** 事实自检结论（WORK-084）。verdict ∈ ok|issues|unsure。 */
public record FactCheck(String verdict, double confidence, List<String> issues) {
}
