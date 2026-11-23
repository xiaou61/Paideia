package io.github.xiaou61.ops;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
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

    /** HTML 正文的报告字符上限；超过就退回纯文本 + `.md` 附件。 */
    static final int MAX_HTML_CHARS = 100_000;

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

    /** 不带 traceId 的简版。 */
    void sendDiagnosis(String alertName, String reportMd) {
        sendDiagnosis(0L, alertName, reportMd, List.of());
    }

    /**
     * 诊断完成后调用。只在配置了收件人且 SMTP 可用的前提下真正发信。
     *
     * @param diagnosisId 诊断 id（报告附件按它命名，0 表示不附附件）
     * @param alertName 告警名（主题与正文抬头用）
     * @param reportMd  AI 生成的诊断报告原文
     * @param traceIds  本次采集到的 traceId（用于邮件里做全链路串联；可为空）
     */
    void sendDiagnosis(long diagnosisId, String alertName, String reportMd, List<String> traceIds) {
        OpsProperties.Notify notify = ops.getNotify();
        if (sender == null || !notify.isEnabled()) {
            return;
        }
        // 没有发件地址时不能进 compose：MimeMessageHelper.setFrom(null) 抛 IllegalArgumentException，
        // 那是运行时异常，会一路冒泡成"诊断失败"（见 OpsDiagnosisService.run 的 catch）。
        if (!mail.hasSender()) {
            log.warn("ops.mail.skipped reason=no-from-address alert={}"
                    + "（配置 paideia.mail.from-address 或 paideia.mail.username）", alertName);
            return;
        }
        String[] recipients = recipients(notify.getTo());
        if (recipients.length == 0) {
            log.debug("ops.mail.skipped reason=no-recipient alert={}", alertName);
            return;
        }
        try {
            MimeMessage message = compose(recipients, diagnosisId, alertName, reportMd,
                    traceIds, notify.getBaseUrl());
            sender.send(message);
            log.info("ops.mail.sent alert={} diagnosisId={} recipients={}",
                    alertName, diagnosisId, recipients.length);
        } catch (MessagingException | UnsupportedEncodingException | MailException failure) {
            // MailException 必须一并接住：send() 抛的是 Spring 包装过的运行时异常
            log.warn("ops.mail.failed alert={} error={}", alertName, failure.toString());
        }
    }

    /**
     * 组装邮件（发信之外的全部内容都在这里，便于断言）。
     *
     * <p>三段结构：纯文本兜底 + HTML 正文 + 报告原文 {@code .md} 附件。
     * 纯文本**始终保留**——只发 HTML 时，把 HTML 判成垃圾的客户端会收到一封空信；
     * 附件是为了让收到的人能直接把内容转存成复盘，而不用从邮件里往外复制。
     *
     * <p>multipart(true) 是必需的：要挂附件就是 mixed 结构。HTML 关掉时只改正文部件，
     * 附件与纯文本不受影响。
     */
    MimeMessage compose(String[] recipients, long diagnosisId, String alertName, String reportMd,
                        List<String> traceIds, String baseUrl)
            throws MessagingException, UnsupportedEncodingException {
        MimeMessage message = sender.createMimeMessage();
        // UTF-8 必填，否则中文主题与正文乱码
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(mail.fromAddress(), mail.fromName());
        helper.setTo(recipients);
        helper.setSubject(OpsDiagnosisEmail.subject(alertName));
        String link = incidentLink(baseUrl);
        String explore = grafanaExploreBase(baseUrl);
        String plain = OpsDiagnosisEmail.body(alertName, reportMd, link, traceIds, explore);
        // 超长报告退回纯文本（AC-010 的降级面）：几十万字符的 HTML 既撑爆客户端渲染，
        // 也会被部分企业邮箱直接截断成半封；纯文本 + .md 附件至少内容完整。
        if (mail.isHtmlEnabled() && reportMd != null && reportMd.length() <= MAX_HTML_CHARS) {
            helper.setText(plain, OpsDiagnosisEmail.html(alertName, reportMd, link, traceIds, explore));
        } else {
            helper.setText(plain, false);
        }
        if (diagnosisId > 0 && reportMd != null && !reportMd.isBlank()) {
            byte[] bytes = reportMd.getBytes(StandardCharsets.UTF_8);
            helper.addAttachment("diagnosis-" + diagnosisId + ".md",
                    () -> new ByteArrayInputStream(bytes),
                    "text/markdown; charset=UTF-8");
        }
        // 提前定稿：multipart 的结构与每个部件的头部都要在 send() 之前落到消息上，
        // 否则这里拿到的还是一堆未物化的 TextSource，读不出 content-type。
        message.saveChanges();
        return message;
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

    /** Grafana 的公网入口（走应用反代的子路径）；用于拼 Explore 深链接。 */
    private static String grafanaExploreBase(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        String base = baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/api/v1/ops/grafana";
    }
}
