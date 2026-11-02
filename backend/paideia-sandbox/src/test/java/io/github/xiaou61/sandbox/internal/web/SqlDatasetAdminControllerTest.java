package io.github.xiaou61.sandbox.internal.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.sandbox.internal.dataset.SqlDatasetMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 管理端下架的边界（WORK-035 T-11 / AC-008）。
 *
 * <p>**只能下架社区数据集**：私人集不经过审核流程，管理员下架它等于替作者删掉了
 * 自己的东西——而作者根本不知道发生了什么。
 */
class SqlDatasetAdminControllerTest {

    private static SqlDatasetMapper mapperWith(String key, String source) {
        return (SqlDatasetMapper) java.lang.reflect.Proxy.newProxyInstance(
                SqlDatasetMapper.class.getClassLoader(),
                new Class<?>[] {SqlDatasetMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByKey" -> key.equals(args[0])
                            ? Optional.of(new SqlDatasetMapper.Row(1L, key, key, source, 7L,
                                    "storage-1", null, 1, 1, 100, "published",
                                    Instant.EPOCH, Instant.EPOCH))
                            : Optional.empty();
                    case "updateStatus" -> 1;
                    case "markReportsHandled" -> 0;
                    case "toString" -> "FakeMapper";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static SqlDatasetAdminController controller(String key, String source) {
        return new SqlDatasetAdminController(mapperWith(key, source),
                io.github.xiaou61.sandbox.DatasetOwnerNames.NONE);
    }

    @Test
    void 社区数据集可以下架() {
        // 不抛就是通过：真实副作用（updateStatus）由 mapper 代理吃掉
        controller("shared", "community").takeDown("shared");
    }

    /** 私人集不能被管理员下架：它没经过审核，作者也不会知道。 */
    @Test
    void 私人数据集不能下架() {
        assertThatThrownBy(() -> controller("mine", "private").takeDown("mine"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有社区数据集");
    }

    @Test
    void 未知数据集返回未找到() {
        assertThatThrownBy(() -> controller("x", "community").takeDown("nope"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有这个数据集");
    }

    /** 恢复上架同样只对社区集开放。 */
    @Test
    void 恢复也限社区集() {
        assertThatThrownBy(() -> controller("mine", "private").restore("mine"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有社区数据集");
    }
}
