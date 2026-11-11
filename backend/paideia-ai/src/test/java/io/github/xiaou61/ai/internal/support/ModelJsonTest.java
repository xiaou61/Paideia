package io.github.xiaou61.ai.internal.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModelJsonTest {

    @Test
    @DisplayName("围栏包住的 JSON：剥掉围栏再取对象")
    void fencedObject() {
        assertThat(ModelJson.objectBody("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("前后有解释文字：取第一个 { 到最后一个 }")
    void proseAroundObject() {
        assertThat(ModelJson.objectBody("好的，结果如下：{\"a\":1} 以上。")).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("围栏 + 前后废话同时出现也兜得住")
    void fencedWithProse() {
        assertThat(ModelJson.objectBody("结果：\n```json\n{\"a\":[1,2]}\n```\n完毕"))
                .isEqualTo("{\"a\":[1,2]}");
    }

    @Test
    @DisplayName("没有 JSON 对象：返回 null，不猜边界")
    void noObject() {
        assertThat(ModelJson.objectBody("抱歉，我无法完成这个请求")).isNull();
        assertThat(ModelJson.objectBody(null)).isNull();
        assertThat(ModelJson.objectBody("   ")).isNull();
    }

    @Test
    @DisplayName("只剩左括号（被 maxTokens 截断）：返回 null 而不是半截正文")
    void truncatedObject() {
        assertThat(ModelJson.objectBody("{\"a\":1")).isNull();
    }
}
