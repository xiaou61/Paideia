package io.github.xiaou61.ai.internal.conversation;

import java.util.ArrayList;
import java.util.List;

/**
 * 从附着文本里取出对象 URI（WORK-058），只为了落 `ai_runs.attrs` 方便排查。
 *
 * <p><b>为什么要从文本里再取一遍</b>：前端只发一段文本（消息契约不变），它的首行是
 * `附着对象：entry:123, note:45`。这里只认这一行的前缀；取不到就返回空——
 * 观测是锦上添花，解析失败不该影响这一轮。
 *
 * <p>改格式时**必须同时改** `frontend/packages/assistant/src/attachments.ts` 的
 * `serializeAttachments`。
 */
public final class AttachmentUris {

    private static final String PREFIX = "附着对象：";
    /** 与前端的上限一致：多了也只会是脏数据。 */
    private static final int MAX = 5;

    private AttachmentUris() {
    }

    public static List<String> of(String pageContext) {
        if (pageContext == null || pageContext.isEmpty()) {
            return List.of();
        }
        // 只看前几行：附着块永远是这段文本的开头
        for (String line : pageContext.split("\n", 8)) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(PREFIX)) {
                continue;
            }
            List<String> uris = new ArrayList<>();
            for (String part : trimmed.substring(PREFIX.length()).split(",")) {
                String uri = part.trim();
                if (!uri.isEmpty() && uris.size() < MAX) {
                    uris.add(uri);
                }
            }
            return uris;
        }
        return List.of();
    }
}
