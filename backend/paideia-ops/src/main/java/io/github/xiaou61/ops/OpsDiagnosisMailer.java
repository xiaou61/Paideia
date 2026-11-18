package io.github.xiaou61.ops;

import jakarta.mail.MessagingException;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 把 AI 诊断结论发到运维邮箱。
 *
 * <p><b>为什么由后端发，而不是靠 Grafana</b>：Grafana 的告警邮件是内置模板，只有 labels、
 * {@code Value: [no value]} 和一堆（回环 {@code root_url} 的）死链；真正能看懂的那份诊断
 * 报告一直躺在 {@code ops_diagnoses} 表里，从来没被送出去。这里在诊断完成后直接把"答案"发走。
 *
 * <p><b>失败不进主流程</b>：邮件是尽力而为的旁路。SMTP 超时、认证失败都只记一条 warn，
 * 绝不回滚已经落库的诊断报告，也不让告警处理因为发信失败而失败。
 *
 * <p>传输层手工装配 {@link JavaMailSenderImpl}（而不是 Boot 的 {@code spring.mail.*} 自动配置），
 * 因为本项目的配置命名空间统一是 {@code paideia.*}。
 */
@Component
class OpsDiagnosisMailer {

    private static final Logger log = LoggerFactory.getLogger(OpsDiagnosisMailer.class);

    private final OpsMailProperties mail;
    private final OpsProperties ops;
    private final JavaMailSenderImpl sender;

    OpsDiagnosisMailer(OpsMailProperties mail, OpsProperties ops) {
        this.mail = mail;
        this.ops = ops;
        this.sender = buildSender(mail);
    }

    private static JavaMailSenderImpl buildSender(OpsMailProperties mail) {
        if (!mail.hasHost()) {
            return null;
        }
        JavaMailSenderImpl configured = new JavaMailSenderImpl();
        configured.setHost(mail.host());
        configured.setPort(mail.port());
        configured.setUsername(mail.username());
        configured.setPassword(mail.password());
        configured.setDefaultEncoding("UTF-8");
        var props = configured.getJavaMailProperties();
        props.put("mail.smtp.auth", String.valueOf(mail.hasCredentials()));
        // 两种加密方式互斥：465 用隐式 TLS（ssl=true），587 用 STARTTLS（startTls=true）
        props.put("mail.smtp.ssl.enable", String.valueOf(mail.ssl()));
        props.put("mail.smtp.starttls.enable", String.valueOf(mail.startTls()));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.debug", "false");
        return configured;
    }

    /**
     * 诊断完成后调用。只在配置了收件人且 SMTP 可用的前提下真正发信。
     *
     * @param alertName 告警名（主题与正文抬头用）
     * @param reportMd  AI 生成的诊断报告原文
     */
    void sendDiagnosis(String alertName, String reportMd) {
        OpsProperties.Notify notify = ops.getNotify();
        if (sender == null || !notify.isEnabled()) {
            return;
        }
        String[] recipients = recipients(notify.getTo());
        if (recipients.length == 0) {
            log.debug("ops.mail.skipped reason=no-recipient alert={}", alertName);
            return;
        }
        try {
            var message = sender.createMimeMessage();
            // false = 非 multipart（纯文本）；UTF-8 必填，否则中文主题乱码
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(mail.fromAddress(), mail.fromName());
            helper.setTo(recipients);
            helper.setSubject(OpsDiagnosisEmail.subject(alertName));
            helper.setText(OpsDiagnosisEmail.body(alertName, reportMd, incidentLink(notify.getBaseUrl())), false);
            sender.send(message);
            log.info("ops.mail.sent alert={} recipients={}", alertName, recipients.length);
        } catch (MessagingException | UnsupportedEncodingException | MailException failure) {
            // MailException 必须一并接住：send() 抛的是 Spring 包装过的运行时异常
            log.warn("ops.mail.failed alert={} error={}", alertName, failure.toString());
        }
    }

    private static String[] recipients(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    /** 管理端事件页（Hash 路由：/admin/#/ops/incidents）。 */
    private static String incidentLink(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        String base = baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/admin/#/ops/incidents";
    }
}
