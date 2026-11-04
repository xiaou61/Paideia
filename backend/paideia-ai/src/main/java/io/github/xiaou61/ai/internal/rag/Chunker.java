package io.github.xiaou61.ai.internal.rag;

import java.util.ArrayList;
import java.util.List;

/** 把条目正文切成适合 embedding 的短块（WORK-072）。 */
final class Chunker {

    private Chunker() {
    }

    static List<String> chunk(String title, String body, int maxChars, int overlap) {
        String text = body == null ? "" : body.replace("\r\n", "\n").trim();
        if (text.isEmpty()) {
            return List.of();
        }
        int max = Math.max(120, maxChars);
        int stepOverlap = Math.max(0, Math.min(overlap, max / 2));
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + max);
            if (end < text.length()) {
                int paragraph = text.lastIndexOf("\n\n", end);
                if (paragraph > start + max / 2) {
                    end = paragraph;
                }
            }
            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) {
                chunks.add((title == null || title.isBlank() ? "" : title.trim() + "\n") + piece);
            }
            if (end >= text.length()) {
                break;
            }
            start = Math.max(start + 1, end - stepOverlap);
        }
        return chunks;
    }
}
