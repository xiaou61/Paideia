package io.github.xiaou61.ai.internal.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 附件 URI 的抽取（WORK-058）：只认那一行前缀，取不到不影响这一轮。 */
class AttachmentUrisTest {

    @Test
    @DisplayName("从首行取出 URI 列表")
    void parsesHeaderLine() {
        String context = "附着对象：entry:123, note:45\n\n### 附件 1 · entry:123 · 第 13 关";
        assertThat(AttachmentUris.of(context)).containsExactly("entry:123", "note:45");
    }

    @Test
    @DisplayName("没有附着块时返回空（SQL 工作台、学习记录都走这条）")
    void emptyWhenNoAttachments() {
        assertThat(AttachmentUris.of(null)).isEmpty();
        assertThat(AttachmentUris.of("")).isEmpty();
        assertThat(AttachmentUris.of("页面：SQL 工作台\n当前数据集：shop")).isEmpty();
    }

    @Test
    @DisplayName("超过 5 条只留前 5 条，空项丢掉")
    void capsAndSkipsBlanks() {
        String context = "附着对象：entry:1, entry:2,, entry:3, entry:4, entry:5, entry:6";
        assertThat(AttachmentUris.of(context)).containsExactly("entry:1", "entry:2", "entry:3", "entry:4", "entry:5");
    }

    /**
     * 附着行排在页面场景**之后**——这是前端真实的拼法（`turnContext` 先页面后附件），
     * 而 SQL 工作台那一段自己就有 9 行。原先只看前 8 行，于是最常见的这段请求恒为空。
     */
    @Test
    @DisplayName("附着行不在开头也要取到")
    void findsHeaderBelowTheFirstLines() {
        String pageContext = String.join("\n",
                "页面：SQL 工作台", "当前数据集：shop", "", "编辑器里的 SQL（用户还没提交，可能没写完）：",
                "```sql", "SELECT * FROM orders", "```", "",
                "如果用户要造新数据集：用 `planner_build_dataset` 拼 SQL，不要凭记忆写建表语句。",
                "如果用户要改现有数据集：先用 `sql_read_dataset` 读它的完整 SQL，再在原 SQL 上改；保存由用户在页面上确认。");
        String context = pageContext + "\n\n附着对象：entry:123, note:45\n\n### 附件 1 · entry:123 · 第 13 关";

        assertThat(AttachmentUris.of(context)).containsExactly("entry:123", "note:45");
    }

    /** 附件正文里带换行的脏串不能当成一条 URI 落库。 */
    @Test
    @DisplayName("不合法或超长的条目被丢掉")
    void rejectsDirtyEntries() {
        String context = "附着对象：entry:123, 不是URI, entry:9999999999999999999999999999999999999999999999999999999999999999999";
        assertThat(AttachmentUris.of(context)).containsExactly("entry:123");
    }
}
