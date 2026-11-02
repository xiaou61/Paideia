package io.github.xiaou61.ai.internal.tools;

import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolArgs;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 起草一条笔记（WORK-019 迁自 {@code AssistantTools.draft_note}）。
 *
 * <p><b>它不写库</b>：只把标题与正文包成结构化结果交出去，界面据此问用户"要不要存"。
 * 这是"自主只建议不动作"的落点——模型能提议，不能落笔。
 *
 * <p>它是**内部专属**工具（`defaultGroup=false`，工具组 `assistant` 只在内部 harness 的清单里）：
 * 外部客户端没有那个确认 UI，把草稿发给它只会得到一段没人会确认的文本。
 */
@Component
public class DraftNoteTool implements PaideiaTool {

    /** 没给标题时的兜底：空的标题会在笔记列表里显示成一行空白。 */
    static final String DEFAULT_TITLE = "来自助手的笔记";

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "assistant_draft_note", "assistant", "起草一条笔记",
                "起草一条给当前用户自己看的笔记。用户说「帮我记一条」「把这个记下来」时用它。"
                        + "**它只是草稿，不会真的保存**——要用户点确认才会存进笔记。"
                        + "正文写清完整内容（用户可能直接采用），标题一句话概括。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "title", Map.of("type", "string", "description", "笔记标题，一句话"),
                                "body", Map.of("type", "string", "description", "笔记正文，可多行")),
                        "required", List.of()),
                Map.of("type", "object",
                        "properties", Map.of(
                                "title", Map.of("type", "string"),
                                "body", Map.of("type", "string"))),
                true, false, true, ToolScope.READ, false);
    }

    @Override
    public ToolResult call(ToolContext context, Map<String, Object> arguments) {
        String body = stringOf(arguments, "body");
        if (body == null) {
            // 空草稿没有意义：让模型知道这次没成，它可以补上再提一次
            return ToolResult.fail("草稿的正文不能为空");
        }
        String title = stringOf(arguments, "title");
        String effectiveTitle = title == null ? DEFAULT_TITLE : title;
        return ToolResult.ok("已准备一条笔记草稿（标题：" + effectiveTitle + "），等待用户确认。",
                Map.of("title", effectiveTitle, "body", body));
    }

    private static String stringOf(Map<String, Object> arguments, String key) {
        return ToolArgs.stringOf(arguments, key);
    }
}
