package io.github.xiaou61.ai.internal.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 附件 URI 的抽取（WORK-058）：只认首行前缀，取不到不影响这一轮。 */
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
}
