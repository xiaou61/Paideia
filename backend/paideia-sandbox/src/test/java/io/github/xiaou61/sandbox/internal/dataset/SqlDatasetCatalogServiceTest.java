package io.github.xiaou61.sandbox.internal.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.sandbox.DatasetOwnerNames;
import io.github.xiaou61.sandbox.DatasetStorage;
import io.github.xiaou61.sandbox.internal.run.SqlDatasets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 数据集目录（WORK-060）。
 *
 * <p>守的是「目录能看到什么」：内置公开集 + 我的私人集 + 已发布社区集；
 * 隐藏集、别人的私人集、已下架集都不能出现，上传者名字只从用户名目录来。
 */
class SqlDatasetCatalogServiceTest {

    private static final long ME = 7L;

    private static SqlDatasetCatalogService service(Map<Long, String> names) {
        SqlDatasetMapper mapper = (SqlDatasetMapper) java.lang.reflect.Proxy.newProxyInstance(
                SqlDatasetMapper.class.getClassLoader(),
                new Class<?>[] {SqlDatasetMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "listVisible" -> List.of(
                            new SqlDatasetMapper.Summary("mine-private", "我的库", "private", ME, 2, 12),
                            new SqlDatasetMapper.Summary("shared", "共享库", "community", 8L, 3, 20),
                            new SqlDatasetMapper.Summary("orphan", "孤儿库", "community", null, 1, 5));
                    case "toString" -> "FakeSqlDatasetMapper";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("测试桩没有实现 " + method.getName());
                });
        DatasetStorage storage = new DatasetStorage() {
            @Override
            public Optional<byte[]> read(String storageId) {
                return Optional.empty();
            }

            @Override
            public String put(byte[] content, String contentType) {
                throw new UnsupportedOperationException("目录不写");
            }
        };
        SqlDatasets datasets = new SqlDatasets(mapper, storage);
        return new SqlDatasetCatalogService(datasets, mapper, ids -> names);
    }

    @Test
    void 目录包含内置我的与公开且不泄露隐藏集() {
        List<SqlDatasetCatalogService.CatalogItem> items =
                service(Map.of(ME, "lzf", 8L, "alice")).catalog(ME);

        assertThat(items).extracting(SqlDatasetCatalogService.CatalogItem::key)
                .containsExactly("orders-public", "mine-private", "shared", "orphan");
        assertThat(items).extracting(SqlDatasetCatalogService.CatalogItem::key)
                .doesNotContain("orders-hidden-a");
    }

    @Test
    void 内置集永远公开且表数来自快照() {
        SqlDatasetCatalogService.CatalogItem builtin = service(Map.of()).catalog(ME).get(0);

        assertThat(builtin.source()).isEqualTo("builtin");
        assertThat(builtin.visibility()).isEqualTo("public");
        assertThat(builtin.ownerName()).isEqualTo("平台内置");
        assertThat(builtin.mine()).isFalse();
        assertThat(builtin.tableCount()).isEqualTo(3);
    }

    @Test
    void 私人集显示私有与本人() {
        SqlDatasetCatalogService.CatalogItem mine = service(Map.of(ME, "lzf", 8L, "alice"))
                .catalog(ME).get(1);

        assertThat(mine.visibility()).isEqualTo("private");
        assertThat(mine.mine()).isTrue();
        assertThat(mine.ownerName()).isEqualTo("lzf");
    }

    @Test
    void 社区集显示上传者名字_注销作者显示已注销用户() {
        List<SqlDatasetCatalogService.CatalogItem> items =
                service(Map.of(ME, "lzf", 8L, "alice")).catalog(ME);

        assertThat(items.get(2).visibility()).isEqualTo("public");
        assertThat(items.get(2).ownerName()).isEqualTo("alice");
        assertThat(items.get(3).ownerName()).isEqualTo("已注销用户");
    }

    @Test
    void 名字解析不到时回退到用户id而不是空字符串() {
        List<SqlDatasetCatalogService.CatalogItem> items = service(Map.of()).catalog(ME);

        assertThat(items.get(2).ownerName()).isEqualTo("用户 8");
    }
}
