package io.github.xiaou61.ai.internal.essay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 正文拼装与旧实现逐字一致（WORK-084 TASK-002，行为不变）。 */
class EssayBodyRendererTest {

    @Test
    void renderMatchesLegacy() {
        assertThat(EssayBodyRenderer.render(
                        "线程池有哪些核心参数？", "答案正文", "https://example.com/post/1", "某面经合集"))
                .isEqualTo("## 线程池有哪些核心参数？\n\n答案正文\n\n---\n\n"
                        + "> 题目来源：[某面经合集](https://example.com/post/1)\n");
    }

    @Test
    void blankSourceTitleFallsBackToPlaceholder() {
        assertThat(EssayBodyRenderer.render("题", "答", "https://example.com/1", "  "))
                .contains("> 题目来源：[原帖](https://example.com/1)");
    }

    @Test
    void bracketsInSourceTitleAreNeutralisedSoLinkSyntaxSurvives() {
        assertThat(EssayBodyRenderer.render("题", "答", "https://example.com/1", "字节[一二面]"))
                .contains("> 题目来源：[字节(一二面)](https://example.com/1)");
    }
}
