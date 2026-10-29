package io.github.xiaou61.ai.internal.tools;

import io.agentscope.core.model.ToolSchema;
import io.github.xiaou61.ai.Harness;
import io.github.xiaou61.ai.NoteDraft;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolCatalog;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolQuery;
import io.github.xiaou61.platform.ToolResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 内部适配器：把平台工具目录翻译成 AgentScope 的工具清单（WORK-019 TASK-003）。
 *
 * <p><b>为什么需要这一层</b>：目录是平台契约（`paideia-platform`，不含 Spring、不含 SDK），
 * harness 用的是 AgentScope 的 {@code ToolSchema}。翻译只在这一个文件里发生，于是"加一个工具"
 * 不再需要改 harness——只要在业务模块注册一个 {@link PaideiaTool}。
 *
 * <p><b>按需加载（REQ-007/008）</b>：目录里的工具数超过 {@code paideia.ai.tools.eager-limit}
 * 时，额外提供一个 {@code find_tools} 元工具。模型先搜、拿到结果后**同一请求的下一轮**就能调用
 * 命中的工具。DeepSeek 没有 Anthropic/OpenAI 那种端侧工具搜索，所以这个机制只能自己做。
 *
 * <p><b>阈值以下不出现 {@code find_tools}</b>：工具本来就少的时候多一个元工具只会让模型多绕一步。
 * 现有 4 个工具的场景行为因此完全不变。
 */
@Component
public class AgentToolBridge {

    /** 内部发现元工具的名字。它不是平台能力，所以**不进目录**。 */
    static final String FIND_TOOLS = "find_tools";

    private static final int DEFAULT_EAGER_LIMIT = 12;
    private static final int FIND_LIMIT = 8;

    private final ToolCatalog catalog;
    private final int eagerLimit;

    @org.springframework.beans.factory.annotation.Autowired
    AgentToolBridge(ToolCatalog catalog,
                    @Value("${paideia.ai.tools.eager-limit:" + DEFAULT_EAGER_LIMIT + "}") int eagerLimit) {
        this.catalog = catalog;
        this.eagerLimit = Math.max(1, eagerLimit);
    }

    /** 测试与装配用：不依赖 Spring 的构造。 */
    public AgentToolBridge(ToolCatalog catalog) {
        this(catalog, DEFAULT_EAGER_LIMIT);
    }

    /**
     * 这个 harness 能直接看到的那批工具。
     *
     * <p>harness → 工具组的映射是**配置化**的（WORK-010 的 switch 在这里收口）：
     * 加场景只要改这张表，不必再复制一遍 schema。
     */
    public List<ToolSchema> core(Harness harness) {
        Set<String> toolsets = toolsetsOf(harness);
        // **空集合在这里必须短路**：ToolQuery 里"空工具组"表示"不按组过滤"（/mcp/x/all 用的就是
        // 这个语义），直接传下去会让点评官拿到全部只读工具。两者语义相反，所以由桥接层先挡掉。
        if (toolsets.isEmpty()) {
            return List.of();
        }
        return toSchemas(catalog.view(ToolQuery.internal(toolsets, "USER")));
    }

    /**
     * 一个 harness 用哪些工具组。
     *
     * <p>`REVIEW` 是空的：点评要结构化评分，而 DeepSeek 上工具调用与结构化输出不能同一次调用并用。
     * 这是**能力约束**，不是"点评不需要数据"。
     *
     * <p>注意：组里包含写工具并不等于内部能写——{@link ToolQuery#internal} 会统一把写工具过滤掉。
     */
    static Set<String> toolsetsOf(Harness harness) {
        return switch (harness) {
            // sql 组（WORK-035）：只有 sql_describe_dataset 一个只读工具，让助手能看数据集结构。
            // 它**不给执行能力**——跑一次 SQL 要 3 秒与一次配额，写操作还会真的落数据。
            case ASSISTANT -> Set.of("knowledge", "note", "account", "assessment", "assistant", "sql");
            // 面试官与通用助手同样的读能力 + 草稿；它不该替用户写笔记，写工具本来也被只读过滤挡住。
            // **面试官不给 sql 组**：面试官不需要看你的 SQL 练习库。
            case INTERVIEW -> Set.of("knowledge", "assessment", "assistant");
            case REVIEW -> Set.of();
            // 导师要能查到本人学情（learning 组），也要按需检索知识点与读条目
            case TUTOR -> Set.of("knowledge", "learning", "assessment", "assistant", "sql");
            // 规划场景只多一个 planner 组（生成类工具，仍然只读：产出草稿、不落库）。
            // 学习记录快照由页面经 page-context 送进来，不靠工具去查。
            case PLANNER -> Set.of("knowledge", "learning", "assessment", "assistant", "planner");
        };
    }

