package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 只断言「组装出来的邮件长什么样」，不真的发信。
 *
 * <p>{@code createMimeMessage()} 不需要连上 SMTP，所以把发信与组装拆开之后，
 * 多部件结构、HTML 开关、附件这类容易出错的地方都能在没有服务器的前提下验。
 */
class OpsDiagnosisMailerTest {

    private static OpsDiagnosisMailer mailer(boolean htmlEnabled) {
        OpsMailProperties mail = new OpsMailProperties("smtp.example.com", 465, "bot@example.com",
                "secret", "ops@example.com", "Paideia 运维", false, true, htmlEnabled);
        OpsProperties ops = new OpsProperties();
        ops.getNotify().setEnabled(true);
        ops.getNotify().setTo("ops@example.com");
        ops.getNotify().setBaseUrl("http://h:8081");
        return new OpsDiagnosisMailer(mail, ops);
    }

    private static List<Part> parts(MimeMessage message) throws Exception {
        List<Part> parts = new ArrayList<>();
        collect(message, parts);
        return parts;
    }

    private static void collect(Part part, List<Part> acc) throws Exception {
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                // 嵌套的 alternative 里才是真正的 text/plain 与 text/html
                if (child.getContent() instanceof Multipart) {
                    collect(child, acc);
                } else {
                    acc.add(child);
                }
            }
            return;
        }
        acc.add(part);
    }

    @Test
    @DisplayName("开着 HTML 时邮件是「纯文本 + HTML + .md 附件」三件套")
    void composeCarriesPlainHtmlAndAttachment() throws Exception {
        MimeMessage message = mailer(true).compose(new String[] {"ops@example.com"}, 42L,
                "CPU 相对 7 天基线异常", "## 结论\n进程重启 4 次，不是崩溃。", List.of(), "http://h:8081");

        List<Part> parts = parts(message);

        assertThat(parts).anySatisfy(part -> {
            assertThat(contentType(part)).contains("text/plain");
            assertThat(text(part)).contains("进程重启 4 次").contains("■ 结论");
        });
        assertThat(parts).anySatisfy(part -> {
            assertThat(contentType(part)).contains("text/html");
            assertThat(text(part)).contains("class=\"verdict\"").contains("<h2>结论</h2>");
        });
        assertThat(parts).anySatisfy(part -> {
            assertThat(part.getFileName()).isEqualTo("diagnosis-42.md");
            assertThat(contentType(part)).contains("text/markdown");
            assertThat(text(part)).contains("进程重启 4 次");
        });
        assertThat(message.getSubject()).contains("【Paideia 运维·已自动诊断】");
    }

    @Test
    @DisplayName("关掉 HTML 只影响正文部件，附件与纯文本照旧")
    void composeWithoutHtmlKeepsPlainAndAttachment() throws Exception {
        MimeMessage message = mailer(false).compose(new String[] {"ops@example.com"}, 7L,
                "X", "## 结论\nok", List.of(), "http://h:8081");

        List<Part> parts = parts(message);

        assertThat(parts).noneSatisfy(part -> assertThat(contentType(part)).contains("text/html"));
        assertThat(parts).anySatisfy(part -> {
            assertThat(part.getFileName()).isEqualTo("diagnosis-7.md");
        });
    }

    @Test
    @DisplayName("没有诊断 id 时不挂附件，避免出现 diagnosis-0.md")
    void composeSkipsAttachmentWithoutId() throws Exception {
        MimeMessage message = mailer(true).compose(new String[] {"ops@example.com"}, 0L,
                "X", "## 结论\nok", List.of(), "http://h:8081");

        assertThat(parts(message)).noneSatisfy(part ->
                assertThat(part.getFileName()).isNotNull());
    }

    private static String contentType(Part part) throws Exception {
        String type = part.getContentType();
        return type == null ? "" : type.toLowerCase();
    }

    private static String text(Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof java.io.InputStream in) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        return String.valueOf(content);
    }
}
