package io.github.xiaou61.ai.internal.summary;

import io.github.xiaou61.platform.Hashes;
import java.nio.charset.StandardCharsets;

/**
 * 正文指纹：正文的 SHA-256（十六进制）。
 *
 * <p>它是缓存键的一部分，作用是让"正文改了"这件事**自动**使旧结果失效。用哈希而不是
 * 条目表的 `updated_at`：后者依赖"任何一次正文改动都会刷新时间戳"这个隐式前提，
 * 而哈希把"内容没变就不该重新生成"写成显式事实，成本可以忽略。
 */
final class SummaryFingerprint {

    private SummaryFingerprint() {
    }

    static String of(String body) {
        return Hashes.sha256Hex(body == null ? "" : body);
    }
}
