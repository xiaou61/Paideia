package io.github.xiaou61.ai.internal.essay;

/**
 * 组装面经入库正文（WORK-084 TASK-002）：题目 + 我们的解析 + 来源链接。
 *
 * <p>从 {@code EssayCollectionService#render} 原样搬出，输出**逐字一致**，目的是让
 * 校验管线与人工复核走同一套正文格式，避免两处拼接漂移。
 *
 * <p>**不放帖子原文**：面经正文里有公司名、薪资、个人经历，逐字入库既是转载也不适合做学习内容。
 */
final class EssayBodyRenderer {

    private EssayBodyRenderer() {
    }

    static String render(String title, String answer, String sourceUrl, String sourceTitle) {
        return "## " + title + "\n\n"
                + answer + "\n\n"
                + "---\n\n"
                + "> 题目来源：[" + safeTitle(sourceTitle) + "](" + sourceUrl + ")\n";
    }

    /** 标题里若有方括号会把 markdown 链接语法弄坏；空标题给一个占位。 */
    static String safeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "原帖";
        }
        return title.replace("[", "(").replace("]", ")");
    }
}
