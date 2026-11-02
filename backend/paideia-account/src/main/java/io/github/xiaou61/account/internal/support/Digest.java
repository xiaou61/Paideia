package io.github.xiaou61.account.internal.support;

import io.github.xiaou61.platform.Hashes;
import java.nio.charset.StandardCharsets;

/**
 * 摘要工具。
 *
 * <p>两类用途：① 一次性凭据（refresh 令牌、邮箱验证码、图形验证码答案）入库/入内存前先摘要，
 * 使记录泄露也无法直接冒用；② 限流键用邮箱摘要而不是邮箱本身，避免把个人标识散落在内存里。
 *
 * <p>这里**故意不加盐也不做慢哈希**：这些值本身就是高熵或短寿命的，加盐没有意义；
 * 慢哈希会让每次校验都付出无谓的 CPU。密码是另一回事——它必须用 BCrypt，见
 * {@code PasswordHasher}。
 */
public final class Digest {

    private Digest() {
    }

    public static String sha256Hex(String value) {
        // 收敛到共享件（WORK-062）；调用方不动
        return Hashes.sha256Hex(value);
    }
}
