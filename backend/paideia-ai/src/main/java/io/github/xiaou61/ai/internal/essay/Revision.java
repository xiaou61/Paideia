package io.github.xiaou61.ai.internal.essay;

import java.util.List;

/** 修订结论（WORK-084）。action ∈ accept|revise|reject。usedRefs 记录依据出处（本期为空）。 */
public record Revision(String action, String answer, double confidence, List<String> usedRefs, String notes) {
}
