package io.github.xiaou61.ai.internal.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.xiaou61.platform.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 五个一次性调用的 JSON 解析（WORK-067）：围栏要剥掉，形状不对要报错而不是返回半截。 */
class QuickInsightCallerTest {

    @Test
    @DisplayName("剥掉 ```json 围栏再解析")
    void stripsCodeFence() {
        JsonNode root = QuickInsightCaller.parse("```json\n{\"steps\":[\"a\",\"b\"]}\n```");
        assertThat(QuickInsightCaller.strings(root, "steps", 1, 3)).containsExactly("a", "b");
    }

    @Test
    @DisplayName("条数超出范围就拒绝")
    void rejectsOutOfRange() {
        JsonNode root = QuickInsightCaller.parse("{\"steps\":[\"a\",\"b\",\"c\",\"d\"]}");
        assertThatThrownBy(() -> QuickInsightCaller.strings(root, "steps", 1, 3))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("格式不对");
    }

    @Test
    @DisplayName("不是 JSON 就拒绝，不猜")
    void rejectsNonJson() {
        assertThatThrownBy(() -> QuickInsightCaller.parse("我觉得这道题选 B"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("格式不对");
    }
}
