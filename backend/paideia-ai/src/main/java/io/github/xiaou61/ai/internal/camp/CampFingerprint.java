package io.github.xiaou61.ai.internal.camp;

import io.github.xiaou61.platform.Hashes;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 题干指纹（WORK-029）。
 *
 * <p>用途只有一个：**同一卷里不出重复题**。复测还有第二个用途——把诊断卷的全部题干
 * 作为"不要再出这些"传给模型，落库后再用指纹核对交集是否为空。
 *
 * <p>规范化只做三件事：去标点与空白、转小写、取前 40 字。**不做同义改写**：那需要模型，
 * 而模型参与的判重既不可复现，也会把"换了个说法的同一道题"当成两道。
 */
public final class CampFingerprint {

    /** 参与哈希的字数上限。够区分题干，又不会被长材料的尾巴带偏。 */
    private static final int STEM_CHARS = 40;

    private CampFingerprint() {
    }

    public static String of(String stem) {
        String normalized = normalize(stem);
        return sha256(normalized);
    }

    static String normalize(String stem) {
        if (stem == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < stem.length() && builder.length() < STEM_CHARS; index++) {
            char value = stem.charAt(index);
            if (Character.isLetterOrDigit(value)) {
                builder.append(value);
            }
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        // 收敛到共享件（WORK-062）：手写 forDigit 与 HexFormat 输出逐位一致
        return Hashes.sha256Hex(value);
    }
}
