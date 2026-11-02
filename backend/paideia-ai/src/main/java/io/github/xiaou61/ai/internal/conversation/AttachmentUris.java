package io.github.xiaou61.ai.internal.conversation;

import java.util.ArrayList;
import java.util.List;

/**
 * 从附着文本里取出对象 URI（WORK-058），只为了落 `ai_runs.attrs` 方便排查。
 *
 * <p><b>为什么要从文本里再取一遍</b>：前端只发一段文本（消息契约不变），这段文本里有一行
 * `附着对象：entry:123, note:45`。这里只认这一行的前缀；取不到就返回空——
 * 观测是锦上添花，解析失败不该影响这一轮。
 *
 * <p>**那一行不一定在开头**：前端的 `turnContext` 是「页面场景 + 附件」拼起来的，页面场景
 * 本身就占 7~13 行，只看前 8 行会把它挤掉（2026-11-02 审查抓到，当时最常见的
 * 「学习记录/SQL 工作台 + 附件」恒为空）。所以整段按行扫。
 *
 * <p>改格式时**必须同时改** `frontend/packages/assistant/src/attachments.ts` 的
 * `serializeAttachments`。
 */
public final class AttachmentUris {

    private static final String PREFIX = "附着对象：";
    /** 与前端的上限一致：多了也只会是脏数据。 */
    private static final int MAX = 5;
    /** 单条 URI 的长度上限。用户可控文本能被塞进这一行，落库前必须收窄。 */
    private static final int MAX_URI_CHARS = 64;

    private AttachmentUris() {
    }

    public static List<String> of(String pageContext) {
        if (pageContext == null || pageContext.isEmpty()) {
            return List.of();
        }
        for (String line : pageContext.split("\n", -1)) {
            String trimmed = line.strip();
            if (!trimmed.startsWith(PREFIX)) {
                continue;
            }
            List<String> uris = new ArrayList<>();
            for (String part : trimmed.substring(PREFIX.length()).split(",", -1)) {
                String uri = part.strip();
                if (isUriLike(uri) && uris.size() < MAX) {
                    uris.add(uri);
                }
            }
            return uris;
        }
        return List.of();
    }

    /** 只收 `type:id` 形状的短串——别把附件正文一起吞进来写成一条脏 URI。 */
    private static boolean isUriLike(String uri) {
        int colon = uri.indexOf(':');
        return colon > 0 && colon + 1 < uri.length() && uri.length() <= MAX_URI_CHARS
                && uri.indexOf('\n') < 0 && uri.indexOf('\r') < 0;
    }
}
