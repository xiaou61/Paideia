package io.github.xiaou61.ops;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * trace 属性的白名单（WORK-043 REQ-018）。
 *
 * <p>只允许能解释“哪里慢/哪里错”的低敏属性；SQL 语句、请求体、Authorization、Cookie
 * 一律不进入证据。宁可少给模型一点上下文，也不把用户数据带出去。
 */
final class TempoTraceRedactor {

    private static final Set<String> ALLOWED = Set.of(
            "service.name",
            "http.method",
            "http.request.method",
            "http.route",
            "url.path",
            "http.status_code",
            "http.response.status_code",
            "db.system",
            "db.operation",
            "rpc.service",
            "rpc.method",
            "server.address",
            "error.type");

    private TempoTraceRedactor() {
    }

    static boolean allowed(String key) {
        return key != null && ALLOWED.contains(key);
    }

    static Map<String, String> filter(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> kept = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (allowed(entry.getKey()) && entry.getValue() != null && !entry.getValue().isBlank()) {
                kept.put(entry.getKey(), trim(entry.getValue(), 200));
            }
        }
        return kept;
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
