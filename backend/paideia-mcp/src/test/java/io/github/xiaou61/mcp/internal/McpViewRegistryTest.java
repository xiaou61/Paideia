package io.github.xiaou61.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolCatalog;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 管理视图的隔离（WORK-038）。
 *
 * <p>三条都是"静默放行"式的失败：清单按角色串用、非管理员看见管理工具、默认视图被管理工具污染。
 * 不测的话只会表现为"普通用户能删内容"。
 */
class McpViewRegistryTest {

    private static PaideiaTool tool(String name, String toolset, ToolScope scope, boolean readOnly) {
        return new PaideiaTool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor(name, toolset, name, "描述 " + name,
                        Map.of("type", "object", "properties", Map.of()), null,
                        readOnly, false, true, scope, false);
            }

            @Override
            public ToolResult call(ToolContext context, Map<String, Object> arguments) {
                return ToolResult.ok("ok");
            }
        };
    }

    private static ToolCatalog catalog() {
        return new ToolCatalog(List.of(
                tool("knowledge_search", "knowledge", ToolScope.READ, true),
                tool("admin_users_list", "admin_users", ToolScope.ADMIN, true),
                tool("admin_users_set_role", "admin_users", ToolScope.ADMIN, false)));
    }

    private static List<String> names(ToolCatalog catalog, McpViews view, String role) {
        Set<ToolScope> scopes = Set.of(ToolScope.READ, ToolScope.WRITE, ToolScope.ADMIN);
        return catalog.view(view.queryFor(scopes, Set.of(), role)).stream()
                .map(ToolDescriptor::name).toList();
    }

    @Test
    void cacheKeySeparatesAdminFromLearner() {
        McpViews view = McpViews.parse("/x/admin_users").orElseThrow();

        assertThat(McpViewRegistry.cacheKey(view, Set.of(), "admin"))
                .isNotEqualTo(McpViewRegistry.cacheKey(view, Set.of(), "learner"))
                .isEqualTo(McpViewRegistry.cacheKey(view, Set.of(), "ADMIN"));
        // 空白名单与显式空集合必须落到同一个键，否则同一份清单会建两台 server
        assertThat(McpViewRegistry.cacheKey(view, null, "admin"))
                .isEqualTo(McpViewRegistry.cacheKey(view, Set.of(), "admin"));
    }

    @Test
    void learnerNeverSeesAdminToolsEvenWithAdminScope() {
        // 过期未清理的 admin scope + 已降权账号：角色是最后一道，必须挡住
        assertThat(names(catalog(), McpViews.parse("/x/admin_users").orElseThrow(), "learner"))
                .isEmpty();
        assertThat(names(catalog(), McpViews.parse("/x/admin_users/write").orElseThrow(), "learner"))
                .isEmpty();
    }

    @Test
    void adminViewListsOnlyItsOwnGroupAndHonoursWritePath() {
        assertThat(names(catalog(), McpViews.parse("/x/admin_users").orElseThrow(), "admin"))
                .containsExactly("admin_users_list");
        assertThat(names(catalog(), McpViews.parse("/x/admin_users/write").orElseThrow(), "admin"))
                .containsExactlyInAnyOrder("admin_users_list", "admin_users_set_role");
    }

    @Test
    void defaultViewForAdminAddsAdminReadToolsButStillNoWrites() {
        // 默认视图 = 全部只读工具：管理员凭 admin scope 能看到管理读工具，但管理写工具仍不可见
        assertThat(names(catalog(), McpViews.DEFAULT, "admin"))
                .containsExactlyInAnyOrder("knowledge_search", "admin_users_list");
        // 学习者仍然只有学习者工具
        assertThat(names(catalog(), McpViews.DEFAULT, "learner"))
                .containsExactly("knowledge_search");
    }

    @Test
    void unknownAdminGroupIsNotFoundBeforeBuilding() {
        McpViewRegistry registry = new McpViewRegistry(catalog(),
                new McpToolInvoker(catalog(), new McpRateLimiter(60)));

        assertThatThrownBy(() -> registry.view("/x/admin_nope", Set.of(), "admin"))
                .isInstanceOf(McpViewRegistry.ViewNotFoundException.class);
    }
}
