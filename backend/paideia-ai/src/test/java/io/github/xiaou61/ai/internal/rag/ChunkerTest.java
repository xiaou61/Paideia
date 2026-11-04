package io.github.xiaou61.ai.internal.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkerTest {

    @Test
    void longBodyKeepsAllText() {
        String body = "第一段内容。".repeat(120);
        List<String> chunks = Chunker.chunk("标题", body, 200, 40);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.getFirst()).startsWith("标题\n");
        assertThat(String.join("", chunks)).contains("第一段内容。");
    }

    @Test
    void blankBodyHasNoChunks() {
        assertThat(Chunker.chunk("标题", "  \n\n ", 500, 80)).isEmpty();
    }

    @Test
    void overlapDoesNotLoseTheTail() {
        String body = "abcdefghij".repeat(80);
        List<String> chunks = Chunker.chunk("", body, 300, 50);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.getLast()).endsWith("abcdefghij");
    }
}
