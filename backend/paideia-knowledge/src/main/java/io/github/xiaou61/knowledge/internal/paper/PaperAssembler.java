package io.github.xiaou61.knowledge.internal.paper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则组卷（纯函数，WORK-083）。
 *
 * <p>输入候选与规则，输出选中的题目身份。**无 DB、无时钟**：同输入必得同输出，
 * 于是「同规则两次组卷逐题相同」可被测试钉住。难度按分布配额挑选，不足如实计
 * {@code shortfall}，不静默少给。排序稳定键为 {@code entryId → blockKey → 题号}。
 */
public final class PaperAssembler {

    public record Candidate(long entryId, String blockKey, String questionKey, String fingerprint,
                            int optionCount, String difficultyLabel, List<Long> conceptIds) {
    }

    public record PaperRule(String title, List<Long> conceptIds, Long categoryId,
                            Map<String, Integer> difficultyDistribution, int count,
                            List<String> excludeFingerprints) {
    }

    public record AssemblyResult(List<Candidate> items, int shortfall) {
    }

    private static final List<String> LABEL_ORDER = List.of("easy", "medium", "hard");

    private static final Comparator<Candidate> STABLE_ORDER = Comparator
            .comparingLong(Candidate::entryId)
            .thenComparing(Candidate::blockKey)
            .thenComparingInt(candidate -> questionIndex(candidate.questionKey()))
            .thenComparing(Candidate::questionKey);

    private PaperAssembler() {
    }

    public static AssemblyResult assemble(List<Candidate> candidates, PaperRule rule) {
        int target = rule == null ? 0 : Math.max(0, rule.count());
        List<Candidate> input = candidates == null ? List.of() : candidates;
        Set<String> excluded = rule != null && rule.excludeFingerprints() != null
                ? new HashSet<>(rule.excludeFingerprints()) : Set.of();

        Map<String, Candidate> unique = new LinkedHashMap<>();
        for (Candidate candidate : input) {
            if (candidate == null) {
                continue;
            }
            if (candidate.fingerprint() != null && excluded.contains(candidate.fingerprint())) {
                continue;
            }
            unique.putIfAbsent(candidate.fingerprint(), candidate);
        }
        List<Candidate> pool = new ArrayList<>(unique.values());
        pool.sort(STABLE_ORDER);

        List<Candidate> chosen = new ArrayList<>();
        Set<String> used = new HashSet<>();
        Map<String, Integer> distribution = rule == null ? null : rule.difficultyDistribution();
        if (distribution != null && !distribution.isEmpty()) {
            Map<String, List<Candidate>> byLabel = new HashMap<>();
            for (Candidate candidate : pool) {
                byLabel.computeIfAbsent(candidate.difficultyLabel(), key -> new ArrayList<>()).add(candidate);
            }
            for (String label : LABEL_ORDER) {
                int need = distribution.getOrDefault(label, 0);
                if (need <= 0) {
                    continue;
                }
                int taken = 0;
                for (Candidate candidate : byLabel.getOrDefault(label, List.of())) {
                    if (taken >= need || chosen.size() >= target) {
                        break;
                    }
                    if (used.add(candidate.fingerprint())) {
                        chosen.add(candidate);
                        taken++;
                    }
                }
            }
        }
        if (chosen.size() < target) {
            for (Candidate candidate : pool) {
                if (chosen.size() >= target) {
                    break;
                }
                if (used.add(candidate.fingerprint())) {
                    chosen.add(candidate);
                }
            }
        }
        chosen.sort(STABLE_ORDER);
        int shortfall = Math.max(0, target - chosen.size());
        return new AssemblyResult(List.copyOf(chosen), shortfall);
    }

    private static int questionIndex(String questionKey) {
        if (questionKey == null) {
            return Integer.MAX_VALUE;
        }
        int mark = questionKey.lastIndexOf('#');
        if (mark < 0) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(questionKey.substring(mark + 1));
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }
}