    /** 要不要启用按需发现。 */
    public boolean discoveryEnabled() {
        return catalog.size() > eagerLimit;
    }

    /**
     * 这个 harness **有可能**看到的全部工具（不只是这一轮可见的）。
     *
     * <p>WORK-031 的 `ReActAgent` 在请求开始时锁定可执行工具，运行中再注册的调不动（实测）。
     * 所以执行侧必须**一次注册齐**，可见性由 Middleware 按轮收窄。这一条就是"一次注册齐"的来源。
     */
    public List<ToolSchema> callable(Harness harness) {
        Set<String> toolsets = toolsetsOf(harness);
        if (toolsets.isEmpty()) {
            return List.of();
        }
        return toSchemas(catalog.view(ToolQuery.internal(toolsets, "USER")));
    }

    /** `find_tools` 的 schema；未启用时返回空。 */
    public List<ToolSchema> discoverySchemas() {
        if (!discoveryEnabled()) {
            return List.of();
        }
        return List.of(ToolSchema.builder()
                .name(FIND_TOOLS)
                .description("当现有工具不够用时，按关键词搜索平台还提供哪些工具。"
                        + "返回匹配工具的名称、用途与完整参数定义；**命中后你在同一轮对话里就能调用它们**。"
                        + "不知道用哪个工具、或者用户问到的东西看起来没有对应工具时，先用它搜一下。")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "用自然语言描述你要做什么"),
                                "limit", Map.of("type", "integer", "description", "最多返回几个，默认 5，上限 8")),
                        "required", List.of("query")))
                .build());
    }

    /**
     * 收录某个工具后的完整清单。
     *
     * <p>**每轮重算**：模型在上一轮通过 `find_tools` 拿到的新工具，下一轮必须出现在清单里，
     * 否则它会一直重复搜索。
     *
     * <p><b>必须按名字去重</b>：`find_tools` 的检索结果里经常包含**本来就在核心包里**的工具
     * （比如 `knowledge_search`）。去重前同一个工具会以两份完全相同的 schema 发给上游，
     * 实测的后果是模型开始反复调同一个工具、把同一段回答重复好几遍，直到耗尽最大轮次。
     * 这不是"多余的几个字节"，是会改变模型行为的输入。
     */
    public List<ToolSchema> concat(List<ToolSchema> core, Collection<String> discovered) {
        Set<String> present = new java.util.HashSet<>();
        List<ToolSchema> merged = new ArrayList<>();
        for (ToolSchema schema : core) {
            if (present.add(schema.getName())) {
                merged.add(schema);
            }
        }
        for (String name : discovered) {
            if (!present.add(name)) {
                continue;
            }
            catalog.find(name).ifPresent(tool -> merged.add(toSchema(tool.descriptor())));
        }
        return List.copyOf(merged);
    }

    /**
     * 执行一次工具调用。
     *
     * <p>`find_tools` 在这里就地处理：它只查本地目录、不调模型、不花钱。命中结果通过
     * {@link Outcome#discovered()} 交回，由调用方决定下一轮怎么合并。
     */
    public Outcome run(long userId, Harness harness, String name, Map<String, Object> arguments) {
        if (FIND_TOOLS.equals(name)) {
            return discover(harness, arguments);
        }
        // 视图校验：模型只能调用"这个 harness 看得见"的工具。
        // 只按名字找会绕过 core() 的只读过滤——模型拼出一个名字（或从 discover 结果里看到）
        // 就能执行 note_create / assessment_mark_entry 这类写工具，甚至 assistant_ask 触发
        // 嵌套问答再扣一次费。这与 McpToolInvoker.doInvoke 的口径一致。
        Set<String> toolsets = toolsetsOf(harness);
        boolean visible = !toolsets.isEmpty()
                && catalog.view(ToolQuery.internal(toolsets, "USER")).stream()
                        .anyMatch(candidate -> candidate.name().equals(name));
        if (!visible) {
            return Outcome.failed("没有名为「" + name + "」的工具");
        }
        ToolDescriptor descriptor = catalog.find(name).map(PaideiaTool::descriptor).orElse(null);
        if (descriptor == null) {
            return Outcome.failed("没有名为「" + name + "」的工具");
        }
        try {
            ToolResult result = catalog.find(name).orElseThrow()
                    .call(ToolContext.internal(userId, "USER"), arguments);
            // 草稿走结构化的那一路：界面据此显示"要不要存成笔记"，而不用把草稿混进正文
            NoteDraft draft = draftOf(descriptor, result);
            return new Outcome(result.error(), result.text(), draft, List.of());
        } catch (RuntimeException failure) {
            // 工具失败回到模型，不往上抛：一次坏查询不该让整轮对话失败
            return Outcome.failed("工具执行失败：" + messageOf(failure));
        }
    }

    private Outcome discover(Harness harness, Map<String, Object> arguments) {
        String query = stringOf(arguments, "query");
        if (query == null) {
            return Outcome.failed("find_tools 需要 query");
        }
        // 只搜这个 harness 本来就可能用的组，且只保留它在当前视图里**真的能调用**的那些：
        // 按 toolset 过滤会把写工具列进来（search 不看 readOnly），模型照着清单调却调不动，
        // 徒增一轮无效往返。
        Set<String> allowed = toolsetsOf(harness);
        Set<String> callable = allowed.isEmpty() ? Set.of()
                : catalog.view(ToolQuery.internal(allowed, "USER")).stream()
                        .map(ToolDescriptor::name).collect(java.util.stream.Collectors.toSet());
        List<ToolDescriptor> hits = catalog.search(query, FIND_LIMIT).stream()
                .filter(descriptor -> allowed.contains(descriptor.toolset()))
                .filter(descriptor -> callable.contains(descriptor.name()))
                .toList();
        if (hits.isEmpty()) {
            return Outcome.text("没有匹配的工具。可以直接告诉用户平台目前没有这项能力。");
        }
        StringBuilder text = new StringBuilder("找到这些工具（下一轮即可调用）：\n");
        List<String> names = new ArrayList<>();
        for (ToolDescriptor descriptor : hits) {
            names.add(descriptor.name());
            text.append("- ").append(descriptor.name()).append("：").append(descriptor.title())
                    .append("。").append(descriptor.description()).append('\n');
            text.append("  参数：").append(descriptor.inputSchema()).append('\n');
        }
        return new Outcome(false, text.toString(), null, List.copyOf(names));
    }

    /** 草稿工具的结构化输出 → 界面草稿。名字写死在这里是刻意的：这是内部适配器的协议。 */
    private static NoteDraft draftOf(ToolDescriptor descriptor, ToolResult result) {
        if (result.error() || !"assistant_draft_note".equals(descriptor.name()) || result.structured() == null) {
            return null;
        }
        Object title = result.structured().get("title");
        Object body = result.structured().get("body");
        if (body == null) {
            return null;
        }
        return new NoteDraft(title == null ? null : title.toString(), body.toString());
    }

    private static List<ToolSchema> toSchemas(List<ToolDescriptor> descriptors) {
        return descriptors.stream().map(AgentToolBridge::toSchema).toList();
    }

    private static ToolSchema toSchema(ToolDescriptor descriptor) {
        var builder = ToolSchema.builder()
                .name(descriptor.name())
                .description(descriptor.description())
                .parameters(descriptor.inputSchema());
        if (descriptor.outputSchema() != null) {
            builder.outputSchema(descriptor.outputSchema());
        }
        return builder.build();
    }

    private static String stringOf(Map<String, Object> arguments, String key) {
        Object raw = arguments == null ? null : arguments.get(key);
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static String messageOf(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    /**
     * 一次内部工具调用的结果。
     *
     * @param discovered `find_tools` 命中的工具名；其它工具为空。调用方据此更新下一轮的清单
     */
    public record Outcome(boolean error, String text, NoteDraft draft, List<String> discovered) {

        static Outcome text(String text) {
            return new Outcome(false, text, null, List.of());
        }

        static Outcome failed(String text) {
            return new Outcome(true, text, null, List.of());
        }
    }
}
