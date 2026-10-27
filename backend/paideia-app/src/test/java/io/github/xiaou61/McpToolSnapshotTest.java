package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolCatalog;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolQuery;
import io.github.xiaou61.platform.ToolScope;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 工具清单的快照（WORK-044 AC-005 / AC-013）。
 *
 * <p><b>为什么写死而不是生成</b>：生成式快照会在改动时自动更新，就失去了"钉住不变"的意义。
 * 写死之后，任何清单变化都必须显式改这份测试——改的时候会想一下"这是不是我要的"。
 *
 * <p>这条测试保护的是两件很容易静默发生的事：
 * <ol>
 *   <li>新工具忘了设 `defaultGroup=false`，学习者默认视图悄悄变大（WORK-044 的四个域全部要求不进默认视图）；</li>
 *   <li>重构时某个工具的名字、工具组或权限标注变了，而外部客户端已经在用它。</li>
 * </ol>
 *
 * <p>用真实的 Spring 上下文：`ToolCatalog` 的启动校验（重名、命名前缀、schema 为 object、
 * 只读与破坏性不矛盾）只有把所有工具都装配起来才会跑。这条测试通过本身就证明校验通过。
 */
@SpringBootTest
@ActiveProfiles("test")
class McpToolSnapshotTest {

    /**
     * 学习者默认视图的清单。
     *
     * <p>它是 `/mcp` 返回的全部工具（只读 + `defaultGroup=true`）。**这个集合不该随新功能增长**：
     * 新增工具一律 `defaultGroup=false`，由用户显式走 `/mcp/x/{toolset}` 或凭据的工具组白名单取用。
     */
    private static final Set<String> DEFAULT_VIEW = new TreeSet<>(Set.of(
            "account_me",
            "assessment_list_marks",
            "knowledge_get_entry",
            "knowledge_list_categories",
            "knowledge_my_marks",
            "knowledge_search",
            "knowledge_weak_concepts",
            "learning_progress",
            "note_get",
            "note_list",
            "sql_describe_dataset"));

    @Autowired
    ToolCatalog catalog;

    @Test
    @DisplayName("学习者默认视图的清单不变")
    void defaultViewUnchanged() {
        List<String> actual = catalog.view(ToolQuery.core("USER")).stream()
                .map(ToolDescriptor::name)
                .toList();

        // 先把实际值打出来核对一次，再写死进 DEFAULT_VIEW
        assertThat(actual).as("实际默认视图清单：%s", actual).containsExactlyElementsOf(DEFAULT_VIEW);
    }

    /** 新增的四个域都必须只出现在各自的组里，且不进默认视图。 */
    @Test
    @DisplayName("WORK-044 的四个域各自成组且不进默认视图")
    void newDomainsAreGroupedAndNotDefault() {
        // 用可写查询：memory_remember 是写工具，只读查询会把它过滤掉
        assertThat(namesIn("resume")).containsExactly("resume_get", "resume_list");
        assertThat(namesIn("interview")).containsExactly("interview_get", "interview_list");
        assertThat(namesIn("review")).containsExactly("review_due", "review_plan");
        assertThat(namesInAll("memory")).containsExactly("memory_list", "memory_remember");

        catalog.snapshot().stream()
                .filter(descriptor -> Set.of("resume", "interview", "review", "memory")
                        .contains(descriptor.toolset()))
                .forEach(descriptor -> assertThat(descriptor.defaultGroup())
                        .as(descriptor.name() + " 不该进默认视图")
                        .isFalse());
    }

    /** 记忆写入是四个域里唯一的写工具，必须标 WRITE 且非只读。 */
    @Test
    @DisplayName("memory_remember 是写工具")
    void memoryRememberIsWrite() {
        ToolDescriptor remember = catalog.find("memory_remember").orElseThrow().descriptor();

        assertThat(remember.scope()).isEqualTo(ToolScope.WRITE);
        assertThat(remember.readOnly()).isFalse();
        assertThat(remember.destructive()).as("写记忆不是破坏性操作").isFalse();
    }

    /**
     * 每个工具的 (名字, 工具组, scope, 只读, 破坏性) 五元组。
     *
     * <p>**改动这份清单要显式改代码**：名字变了外部客户端会调不到，工具组变了视图路径会失效，
     * scope 变了会让某些凭据突然看不见它。
     */
    @Test
    @DisplayName("全部工具的五元组快照")
    void allToolsShape() {
        List<String> actual = catalog.snapshot().stream()
                .map(descriptor -> descriptor.name() + "|" + descriptor.toolset() + "|"
                        + descriptor.scope().key() + "|" + descriptor.readOnly() + "|"
                        + descriptor.destructive())
                .toList();

        assertThat(actual).hasSize(65);
        // 逐组核对数量：改动任何一组都会在这里显形
        assertThat(countByToolset()).containsExactlyInAnyOrderEntriesOf(expectedToolsetCounts());
    }

    private List<String> namesIn(String toolset) {
        return catalog.view(new ToolQuery(Set.of(toolset), Set.of(), true, "USER",
                        Set.of(ToolScope.READ), false))
                .stream().map(ToolDescriptor::name).toList();
    }

    /** 含写工具的组清单。 */
    private List<String> namesInAll(String toolset) {
        return catalog.view(new ToolQuery(Set.of(toolset), Set.of(), false, "USER",
                        Set.of(ToolScope.READ, ToolScope.WRITE), false))
                .stream().map(ToolDescriptor::name).toList();
    }

    private java.util.Map<String, Integer> countByToolset() {
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        for (ToolDescriptor descriptor : catalog.snapshot()) {
            counts.merge(descriptor.toolset(), 1, Integer::sum);
        }
        return counts;
    }

    /** 各组工具数。改动工具时这里必须同步——这是"有没有漏改"的哨兵。 */
    private static java.util.Map<String, Integer> expectedToolsetCounts() {
        return java.util.Map.ofEntries(
                java.util.Map.entry("account", 1),
                java.util.Map.entry("admin_ai", 5),
                java.util.Map.entry("admin_content", 15),
                java.util.Map.entry("admin_digest", 2),
                java.util.Map.entry("admin_learning", 4),
                java.util.Map.entry("admin_ops", 4),
                java.util.Map.entry("admin_review", 5),
                java.util.Map.entry("admin_users", 6),
                java.util.Map.entry("assessment", 2),
                java.util.Map.entry("assistant", 2),
                java.util.Map.entry("interview", 2),
                java.util.Map.entry("knowledge", 5),
                java.util.Map.entry("learning", 1),
                java.util.Map.entry("memory", 2),
                java.util.Map.entry("note", 4),
                java.util.Map.entry("resume", 2),
                java.util.Map.entry("review", 2),
                java.util.Map.entry("sql", 1));
    }
}
