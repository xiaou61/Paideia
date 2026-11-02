package io.github.xiaou61.sandbox.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.sandbox.internal.dataset.SqlAiService;
import io.github.xiaou61.sandbox.internal.dataset.SqlDatasetMapper;
import io.github.xiaou61.sandbox.internal.dataset.SqlDatasetUploadService;
import io.github.xiaou61.sandbox.internal.run.SqlDatasets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 数据集可见性切换（WORK-060）。
 *
 * <p>守三件事：作者能切、别人不能切、管理员下架与内置集不能被作者绕过。
 */
class SqlDatasetControllerTest {

    private static final long OWNER = 7L;

    private final SqlDatasetMapper mapper = mock(SqlDatasetMapper.class);
    private final SqlDatasets datasets = mock(SqlDatasets.class);
    private final SqlDatasetUploadService uploads = mock(SqlDatasetUploadService.class);
    private final SqlDatasetController controller = new SqlDatasetController(
            uploads, mock(SqlAiService.class), mapper, datasets,
            mock(io.github.xiaou61.sandbox.internal.dataset.SqlDatasetCatalogService.class));

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(OWNER), null, List.of()));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    private static SqlDatasetMapper.Row row(String source, String status, Long owner) {
        return new SqlDatasetMapper.Row(1L, "mine", "我的库", source, owner, "storage-1",
                null, 1, 1, 100, status, Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void 作者可以把私人集设为公开() {
        when(mapper.findByKey("mine")).thenReturn(Optional.of(row("private", "draft", OWNER)));

        controller.visibility("mine", new SqlDatasetController.VisibilityRequest("public"));

        verify(mapper).promoteToCommunity(eq("mine"), any());
    }

    @Test
    void 作者可以把公开集收回私有() {
        when(mapper.findByKey("mine")).thenReturn(Optional.of(row("community", "published", OWNER)));

        controller.visibility("mine", new SqlDatasetController.VisibilityRequest("private"));

        verify(mapper).demoteToPrivate(eq("mine"), any());
    }

    @Test
    void 非作者切换返回没有这个数据集() {
        when(mapper.findByKey("mine")).thenReturn(Optional.of(row("private", "draft", 8L)));

        assertThatThrownBy(() -> controller.visibility("mine", new SqlDatasetController.VisibilityRequest("public")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有这个数据集");
    }

    @Test
    void 内置集不能切换() {
        when(datasets.isBuiltin("orders-public")).thenReturn(true);

        assertThatThrownBy(() -> controller.visibility("orders-public",
                new SqlDatasetController.VisibilityRequest("private")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("内置数据集不能修改可见性");
    }

    /** 管理员下架优先：作者不能靠切私有把下架状态洗掉，再重新公开。 */
    @Test
    void 已下架的社区集不能切换() {
        when(mapper.findByKey("mine")).thenReturn(Optional.of(row("community", "taken_down", OWNER)));

        assertThatThrownBy(() -> controller.visibility("mine", new SqlDatasetController.VisibilityRequest("private")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已被管理员下架");
    }

    @Test
    void 非法可见性值被拒绝() {
        assertThatThrownBy(() -> controller.visibility("mine", new SqlDatasetController.VisibilityRequest("weird")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("visibility 只能是 private 或 public");
    }

    @Test
    void 上传把可见性原样交给服务() {
        when(uploads.upload(OWNER, "我的库", "CREATE TABLE t (id INT);", "public")).thenReturn("new-key");

        var response = controller.upload(new SqlDatasetController.UploadRequest(
                "我的库", "CREATE TABLE t (id INT);", "public"));

        assertThat(response.data().key()).isEqualTo("new-key");
        verify(uploads).upload(OWNER, "我的库", "CREATE TABLE t (id INT);", "public");
    }
}
