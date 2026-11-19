package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** WORK-086 知识召回：只认已确认复盘、没关键词不查库、查库失败当没有。 */
class PostmortemKnowledgeTest {

    private PostmortemMapper postmortems;
    private PostmortemKnowledgeRecall knowledge;

    private static final Instant T0 = Instant.parse("2026-11-15T02:00:00Z");

    @BeforeEach
    void setUp() {
        postmortems = mock(PostmortemMapper.class);
        knowledge = new PostmortemKnowledgeRecall(postmortems);
    }

    @Test
    @DisplayName("召回已确认复盘，根因/处置压成单行并截断")
    void recall_returnsHitsWithFlippedFields() {
        PostmortemRow row = approved(12L, "CPU 相对 7 天基线异常");
        row.setRootCause("另一会话在反复部署，\nsystemctl restart 打断请求。");
        row.setActionsMd("1. 停旧起新\n2. 连接池提到 8");
        when(postmortems.searchApproved(eq("CPU 相对 7 天基线异常"), eq("error reset by peer"), anyInt()))
                .thenReturn(List.of(row));

        List<PostmortemKnowledge.Hit> hits = knowledge.recall(
                "CPU 相对 7 天基线异常", "error reset by peer", 2);

        assertThat(hits).hasSize(1);
        PostmortemKnowledge.Hit hit = hits.get(0);
        assertThat(hit.id()).isEqualTo(12L);
        assertThat(hit.title()).isEqualTo("CPU 相对 7 天基线异常");
        assertThat(hit.approvedAt()).isEqualTo(T0);
        assertThat(hit.rootCause()).doesNotContain("\n").contains("systemctl restart");
        assertThat(hit.actions()).contains("连接池提到 8");
    }

    @Test
    @DisplayName("没有关键词时直接返回空，不查库")
    void recall_emptySignaturesSkipsDatabase() {
        assertThat(knowledge.recall("某告警", "  \n ", 2)).isEmpty();
        assertThat(knowledge.recall(null, null, 2)).isEmpty();
        verifyNoInteractions(postmortems);
    }

    @Test
    @DisplayName("检索异常时返回空表并继续，不把知识库故障变成诊断故障")
    void recall_swallowsDatabaseFailure() {
        when(postmortems.searchApproved(anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("table is gone"));

        assertThat(knowledge.recall("某告警", "error timeout", 2)).isEmpty();
        verify(postmortems).searchApproved(eq("某告警"), eq("error timeout"), eq(2));
    }

    @Test
    @DisplayName("调用方给 0 或超限的 limit 时收敛到合法范围")
    void recall_clampsLimit() {
        when(postmortems.searchApproved(anyString(), anyString(), anyInt())).thenReturn(List.of());

        knowledge.recall("a", "b", 0);
        verify(postmortems).searchApproved(eq("a"), eq("b"), eq(PostmortemKnowledgeRecall.DEFAULT_LIMIT));

        knowledge.recall("a", "b", 99);
        verify(postmortems).searchApproved(eq("a"), eq("b"), eq(5));
    }

    private static PostmortemRow approved(long id, String title) {
        PostmortemRow row = new PostmortemRow();
        row.setId(id);
        row.setTitle(title);
        row.setStatus(PostmortemRow.STATUS_APPROVED);
        row.setApprovedAt(T0);
        row.setRootCause("根因");
        row.setActionsMd("处置");
        return row;
    }
}
