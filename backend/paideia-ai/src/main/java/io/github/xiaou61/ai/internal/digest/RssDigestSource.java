package io.github.xiaou61.ai.internal.digest;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 一个 RSS/Atom 源（WORK-027）。
 *
 * <p><b>一个类服务五个源</b>而不是五个类：它们的行为完全一样（拿 XML、取 title/link/pubDate），
 * 差别只在 URL 与显示名。这与 WORK-025「一源一类」不同——牛客是结构化 API，语义与 RSS 完全不同。
 *
 * <p>URL、显示名、分组全部来自配置（{@link DigestProperties.RssSource}），加一个 RSS 源不改代码。
 */
@Component
class RssDigestSource {

    /** 源主机的可识别 UA。带 UA 是抓取的礼貌，也让对方日志里能查到来源。 */
    private static final String USER_AGENT = "PaideiaDigest/1.0 (+daily tech digest)";

    private final FeedXml feedXml;

    RssDigestSource(FeedXml feedXml) {
        this.feedXml = feedXml;
    }

    /**
     * 抓一个 RSS 源。
     *
     * @throws DigestSource.DigestSourceException 网络失败、非 2xx、XML 非法或带 DOCTYPE
     */
    List<FetchedItem> fetch(DigestProperties.RssSource source, DigestHttp http, long sinceMillis,
                            long nowMillis) {
        String body = http.getText(source.getUrl(), USER_AGENT);
        List<FeedXml.Entry> entries = feedXml.parse(body, nowMillis);
        return entries.stream()
                .filter(entry -> entry.publishedAt() >= sinceMillis)
                .map(entry -> new FetchedItem(entry.title(), entry.link(), entry.summaryHint(),
                        null, null, null, null, entry.publishedAt(), source.getKey(), source.getName(),
                        source.getGroup()))
                .toList();
    }

    /** 让编排层能按 key 找到对应配置（测试里也用得上）。 */
    static Map<String, DigestProperties.RssSource> index(List<DigestProperties.RssSource> sources) {
        Map<String, DigestProperties.RssSource> byKey = new LinkedHashMap<>();
        for (DigestProperties.RssSource source : sources) {
            byKey.put(source.getKey(), source);
        }
        return byKey;
    }
}
