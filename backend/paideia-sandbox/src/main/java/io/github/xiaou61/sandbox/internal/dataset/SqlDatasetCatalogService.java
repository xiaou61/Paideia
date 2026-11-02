package io.github.xiaou61.sandbox.internal.dataset;

import io.github.xiaou61.sandbox.DatasetOwnerNames;
import io.github.xiaou61.sandbox.internal.run.SqlDatasets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 数据集目录（WORK-060）。
 *
 * <p>**只读**：把「内置公开集 + 我的私人集 + 已发布社区集」拼成一张给学习者看的清单。
 * 可见集合直接复用 `mapper.listVisible` / `datasets.publicKeys`，不在这里另写一套
 * 「什么算公开」的判定——两套口径迟早会漂移。
 *
 * <p>上传者名字走 `DatasetOwnerNames` 端口批量解析：一次查询，不在循环里逐条查账号。
 */
@Service
public class SqlDatasetCatalogService {

    private final SqlDatasets datasets;
    private final SqlDatasetMapper mapper;
    private final DatasetOwnerNames owners;

    SqlDatasetCatalogService(SqlDatasets datasets, SqlDatasetMapper mapper, DatasetOwnerNames owners) {
        this.datasets = datasets;
        this.mapper = mapper;
        this.owners = owners;
    }

    public List<CatalogItem> catalog(long userId) {
        List<CatalogItem> items = new ArrayList<>();

        // 1. 内置：永远公开，不落表；表数从构建期快照数，行数不展示
        for (SqlDatasets.DatasetOption option : datasets.options(userId)) {
            if (!"builtin".equals(option.source())) {
                continue;
            }
            String schema = datasets.schema(userId, option.key());
            int tables = SqlDatasetSchemaReader.count(schema).tables();
            items.add(new CatalogItem(option.key(), option.name(), "public", "builtin",
                    null, "平台内置", tables, 0, false));
        }

        // 2. 私人/社区：listVisible 已经排除了别人的私人集与未发布/已下架的社区集
        List<SqlDatasetMapper.Summary> rows = mapper.listVisible(userId);
        Set<Long> ownerIds = new LinkedHashSet<>();
        for (SqlDatasetMapper.Summary row : rows) {
            if (row.ownerUserId() != null) {
                ownerIds.add(row.ownerUserId());
            }
        }
        Map<Long, String> names = ownerIds.isEmpty() ? Map.of() : owners.usernames(ownerIds);
        for (SqlDatasetMapper.Summary row : rows) {
            boolean mine = row.ownerUserId() != null && row.ownerUserId() == userId;
            boolean isPrivate = "private".equals(row.source());
            items.add(new CatalogItem(
                    row.datasetKey(), row.name(),
                    isPrivate ? "private" : "public", row.source(),
                    row.ownerUserId(), ownerName(row.ownerUserId(), names),
                    row.tableCount(), row.rowCount(), mine));
        }
        return List.copyOf(items);
    }

    /** 注销作者 → 已注销用户；解析不到 → 用户 id（只在这种情况下回退到数字）。 */
    public static String ownerName(Long ownerUserId, Map<Long, String> names) {
        if (ownerUserId == null) {
            return "已注销用户";
        }
        String name = names.get(ownerUserId);
        return name == null || name.isBlank() ? "用户 " + ownerUserId : name;
    }

    /** 目录的一行。**不含 schema、SQL、数据行**。 */
    public record CatalogItem(String key, String name, String visibility, String source,
                              Long ownerUserId, String ownerName,
                              int tableCount, int rowCount, boolean mine) {
    }
}
