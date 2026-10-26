package io.github.xiaou61.ai.internal.camp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.RunRecorder;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.ResumeData;
import io.github.xiaou61.ai.internal.billing.CreditService;
import io.github.xiaou61.ai.internal.credential.CredentialService;
import io.github.xiaou61.ai.internal.resume.ResumeDocumentService;
import io.github.xiaou61.knowledge.KnowledgeApi;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 开营的编排与失败语义（WORK-029 TASK-008/009/010）。
 *
 * <p>模型全程打桩，**不出网**。三个必测的失败模式：
 * <ol>
 *   <li>空返回被当成"0 道题"成功 → 会建出一个没有题的营还照常扣费</li>
 *   <li>余额不足仍调用上游 → 用户被拒了却还在花钱</li>
 *   <li>修复重试没有真的重试 / 重试后又把第一轮的好题丢掉</li>
 * </ol>
 */
class CampServiceTest {

    private CampMapper camps;
    private ResumeDocumentService documents;
    private CampItemGenerator generator;
    private KnowledgeApi knowledge;
    private CredentialService credentials;
    private CreditService credits;
    private RunRecorder recorder;
    private RunRecorder.RunScope scope;
    private CampService service;

    private static final long USER = 7L;
    private static final long TEMPLATE_ID = 21L;
    private static final long PROGRAM_ID = 99L;

    @BeforeEach
    void setUp() {
        camps = mock(CampMapper.class);
        documents = mock(ResumeDocumentService.class);
        generator = mock(CampItemGenerator.class);
        knowledge = mock(KnowledgeApi.class);
        credentials = mock(CredentialService.class);
        credits = mock(CreditService.class);
        recorder = mock(RunRecorder.class);
        scope = mock(RunRecorder.RunScope.class);

        service = new CampService(camps, documents, generator, new CampItemParser(), knowledge,
                credentials, credits, recorder);

        ResumeData resume = new ResumeData(
                new ResumeData.Basics("张三", "13800001111", "z@example.com", "杭州", List.of()),
                new ResumeData.Intent("Java 后端", "杭州", ""),
                List.of(), List.of(), List.of(),
                List.of(new ResumeData.SkillGroup("技术栈", List.of("Java", "MySQL"))),
                List.of(), "");
        when(documents.get(USER, TEMPLATE_ID))
                .thenReturn(new ResumeDocumentService.Detail(TEMPLATE_ID, "主简历", true, 1, resume, null));
        when(credentials.access(anyLong()))
                .thenReturn(Optional.of(new ModelAccess("https://example.invalid", "deepseek-flash", "k")));
        when(knowledge.concepts()).thenReturn(List.of());
        when(knowledge.conceptCarriers(any())).thenReturn(Map.of());
        when(knowledge.dueReviews(anyLong(), anyInt())).thenReturn(List.of());
        when(credits.estimate()).thenReturn(100L);
        when(credits.reserve(eq(USER), anyLong())).thenReturn(100L);
        when(recorder.begin(any())).thenReturn(scope);
        when(camps.countPrograms(USER)).thenReturn(0);
        when(camps.insertProgram(any())).thenAnswer(invocation -> {
            CampMapper.ProgramRow row = invocation.getArgument(0);
            row.setId(PROGRAM_ID);
            return 1;
        });
    }

    // ── 夹具 ────────────────────────────────────────────────────────

    private static ItemValidator.RawItem good(String stem) {
        return new ItemValidator.RawItem("集合", "", "medium", stem,
                List.of("死循环", "内存泄漏", "数据丢失", "编译失败"), 0,
                "死循环是 JDK7 头插法在并发扩容时的典型后果。");
    }

    /** 选项只有 3 个 → 必被丢弃。 */
    private static ItemValidator.RawItem bad(String stem) {
        return new ItemValidator.RawItem("集合", "", "medium", stem,
                List.of("死循环", "内存泄漏", "数据丢失"), 0, "解析在这里写着但选项不够。");
    }

