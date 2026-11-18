package io.github.xiaou61.knowledge.internal.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaperAssemblerTest {

    private static PaperAssembler.Candidate candidate(long entryId, String block, int index,
                                                      String difficulty, String fingerprint) {
        return new PaperAssembler.Candidate(entryId, block, block + "#" + index, fingerprint, 2,
                difficulty, List.of());
    }

    private static PaperAssembler.PaperRule rule(int count, Map<String, Integer> distribution,
                                                 List<String> exclude) {
        return new PaperAssembler.PaperRule("t", null, null, distribution, count, exclude);
    }

    private static List<String> fingerprints(List<PaperAssembler.Candidate> items) {
        return items.stream().map(PaperAssembler.Candidate::fingerprint).toList();
    }

    @Test
    void sameRuleTwiceIsIdentical() {
        List<PaperAssembler.Candidate> pool = List.of(
                candidate(1, "a", 0, "medium", "f0"),
                candidate(1, "a", 1, "medium", "f1"),
                candidate(2, "b", 0, "hard", "f2"));
        PaperAssembler.PaperRule rule = rule(2, Map.of("medium", 1, "hard", 1), null);

        PaperAssembler.AssemblyResult first = PaperAssembler.assemble(pool, rule);
        PaperAssembler.AssemblyResult second = PaperAssembler.assemble(pool, rule);

        assertEquals(fingerprints(first.items()), fingerprints(second.items()));
        assertEquals(2, first.items().size());
    }

    @Test
    void shortfallIsReportedNotSilentlyDropped() {
        List<PaperAssembler.Candidate> pool = List.of(
                candidate(1, "a", 0, "medium", "f0"),
                candidate(1, "a", 1, "medium", "f1"));

        PaperAssembler.AssemblyResult result = PaperAssembler.assemble(pool, rule(5, Map.of(), null));

        assertEquals(2, result.items().size());
        assertEquals(3, result.shortfall());
    }

    @Test
    void duplicateAndExcludedFingerprintsAreDropped() {
        List<PaperAssembler.Candidate> pool = List.of(
                candidate(1, "a", 0, "medium", "f0"),
                candidate(1, "a", 0, "medium", "f0"),
                candidate(1, "a", 1, "medium", "f1"),
                candidate(1, "a", 2, "medium", "f2"));

        PaperAssembler.AssemblyResult result =
                PaperAssembler.assemble(pool, rule(10, Map.of(), List.of("f2")));

        assertEquals(List.of("f0", "f1"), fingerprints(result.items()));
    }

    @Test
    void outputOrderIsStableRegardlessOfInputOrder() {
        List<PaperAssembler.Candidate> pool = new ArrayList<>(List.of(
                candidate(2, "b", 0, "medium", "f9"),
                candidate(1, "a", 1, "medium", "f1"),
                candidate(1, "a", 0, "medium", "f0")));
        Collections.shuffle(pool);

        PaperAssembler.AssemblyResult result = PaperAssembler.assemble(pool, rule(3, Map.of(), null));

        assertEquals(List.of("f0", "f1", "f9"), fingerprints(result.items()));
        assertTrue(result.items().size() == 3);
    }
}
