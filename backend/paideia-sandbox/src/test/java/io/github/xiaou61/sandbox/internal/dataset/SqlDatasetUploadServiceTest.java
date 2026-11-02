package io.github.xiaou61.sandbox.internal.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.sandbox.DatasetStorage;
import io.github.xiaou61.sandbox.internal.audit.SandboxQuotaService;
import io.github.xiaou61.sandbox.internal.run.CodeRun;
import io.github.xiaou61.sandbox.internal.run.CodeRunner;
import io.github.xiaou61.sandbox.internal.run.RunSlot;
import io.github.xiaou61.sandbox.internal.run.SandboxProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 上传可见性参数与写入形状（WORK-060）：缺省必须是私有。 */
class SqlDatasetUploadServiceTest {

    @Test
    void 缺省与私有都按私有处理() {
        assertThat(SqlDatasetUploadService.parseVisibility(null)).isFalse();
        assertThat(SqlDatasetUploadService.parseVisibility("")).isFalse();
        assertThat(SqlDatasetUploadService.parseVisibility("  ")).isFalse();
        assertThat(SqlDatasetUploadService.parseVisibility("private")).isFalse();
    }

    @Test
    void public按公开处理() {
        assertThat(SqlDatasetUploadService.parseVisibility("public")).isTrue();
    }

    @Test
    void 其它值直接拒绝() {
        assertThatThrownBy(() -> SqlDatasetUploadService.parseVisibility("PUBLIC"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("visibility 只能是 private 或 public");
    }

    /** `public` 写 community/published；`private`/缺省写 private/draft——一次 insert 决定，不改动任何既有行。 */
    @Test
    void 公开写community_published_私有写private_draft_且不改动既有数据集() {
        SqlDatasetMapper mapper = mock(SqlDatasetMapper.class);
        DatasetStorage storage = mock(DatasetStorage.class);
        when(storage.put(any(), anyString())).thenReturn("storage-new");
        CodeRunner runner = mock(CodeRunner.class);
        when(runner.ready()).thenReturn(true);
        when(runner.run(any())).thenReturn(new CodeRun.Result("", "", 0, 5, false, false, false,
                List.of(new CodeRun.Table(0, List.of("tbl", "col", "typ", "nullable", "colkey"),
                        List.of(List.of("t", "id", "INT", "NO", "PRI"))))));
        SandboxProperties properties = new SandboxProperties();
        properties.setMaxConcurrency(1);
        SqlDatasetUploadService service = new SqlDatasetUploadService(mapper, storage, runner,
                mock(SandboxQuotaService.class), new RunSlot(properties));

        String publicKey = service.upload(7L, "公开库", "CREATE TABLE t (id INT);", "public");
        String privateKey = service.upload(7L, "私有库", "CREATE TABLE t (id INT);", null);

        assertThat(publicKey).isNotEqualTo(privateKey);
        assertThat(UUID.fromString(publicKey)).isNotNull();
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(mapper).insert(key.capture(), eq("公开库"), eq("community"), eq(7L), eq("storage-new"),
                any(), eq(1), eq(0), anyInt(), eq("published"), any());
        assertThat(key.getValue()).isEqualTo(publicKey);
        verify(mapper).insert(anyString(), eq("私有库"), eq("private"), eq(7L), eq("storage-new"),
                any(), eq(1), eq(0), anyInt(), eq("draft"), any());
        // 保存永远是新增一行（新 UUID），不更新既有行——「改现有另存为新数据集」的底层保证
        verify(mapper, never()).updateSnapshot(anyString(), any(), anyInt(), anyInt(), anyInt(), any());
        verify(mapper, never()).promoteToCommunity(anyString(), any());
    }
}