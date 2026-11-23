package io.github.xiaou61.ai.internal.essay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.knowledge.KnowledgeApi;
import io.github.xiaou61.platform.BizException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 复核队列的采纳/驳回（WORK-084 TASK-006）。 */
class EssayReviewServiceTest {

    private EssayVerificationMapper mapper;
    private KnowledgeApi knowledge;
    private EssayReviewService service;

    @BeforeEach
    void setUp() {
        mapper = mock(EssayVerificationMapper.class);
        knowledge = mock(KnowledgeApi.class);
        // 采纳前会核对分类还在不在（分类被删后再采纳会撞外键，整行永远无法处理）。
        // 夹具里的记录都挂 categoryId=1，所以这里给一个 id=1 的分类。
        when(knowledge.listCategories()).thenReturn(List.of(
                new KnowledgeApi.CategoryNode(1L, "面经", "interview", null)));
        // markReviewed 现在带状态守卫并返回影响行数：默认"改到了"（=1），
        // 只有并发冲突那条用例显式让它返回 0。
        when(mapper.markReviewed(anyLong(), anyString(), any(), anyLong(), any())).thenReturn(1);
        service = new EssayReviewService(mapper, knowledge);
    }

    @Test
    void approvePendingCreatesEntryAndMarksApproved() {
        when(mapper.findById(9L)).thenReturn(Optional.of(row(9L, "pending_review")));
        when(knowledge.createCollected(any()))
                .thenReturn(new KnowledgeApi.CreateOutcome(500L, true));

        long entryId = service.approve(9L, null, null, true, 42L);

        assertThat(entryId).isEqualTo(500L);
        ArgumentCaptor<KnowledgeApi.CollectedEntry> captor =
                ArgumentCaptor.forClass(KnowledgeApi.CollectedEntry.class);
        verify(knowledge).createCollected(captor.capture());
        KnowledgeApi.CollectedEntry input = captor.getValue();
        assertThat(input.title()).isEqualTo("题");
        assertThat(input.status()).isEqualTo("published");
        assertThat(input.categoryId()).isEqualTo(1L);
        assertThat(input.body()).contains("## 题").contains("答案").contains("---");
        verify(mapper).markReviewed(eq(9L), eq("approved"), eq(500L), eq(42L), isNull());
    }

    @Test
    void approvePrefersFinalAnswerAndDraftPublishFlag() {
        EssayVerificationRow row = row(9L, "pending_review");
        row.setFinalAnswer("修订后的答案");
        when(mapper.findById(9L)).thenReturn(Optional.of(row));
        when(knowledge.createCollected(any())).thenReturn(new KnowledgeApi.CreateOutcome(501L, true));

        service.approve(9L, null, null, false, 42L);

        ArgumentCaptor<KnowledgeApi.CollectedEntry> captor =
                ArgumentCaptor.forClass(KnowledgeApi.CollectedEntry.class);
        verify(knowledge).createCollected(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("draft");
        assertThat(captor.getValue().body()).contains("修订后的答案");
    }

    @Test
    void approveTwiceReturnsSameEntryId() {
        EssayVerificationRow row = row(9L, "approved");
        row.setEntryId(777L);
        when(mapper.findById(9L)).thenReturn(Optional.of(row));

        long entryId = service.approve(9L, null, null, true, 42L);

        assertThat(entryId).isEqualTo(777L);
        verify(knowledge, never()).createCollected(any());
        verify(mapper, never()).markReviewed(anyLong(), anyString(), any(), anyLong(), any());
    }

    @Test
    void approveWhenEntryExistsReusesEntryId() {
        when(mapper.findById(9L)).thenReturn(Optional.of(row(9L, "pending_review")));
        // 同指纹+标题已存在时，createCollected 返回 created=false 与原 entryId
        when(knowledge.createCollected(any())).thenReturn(new KnowledgeApi.CreateOutcome(600L, false));

        long entryId = service.approve(9L, null, null, true, 42L);

        assertThat(entryId).isEqualTo(600L);
        verify(mapper).markReviewed(eq(9L), eq("approved"), eq(600L), eq(42L), isNull());
    }

    @Test
    void approveRejectedRowIsConflict() {
        when(mapper.findById(9L)).thenReturn(Optional.of(row(9L, "rejected")));

        assertThatThrownBy(() -> service.approve(9L, null, null, true, 42L))
                .isInstanceOf(BizException.class);
        verify(knowledge, never()).createCollected(any());
    }

    @Test
    void approveWithDeletedCategoryIsRejectedBeforeCreatingEntry() {
        // 记录挂着已被删掉的分类：先建条目再撞外键，结果是 500 且这一行永远无法采纳。
        when(mapper.findById(9L)).thenReturn(Optional.of(row(9L, "pending_review")));
        when(knowledge.listCategories()).thenReturn(List.of());

        assertThatThrownBy(() -> service.approve(9L, null, null, true, 42L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("分类已不存在");
        verify(knowledge, never()).createCollected(any());
    }

    @Test
    void approveLosesRaceWhenRowWasAlreadyHandled() {
        // 状态守卫在 SQL 里：并发采纳时只有一个能改到行，另一个必须回滚掉刚建的条目。
        when(mapper.findById(9L)).thenReturn(Optional.of(row(9L, "pending_review")));
        when(knowledge.createCollected(any()))
                .thenReturn(new KnowledgeApi.CreateOutcome(500L, true));
        when(mapper.markReviewed(eq(9L), eq("approved"), eq(500L), eq(42L), isNull())).thenReturn(0);

        assertThatThrownBy(() -> service.approve(9L, null, null, true, 42L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已被其他人处理");
    }

    @Test
    void missingRowIsNotFound() {
        when(mapper.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reject(404L, "n", 42L)).isInstanceOf(BizException.class);
    }

    @Test
    void rejectDoesNotCreateEntry() {
        when(mapper.findById(9L)).thenReturn(Optional.of(row(9L, "pending_review")));

        service.reject(9L, "技术描述有误", 42L);

        verify(mapper).markReviewed(eq(9L), eq("rejected"), isNull(), eq(42L), eq("技术描述有误"));
        verify(knowledge, never()).createCollected(any());
    }

    private static EssayVerificationRow row(long id, String status) {
        EssayVerificationRow row = new EssayVerificationRow();
        row.setId(id);
        row.setStatus(status);
        row.setQuestionTitle("题");
        row.setDraftAnswer("答案");
        row.setConfidence(BigDecimal.valueOf(0.4));
        row.setCategoryId(1L);
        row.setFingerprint("fp");
        row.setSourceUrl("https://example.com/1");
        row.setSourceTitle("某面经");
        return row;
    }
}
