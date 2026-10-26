package io.github.xiaou61.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 视图路由：路径 → 查询。这些形状会被用户手写进 MCP 客户端配置，所以要钉住。
 */
class McpViewsTest {

    @Test
    void defaultViewIsReadOnlyDefaultGroup() {
        McpViews view = McpViews.parse(null).orElseThrow();

        assertThat(view.name()).isEqualTo("default");
        assertThat(view.write()).isFalse();
        assertThat(view.defaultsOnly()).isTrue();
        assertThat(view.toolsets()).isEmpty();
    }

    @Test
    void trailingSlashIsTheSameView() {
        assertThat(McpViews.parse("/").orElseThrow().name()).isEqualTo("default");
        assertThat(McpViews.parse("").orElseThrow().name()).isEqualTo("default");
    }

    @Test
    void writeVariantOfDefaultView() {
        McpViews view = McpViews.parse("/write").orElseThrow();

        assertThat(view.name()).isEqualTo("default-write");
        assertThat(view.write()).isTrue();
        assertThat(view.defaultsOnly()).isTrue();
    }

    @Test
    void singleToolsetView() {
        McpViews read = McpViews.parse("/x/knowledge").orElseThrow();
        McpViews write = McpViews.parse("/x/knowledge/write").orElseThrow();

        assertThat(read.name()).isEqualTo("knowledge");
        assertThat(read.toolsets()).containsExactly("knowledge");
        assertThat(read.write()).isFalse();
        assertThat(write.name()).isEqualTo("knowledge-write");
        assertThat(write.write()).isTrue();
    }

    @Test
    void allViewHasNoToolsetFilter() {
        McpViews all = McpViews.parse("/x/all").orElseThrow();

        assertThat(all.name()).isEqualTo("all");
        assertThat(all.toolsets()).isEmpty();
        assertThat(all.defaultsOnly()).isFalse();
    }

    @Test
    void unknownPathsAreNotFound() {
        assertThat(McpViews.parse("/x/KNOWLEDGE")).isEmpty();
        assertThat(McpViews.parse("/x/knowledge/readonly")).isEmpty();
        assertThat(McpViews.parse("/y/knowledge")).isEmpty();
        assertThat(McpViews.parse("/x/")).isEmpty();
    }

    @Test
    void writeViewRequiresWriteScopeInQuery() {
        McpViews write = McpViews.parse("/x/note/write").orElseThrow();

        // 只读 token 在写视图上仍然拿不到写工具：查询里没有 WRITE scope
        var readOnlyQuery = write.queryFor(
                java.util.Set.of(io.github.xiaou61.platform.ToolScope.READ), "learner");
        assertThat(readOnlyQuery.readOnly()).isFalse();
        assertThat(readOnlyQuery.scopes())
                .doesNotContain(io.github.xiaou61.platform.ToolScope.WRITE);
    }

    @Test
    void optionalIsUsedForEmptyCases() {
        Optional<McpViews> missing = McpViews.parse("/nope");
        assertThat(missing).isEmpty();
    }
}