    // ── 正常路径 ────────────────────────────────────────────────────

    @Test
    @DisplayName("正常出卷：建营、落题、结算，题号从 1 开始")
    void createsCampAndSettles() {
        when(generator.diagnostic(anyString(), anyString(), anyInt(), any()))
                .thenReturn(new CampItemGenerator.Generated(List.of(good("HashMap 在并发扩容时可能发生什么？")),
                        "{}", new TokenUsage(100, 50, 150)));

        CampService.Created created = service.create(USER, TEMPLATE_ID, 3);

        assertThat(created.id()).isEqualTo(PROGRAM_ID);
        assertThat(created.itemCount()).isEqualTo(1);
        assertThat(created.droppedCount()).isZero();
        // 目标 12 道、只拿到 1 道 → 必须告诉界面"题数不足"
        assertThat(created.shortOfTarget()).isTrue();
        verify(camps).insertItem(any());
        verify(credits).settleCamp(eq(USER), eq(100L), eq("deepseek-flash"), eq(100), eq(50), eq(PROGRAM_ID));
        verify(credits, never()).refund(anyLong(), anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("天数不是 3 或 7 → 400，且不碰上游也不预扣")
    void rejectsBadDays() {
        assertThatThrownBy(() -> service.create(USER, TEMPLATE_ID, 5))
                .isInstanceOf(BizException.class);

        verify(credits, never()).reserve(anyLong(), anyLong());
        verify(generator, never()).diagnostic(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("画像为空的简历 → 拒绝、不建营、不扣积分、上游 0 次")
    void emptyDigestIsRejected() {
        ResumeData blank = new ResumeData(
                new ResumeData.Basics("张三", "13800001111", "z@example.com", "杭州", List.of()),
                new ResumeData.Intent("", "", ""),
                List.of(), List.of(), List.of(), List.of(), List.of(), "");
        when(documents.get(USER, TEMPLATE_ID))
                .thenReturn(new ResumeDocumentService.Detail(TEMPLATE_ID, "空简历", true, 1, blank, null));

        assertThatThrownBy(() -> service.create(USER, TEMPLATE_ID, 3))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("可考核的技能");

        verify(credits, never()).reserve(anyLong(), anyLong());
        verify(generator, never()).diagnostic(any(), any(), anyInt(), any());
        verify(camps, never()).insertProgram(any());
    }

    @Test
    @DisplayName("已有 3 个营 → 409")
    void rejectsFourthCamp() {
        when(camps.countPrograms(USER)).thenReturn(3);

        assertThatThrownBy(() -> service.create(USER, TEMPLATE_ID, 3))
                .isInstanceOf(BizException.class);

        verify(generator, never()).diagnostic(any(), any(), anyInt(), any());
    }

    // ── 计费 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("余额不足：402 且一次上游请求都没发出")
    void insufficientCreditsNeverCallUpstream() {
        when(credits.reserve(eq(USER), anyLong()))
                .thenThrow(new BizException(ErrorCode.INSUFFICIENT_CREDITS, "积分不足"));

        assertThatThrownBy(() -> service.create(USER, TEMPLATE_ID, 3))
                .isInstanceOf(BizException.class)
                .extracting(failure -> ((BizException) failure).errorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_CREDITS);

        verify(generator, never()).diagnostic(any(), any(), anyInt(), any());
        verify(camps, never()).insertProgram(any());
    }

    // ── 失败语义 ────────────────────────────────────────────────────

    @Test
    @DisplayName("模型空返回 → 抛错、退款一次、不建营（不能当成 0 道题）")
    void emptyResponseIsFailure() {
        when(generator.diagnostic(anyString(), anyString(), anyInt(), any()))
                .thenThrow(CampGenerationException.emptyResponse());

        assertThatThrownBy(() -> service.create(USER, TEMPLATE_ID, 3))
                .isInstanceOf(CampGenerationException.class)
                .extracting(failure -> ((CampGenerationException) failure).kind())
                .isEqualTo(CampGenerationException.Kind.EMPTY_RESPONSE);

        verify(credits, times(1)).refund(eq(USER), eq(100L), any(), anyString());
        verify(camps, never()).insertProgram(any());
    }

    @Test
    @DisplayName("第一轮有坏题 → 修复重试一次；修好后两轮的好题都在")
    void repairsOnce() {
        when(generator.diagnostic(anyString(), anyString(), anyInt(), any()))
                .thenReturn(new CampItemGenerator.Generated(
                        List.of(bad("坏题一：HashMap 在并发扩容时？"), bad("坏题二：HashMap 扩容会？")),
                        "{\"items\":[]}", new TokenUsage(100, 40, 140)));
        when(generator.repair(anyString(), anyList(), any()))
                .thenReturn(new CampItemGenerator.Generated(
                        List.of(good("HashMap 在并发扩容时可能发生什么？"),
                                good("ConcurrentHashMap 的扩容是如何保证安全的？")),
                        "{}", new TokenUsage(80, 60, 140)));

        CampService.Created created = service.create(USER, TEMPLATE_ID, 3);

        assertThat(created.itemCount()).isEqualTo(2);
        verify(generator, times(1)).repair(anyString(), anyList(), any());
        // 两轮都是真实的上游调用：用量要相加（100+80 / 40+60），只记修复轮会让用户少付一次钱
        verify(credits).settleCamp(eq(USER), eq(100L), anyString(), eq(180), eq(100), eq(PROGRAM_ID));
    }

    @Test
    @DisplayName("两轮都坏 → 一道有效题都没有，退款且不建营")
    void bothRoundsBadRefunds() {
        when(generator.diagnostic(anyString(), anyString(), anyInt(), any()))
                .thenReturn(new CampItemGenerator.Generated(
                        List.of(bad("坏题一：HashMap 在并发扩容时？")), "{}", new TokenUsage(100, 40, 140)));
        when(generator.repair(anyString(), anyList(), any()))
                .thenReturn(new CampItemGenerator.Generated(
                        List.of(bad("坏题二：HashMap 扩容会？")), "{}", new TokenUsage(80, 40, 120)));

        assertThatThrownBy(() -> service.create(USER, TEMPLATE_ID, 3))
                .isInstanceOf(CampGenerationException.class)
                .extracting(failure -> ((CampGenerationException) failure).kind())
                .isEqualTo(CampGenerationException.Kind.NO_VALID_ITEM);

        // 用量已经产生过 → 按实际结算，而不是全额退款（平台不该替用户垫钱）
        verify(credits).settleCamp(eq(USER), eq(100L), anyString(), any(), any(), any());
        verify(credits, never()).refund(anyLong(), anyLong(), any(), anyString());
        verify(camps, never()).insertProgram(any());
    }

    @Test
    @DisplayName("修复本身失败时留下第一轮的有效题，不把这次调用白花掉")
    void repairFailureKeepsFirstRoundItems() {
        when(generator.diagnostic(anyString(), anyString(), anyInt(), any()))
                .thenReturn(new CampItemGenerator.Generated(
                        List.of(good("HashMap 在并发扩容时可能发生什么？"), bad("坏题：HashMap 会怎样？")),
                        "{}", new TokenUsage(100, 40, 140)));
        when(generator.repair(anyString(), anyList(), any()))
                .thenThrow(CampGenerationException.emptyResponse());

        CampService.Created created = service.create(USER, TEMPLATE_ID, 3);

        assertThat(created.itemCount()).isEqualTo(1);
    }

    // ── 归属 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("别人的营一律 404，且不带出任何内容")
    void otherUsersCampIs404() {
        when(camps.findProgram(555L, USER)).thenReturn(null);

        assertThatThrownBy(() -> service.get(USER, 555L))
                .isInstanceOf(BizException.class)
                .extracting(failure -> ((BizException) failure).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }
}
