package io.github.xiaou61.ai.internal.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.xiaou61.ai.DistilledEntry;
import io.github.xiaou61.platform.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * WORK-076 TASK-004：蒸馏产物校验。
 *
 * <p>纯单元测试，不连模型、不连库：钉死“模型返回空/非法 JSON/缺字段/超长”这些已知真实故障
 * 必须被拒，绝不落库。
 */
class DistilledEntryValidatorTest {

    private final DistilledEntryValidator validator = new DistilledEntryValidator();

    @Test
    @DisplayName("合法 JSON：解析出标题/正文/建议概念")
    void parsesValid() {
        String raw = """
                {"title":"TCP 三次握手","bodyMarkdown":"## 过程\\n1. SYN\\n2. SYN-ACK","suggestedConcepts":["三次握手","SYN"]}
                """;
        DistilledEntry entry = validator.validate(raw);
        assertThat(entry.title()).isEqualTo("TCP 三次握手");
        assertThat(entry.bodyMarkdown()).contains("SYN");
        assertThat(entry.suggestedConcepts()).containsExactly("三次握手", "SYN");
    }

    @Test
    @DisplayName("被 ```json 围栏包住：剥掉围栏后仍能解析")
    void stripsFence() {
        String raw = "```json\n{\"title\":\"T\",\"bodyMarkdown\":\"B\"}\n```";
        DistilledEntry entry = validator.validate(raw);
        assertThat(entry.title()).isEqualTo("T");
        assertThat(entry.suggestedConcepts()).isEmpty();
    }

    @Test
    @DisplayName("容忍缺少 bodyMarkdown 时用 body 字段")
    void acceptsBodyAlias() {
        DistilledEntry entry = validator.validate("{\"title\":\"T\",\"body\":\"正文\"}");
        assertThat(entry.bodyMarkdown()).isEqualTo("正文");
    }

    @Test
    @DisplayName("空输出被拒")
    void rejectsBlank() {
        assertThatThrownBy(() -> validator.validate("   "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有返回任何内容");
    }

    @Test
    @DisplayName("非法 JSON 被拒")
    void rejectsNonJson() {
        assertThatThrownBy(() -> validator.validate("这不是 JSON"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是合法 JSON");
    }

    @Test
    @DisplayName("缺标题被拒")
    void rejectsMissingTitle() {
        assertThatThrownBy(() -> validator.validate("{\"bodyMarkdown\":\"B\"}"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("缺少标题");
    }

    @Test
    @DisplayName("正文为空被拒")
    void rejectsEmptyBody() {
        assertThatThrownBy(() -> validator.validate("{\"title\":\"T\",\"bodyMarkdown\":\"   \"}"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("缺少正文");
    }

    @Test
    @DisplayName("建议概念超过 20 个：截断到 20")
    void capsConcepts() {
        StringBuilder concepts = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            if (i > 0) {
                concepts.append(',');
            }
            concepts.append("\"c").append(i).append('"');
        }
        String raw = "{\"title\":\"T\",\"bodyMarkdown\":\"B\",\"suggestedConcepts\":[" + concepts + "]}";
        assertThat(validator.validate(raw).suggestedConcepts()).hasSize(20);
    }
}
