package io.github.xiaou61.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 对象引用解析（WORK-058）。规则只有一条，但每条分支都值得钉住。 */
class ToolUriTest {

    @Test
    @DisplayName("正常 id 与带片段后缀的 id 都取数字前缀")
    void numericPrefix() {
        assertThat(ToolUri.numericId("entry:123", "entry")).contains(123L);
        // 条目页的"选中片段"就是这个形状：对象仍是那条条目
        assertThat(ToolUri.numericId("entry:123/selection", "entry")).contains(123L);
        assertThat(ToolUri.numericId("note:45", "note")).contains(45L);
    }

    @Test
    @DisplayName("类型不符一律不认：防止把 note:1 当成 entry:1")
    void typeMustMatch() {
        assertThat(ToolUri.numericId("note:1", "entry")).isEmpty();
        assertThat(ToolUri.numericId("entry:1", "Entry")).isEmpty();
        assertThat(ToolUri.numericId("entry1", "entry")).isEmpty();
    }

    @Test
    @DisplayName("空、非数字前缀、非正数、超长数字都返回空")
    void rejectsBadInput() {
        assertThat(ToolUri.numericId(null, "entry")).isEmpty();
        assertThat(ToolUri.numericId("", "entry")).isEmpty();
        // 关卡是合成对象（track/seq），没有可取的数据库主键
        assertThat(ToolUri.numericId("level:java/13", "level")).isEmpty();
        assertThat(ToolUri.numericId("entry:", "entry")).isEmpty();
        assertThat(ToolUri.numericId("entry:abc", "entry")).isEmpty();
        assertThat(ToolUri.numericId("entry:0", "entry")).isEmpty();
        assertThat(ToolUri.numericId("entry:-5", "entry")).isEmpty();
        assertThat(ToolUri.numericId("entry:99999999999999999999", "entry")).isEmpty();
    }
}
