package io.github.xiaou61.account.internal.mail;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用内存信箱。
 *
 * <p>它只在测试期存在（打成 test-jar 供 paideia-app 的集成测试复用），**不进生产代码**。
 * 有了它，注册链路的验证码可以从测试里直接读出来，因此注册全链路的验证
 * 不依赖真实 SMTP——这也是"SMTP 凭据最后再给"不阻断验证的原因。
 */
public class InMemoryMailPort implements MailPort {

    private final Map<String, String> codesByRecipient = new ConcurrentHashMap<>();
    private final Map<String, String> resetLinksByRecipient = new ConcurrentHashMap<>();

    @Override
    public void sendVerificationCode(String to, String code, Duration validFor) {
        codesByRecipient.put(to, code);
    }

    @Override
    public void sendPasswordReset(String to, String resetUrl, Duration validFor) {
        resetLinksByRecipient.put(to, resetUrl);
    }

    /** 读取最近一次发给该收件人的验证码明文（生产代码里没有任何路径能拿到它）。 */
    public Optional<String> latestCodeFor(String to) {
        return Optional.ofNullable(codesByRecipient.get(to));
    }

    /** 读取最近一次发给该收件人的重置链接（含令牌明文，只在测试里拿得到）。 */
    public Optional<String> latestPasswordResetFor(String to) {
        return Optional.ofNullable(resetLinksByRecipient.get(to));
    }

    public void clear() {
        codesByRecipient.clear();
        resetLinksByRecipient.clear();
    }
}
