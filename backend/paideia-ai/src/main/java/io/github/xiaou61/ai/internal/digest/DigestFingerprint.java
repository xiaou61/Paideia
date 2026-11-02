package io.github.xiaou61.ai.internal.digest;

import io.github.xiaou61.platform.Hashes;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 规范化 URL 并取 SHA-256 作为去重指纹（WORK-027）。
 *
 * <p><b>为什么必须规范化</b>：同一条新闻被两个门户转时，通常带各自的追踪参数
 * （`utm_source`、`from`、`spm`…）。不剥掉，同一条内容就是两条，AC-002 直接不成立。
 *
 * <p>处理四件事，每条都有对应单测：
 * <ol>
 *   <li>剥掉追踪参数（白名单式：只删已知的那些，不认识的参数保留——宁可多入库一条，
 *       也不能把真正区分文章的 `?id=123` 删掉）；</li>
 *   <li>去掉末尾 `/`（`https://a.com/x/` 与 `https://a.com/x` 是同一篇）；</li>
 *   <li>host 小写（域名大小写不敏感）；</li>
 *   <li>去掉 fragment（`#section` 不改变文档本身）。</li>
 * </ol>
 *
 * <p>**解析不了就退回原始字符串**：有些源的链接本身不规范（缺协议、含中文未编码），
 * 抛异常会让整个源失败。退回原文至少保证"同一条不会被算成两条"。
 */
final class DigestFingerprint {

    /**
     * 要剥掉的查询参数。
     *
     * <p>只列**已知纯追踪**的那些。`ref` 在部分站点是内容标识，但它作为追踪参数更常见，
     * 且这几家源（量子位 / IT 之家 / InfoQ / 掘金 / 开源中国）的正文链接都不靠它定位。
     */
    private static final Set<String> TRACKING = Set.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
            "ref", "spm", "from", "fbclid", "gclid", "yclid", "mc_cid", "mc_eid");

    private DigestFingerprint() {
    }

    /** 规范化后的 URL（便于排查时直接看）。 */
    static String normalize(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI parsed = new URI(url.trim());
            if (parsed.getScheme() == null || parsed.getHost() == null) {
                return url.trim();
            }
            String scheme = parsed.getScheme().toLowerCase(Locale.ROOT);
            String host = parsed.getHost().toLowerCase(Locale.ROOT);
            int port = parsed.getPort();
            String path = parsed.getPath() == null ? "" : parsed.getPath();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String query = stripTracking(parsed.getRawQuery());
            StringBuilder rebuilt = new StringBuilder(scheme).append("://").append(host);
            if (port != -1) {
                rebuilt.append(':').append(port);
            }
            rebuilt.append(path);
            if (!query.isEmpty()) {
                rebuilt.append('?').append(query);
            }
            return rebuilt.toString();
        } catch (URISyntaxException exception) {
            return url.trim();
        }
    }

    /** SHA-256 十六进制。空 URL 得到空串的哈希，调用方应先滤掉空链接。 */
    static String of(String url) {
        return sha256(normalize(url));
    }

    private static String stripTracking(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = (equals < 0 ? pair : pair.substring(0, equals)).toLowerCase(Locale.ROOT);
            if (TRACKING.contains(name)) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append('&');
            }
            kept.append(pair);
        }
        return kept.toString();
    }

    private static String sha256(String value) {
        return Hashes.sha256Hex(value);
    }
}
