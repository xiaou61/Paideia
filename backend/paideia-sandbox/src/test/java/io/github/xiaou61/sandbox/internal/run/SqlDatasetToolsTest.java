package io.github.xiaou61.sandbox.internal.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.sandbox.DatasetStorage;
import io.github.xiaou61.sandbox.internal.dataset.SqlDatasetMapper;
import io.github.xiaou61.sandbox.internal.dataset.SqlDatasetTools;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 数据集结构工具（WORK-035 T-16 / AC-011）。
 *
 * <p>**这组用例守的是"工具不给模型越权的能力"**：
 * <ol>
 *   <li>它必须只读（`readOnly = true`）——目录据此把它放进只读视图；</li>
 *   <li>它**不返回数据行**——数据可能含用户私有内容，而写 SQL 只需要结构；</li>
 *   <li>它自己再判一次权限——不能假设模型只会传有权的 key。</li>
 * </ol>
 */
class SqlDatasetToolsTest {

    private static final long OWNER = 7L;
    private static final long STRANGER = 8L;

    private static SqlDatasets datasets() {
        SqlDatasetMapper mapper = (SqlDatasetMapper) java.lang.reflect.Proxy.newProxyInstance(
                SqlDatasetMapper.class.getClassLoader(),
                new Class<?>[] {SqlDatasetMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByKey" -> "their-private".equals(args[0])
                            ? Optional.of(new SqlDatasetMapper.Row(1L, "their-private", "别人的库",
                                    "private", STRANGER, "storage-1",
                                    "{\"tables\":[{\"name\":\"secret\",\"columns\":[]}],\"relations\":[]}",
                                    1, 1, 100, "draft", java.time.Instant.EPOCH, java.time.Instant.EPOCH))
                            : Optional.empty();
                    case "listVisible" -> List.of();
                    case "toString" -> "FakeMapper";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        DatasetStorage storage = new DatasetStorage() {
            @Override
            public Optional<byte[]> read(String storageId) {
                return Optional.of("CREATE TABLE secret (id INT);".getBytes());
            }

            @Override
            public String put(byte[] content, String contentType) {
                throw new UnsupportedOperationException();
            }
        };
        return new SqlDatasets(mapper, storage);
    }

    /** 内部助手的上下文：READ 作用域、进程内来源。 */
    private static ToolContext user(long userId) {
        return new ToolContext(userId, "USER",
                java.util.Set.of(io.github.xiaou61.platform.ToolScope.READ),
                ToolContext.Origin.INTERNAL);
    }

    @Test
    void 描述内置数据集返回结构() {
        SqlDatasetTools.DescribeDataset tool = new SqlDatasetTools.DescribeDataset(datasets());
        ToolResult result = tool.call(user(OWNER), Map.of("key", "orders-public"));

        assertThat(result.error()).isFalse();
        assertThat(result.text()).contains("customers").contains("orders").contains("order_items");
    }

    /** 工具必须只读：目录据此把它放进只读视图，`ToolQuery.internal` 也据此放行。 */
    @Test
    void 工具标记为只读() {
        SqlDatasetTools.DescribeDataset tool = new SqlDatasetTools.DescribeDataset(datasets());
        assertThat(tool.descriptor().readOnly()).isTrue();
        assertThat(tool.descriptor().toolset()).isEqualTo("sql");
        // 名字必须以 toolset 前缀开头，否则 ToolCatalog 启动校验会拒绝
        assertThat(tool.descriptor().name()).startsWith("sql_");
    }

    /**
     * **AC-011 的一部分：不返回数据行**。
     *
     * <p>断言方式：结构里只有表名与列，没有 `SELECT *` 的结果。这条看起来"显然"，
     * 但实现时最省事的写法恰恰是"跑一次 SELECT * 把结果贴给模型"——那会泄露用户数据。
     */
    @Test
    void 不返回数据行() {
        SqlDatasetTools.DescribeDataset tool = new SqlDatasetTools.DescribeDataset(datasets());
        ToolResult result = tool.call(user(OWNER), Map.of("key", "orders-public"));

        assertThat(result.text()).contains("tables");
        // 结构 JSON 里只有 name/type/nullable/primaryKey，没有具体数据值
        assertThat(result.text()).doesNotContain("'ann'").doesNotContain("'bob'");
    }

    /** 别人的私人数据集：工具内部自己判权限，不信模型传来的 key。 */
    @Test
    void 无权访问的数据集返回未找到() {
        SqlDatasetTools.DescribeDataset tool = new SqlDatasetTools.DescribeDataset(datasets());
        ToolResult result = tool.call(user(OWNER), Map.of("key", "their-private"));

        assertThat(result.error()).isFalse();
        assertThat(result.text()).contains("没有找到");
        // 不能泄露"这个 key 存在"
        assertThat(result.text()).doesNotContain("secret");
    }

    @Test
    void 缺key时返回失败() {
        SqlDatasetTools.DescribeDataset tool = new SqlDatasetTools.DescribeDataset(datasets());
        assertThat(tool.call(user(OWNER), Map.of()).error()).isTrue();
        assertThat(tool.call(user(OWNER), Map.of("key", "  ")).error()).isTrue();
    }

    /** 改现有数据集要先看到原 SQL（WORK-060）。 */
    @Test
    void 读取公开数据集的完整SQL() {
        SqlDatasetTools.ReadDatasetSql tool = new SqlDatasetTools.ReadDatasetSql(datasets());
        ToolResult result = tool.call(user(OWNER), Map.of("key", "orders-public"));

        assertThat(result.error()).isFalse();
        assertThat(result.text()).contains("CREATE TABLE customers").contains("INSERT INTO customers");
    }

    /** 隐藏集不能靠读 SQL 绕过——否则模型猜到 key 就能把隐藏集整份拿走。 */
    @Test
    void 隐藏集不返回SQL() {
        SqlDatasetTools.ReadDatasetSql tool = new SqlDatasetTools.ReadDatasetSql(datasets());
        ToolResult result = tool.call(user(OWNER), Map.of("key", "orders-hidden-a"));

        assertThat(result.error()).isFalse();
        assertThat(result.text()).contains("没有找到");
        assertThat(result.text()).doesNotContain("orders-hidden-a.sql");
    }

    @Test
    void 别人的私人集不返回SQL() {
        SqlDatasetTools.ReadDatasetSql tool = new SqlDatasetTools.ReadDatasetSql(datasets());
        ToolResult result = tool.call(user(OWNER), Map.of("key", "their-private"));

        assertThat(result.error()).isFalse();
        assertThat(result.text()).contains("没有找到");
        assertThat(result.text()).doesNotContain("secret");
    }

    @Test
    void 读SQL工具也是只读() {
        SqlDatasetTools.ReadDatasetSql tool = new SqlDatasetTools.ReadDatasetSql(datasets());

        assertThat(tool.descriptor().readOnly()).isTrue();
        assertThat(tool.descriptor().toolset()).isEqualTo("sql");
        assertThat(tool.descriptor().name()).startsWith("sql_");
    }
}
