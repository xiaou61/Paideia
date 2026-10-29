package io.github.xiaou61.sandbox.internal.audit;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.sandbox.internal.run.CodeRun;
import io.github.xiaou61.sandbox.internal.run.SandboxProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/**
 * 配额与审计（WORK-015）。
 *
 * <p><b>配额按 UTC 日切</b>：项目里时间一律按 UTC 存与算（V6 起的约定），这里沿用；
 * 不为了"看起来像本地的一天"引入时区，那会让跨时区部署的计数不一致。
 *
 * <p><b>被拒绝的也写审计</b>，但不计入次数。写审计是为了回答"他为什么被挡"——
 * 如果只在成功时写，用户说"我明明没跑几次"时就无从对账。
 */
@Service
public class SandboxQuotaService {

    private final CodeRunMapper mapper;
    private final SandboxProperties properties;

    SandboxQuotaService(CodeRunMapper mapper, SandboxProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    /** 今天还剩几次（不分语言，Java/Python 那一档）。 */
    public int remaining(long userId) {
        return Math.max(0, properties.getDailyLimit() - usedToday(userId));
    }

    /** 今天某语言还剩几次（SQL 与 Linux 练习各有单独一档）。 */
    public int remaining(long userId, String language) {
        String own = ownLimit(language);
        if (own == null) {
            return remaining(userId);
        }
        return Math.max(0, limitFor(own) - usedToday(userId, language));
    }

    public int usedToday(long userId) {
        return mapper.countSince(userId, startOfUtcDay());
    }

    /** 今天某语言用了多少次。有独立上限的语言只数它自己的。 */
    public int usedToday(long userId, String language) {
        String own = ownLimit(language);
        if (own == null) {
            return usedToday(userId);
        }
        return mapper.countSinceForLanguage(userId, startOfUtcDay(), own);
    }

    /** 超了抛 429。**在真正执行之前调用**，避免先跑再拒绝。 */
    public void requireQuota(long userId) {
        if (usedToday(userId) >= properties.getDailyLimit()) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                    "今天的运行次数已用完（每天 " + properties.getDailyLimit() + " 次），明天再来");
        }
    }

    /**
     * 按语言检查配额（WORK-032 / WORK-056）。SQL 与 shell 各走自己的一档，与其它语言互不影响。
     *
     * <p>**顺序很重要**：调用方必须在启动容器之前调这个。
     */
    public void requireQuota(long userId, String language) {
        String own = ownLimit(language);
        if (own == null) {
            requireQuota(userId);
            return;
        }
        int used = usedToday(userId, language);
        int limit = limitFor(own);
        if (used >= limit) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                    "今天的" + ("shell".equals(own) ? " Linux 练习" : " SQL")
                            + "运行次数已用完（每天 " + limit + " 次），明天再来");
        }
    }

    /** 有独立上限的语言返回它自己，其余返回 null（走通用额度）。 */
    static String ownLimit(String language) {
        if (language == null) {
            return null;
        }
        String normalized = language.trim().toLowerCase(java.util.Locale.ROOT);
        return "sql".equals(normalized) || "shell".equals(normalized) ? normalized : null;
    }

    private int limitFor(String own) {
        return "shell".equals(own) ? properties.getShellDailyLimit() : properties.getSqlDailyLimit();
    }

    /** 写一条审计。`status` 用 {@link CodeRun.Status} 的名字，存进去就是稳定值。 */
    public void record(long userId, Long entryId, String caseId, String language,
                       CodeRun.Status status, Integer exitCode, long durationMs,
                       int outputBytes, boolean truncated, String code) {
        mapper.insert(userId, entryId, caseId, language, status.name(), exitCode,
                durationMs, outputBytes, truncated, sha256(code), code.length(), Instant.now());
    }

    /** 把运行结果归到审计状态。判错与"跑起来了但程序返回非 0"是两回事。 */
    public static CodeRun.Status statusOf(CodeRun.Result result) {
        if (result.timedOut() && result.outOfMemory()) {
            return CodeRun.Status.OUT_OF_MEMORY;
        }
        if (result.timedOut()) {
            return CodeRun.Status.TIMEOUT;
        }
        if (result.outOfMemory()) {
            return CodeRun.Status.OUT_OF_MEMORY;
        }
        return result.exitCode() == 0 ? CodeRun.Status.OK : CodeRun.Status.RUNTIME_ERROR;
    }

    static Instant startOfUtcDay() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException cause) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境坏了
            throw new IllegalStateException("缺少 SHA-256", cause);
        }
    }
}
