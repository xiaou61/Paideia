package io.github.xiaou61.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 摘要（WORK-062 收敛）。
 *
 * <p>全仓原有 9 处 SHA-256 十六进制、三种写法（`HexFormat` / 手写 `Character.forDigit` /
 * `String.format("%02x")`）与四种异常文案，输出逐位一致——只留这一份实现。
 *
 * <p>唯一的"有意不同"在 ai 的 {@code EssayCollectionService}：它的失败分支**返回原值**
 * （不丢内容），调用处保留自己的 try/catch，本类照常抛。
 */
public final class Hashes {

    private Hashes() {
    }

    /**
     * SHA-256 的十六进制（小写、无分隔符）。
     *
     * <p>SHA-256 是 JDK 必须实现的算法——走到 catch 说明运行环境坏了，直接抛而不是吞：
     * 指纹/缓存的调用方拿不到"算不出来"这个事实时会静默产生错误结果。
     */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", missing);
        }
    }
}