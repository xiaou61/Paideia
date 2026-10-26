package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 日志脱敏的已知模式（WORK-042 AC-009）。
 *
 * <p>断言里的值都是刻意挑的短串：如果某个模式失效，它们会原样出现在输出里，
 * 而不是被其他规则顺带打掉。
 */
class OpsLogRedactorTest {

    @Test
    @DisplayName("邮箱、Bearer、JWT、赋值式令牌、长十六进制串全部替换")
    void redactsKnownSecrets() {
        String input = "user=alice@example.com Authorization: Bearer abcdefghijklmnop "
                + "password=Hunter2 token: tok_123 api_key=AKIA1234567890 "
                + "jwt=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U "
                + "hash=0123456789abcdef0123456789abcdef";

        String out = OpsLogRedactor.redact(input);

        assertThat(out).doesNotContain(
                "alice@example.com",
                "abcdefghijklmnop",
                "Hunter2",
                "tok_123",
                "AKIA1234567890",
                "eyJhbGciOiJIUzI1NiJ9",
                "0123456789abcdef0123456789abcdef");
        assertThat(out).contains("***");
    }

    @Test
    @DisplayName("控制字符被替换、超长行被截断")
    void truncatesAndStripsControlChars() {
        String out = OpsLogRedactor.redact("a\u0000b\u001Fc" + "x".repeat(600));

        assertThat(out).doesNotContain("\u0000", "\u001F");
        assertThat(out).hasSizeLessThanOrEqualTo(OpsLogRedactor.MAX_LINE_CHARS + 1);
        assertThat(out).endsWith("…");
    }

    @Test
    @DisplayName("拼接同时受条数与总字符上限约束")
    void joinRespectsBothLimits() {
        String out = OpsLogRedactor.joinRedacted(
                List.of("one", "two", "three", "four"), 2, 8);

        assertThat(out).isEqualTo("one\ntwo\n");
    }

    @Test
    @DisplayName("空行被跳过而不是留空行")
    void blanksAreSkipped() {
        assertThat(OpsLogRedactor.joinRedacted(List.of("", "  ", "ok"), 10, 100))
                .isEqualTo("ok\n");
    }
}
