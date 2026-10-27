package io.github.xiaou61.ai.internal.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.xiaou61.ai.ResumeData;
import io.github.xiaou61.ai.internal.resume.ResumeDocumentService;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 简历工具（WORK-044 TASK-007）。
 *
 * <p>两条纪律：越权与不存在给同一句话；渲染出来的文本要带小标题（模型靠它认段落）。
 */
class ResumeToolsTest {

    private static final ToolContext CALLER =
            new ToolContext(7L, "learner", Set.of(ToolScope.READ), ToolContext.Origin.EXTERNAL);

    private final ResumeDocumentService documents = mock(ResumeDocumentService.class);

    @Test
    void descriptorMatchesCatalogRules() {
        ToolDescriptor descriptor = new ResumeTools.ListDocuments(documents).descriptor();

        assertThat(descriptor.name()).isEqualTo("resume_list");
        assertThat(descriptor.toolset()).isEqualTo("resume");
        assertThat(descriptor.name()).startsWith(descriptor.toolset() + "_");
        assertThat(descriptor.scope()).isEqualTo(ToolScope.READ);
        assertThat(descriptor.readOnly()).isTrue();
        assertThat(descriptor.defaultGroup()).as("不进默认视图").isFalse();
    }

    @Test
    void listRendersOneLinePerDocument() {
        when(documents.list(7L)).thenReturn(List.of(
                new ResumeDocumentService.Summary(1L, "主简历", true, 3, Instant.parse("2026-10-01T00:00:00Z"))));

        ToolResult result = new ResumeTools.ListDocuments(documents).call(CALLER, Map.of());

        assertThat(result.error()).isFalse();
        assertThat(result.text()).contains("id=1").contains("主简历").contains("默认");
    }

    @Test
    void listSaysSoWhenEmpty() {
        when(documents.list(anyLong())).thenReturn(List.of());

        assertThat(new ResumeTools.ListDocuments(documents).call(CALLER, Map.of()).text())
                .contains("还没有任何简历");
    }

    /** 不存在与不是本人的走同一条：区分开就等于确认了 id 存在。 */
    @Test
    void getTreatsMissingAsNotFound() {
        when(documents.get(7L, 99L)).thenThrow(new RuntimeException("404"));

        ToolResult result = new ResumeTools.Get(documents).call(CALLER, Map.of("documentId", 99));

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("没有找到");
    }

    @Test
    void getRequiresId() {
        assertThat(new ResumeTools.Get(documents).call(CALLER, Map.of()).text()).contains("缺少");
    }

    @Test
    void getRendersSectionsWithHeadings() {
        ResumeData data = new ResumeData(
                new ResumeData.Basics("张三", "13800000000", "z@example.com", "杭州", List.of("github.com/z")),
                new ResumeData.Intent("后端工程师", "杭州", "随时"),
                List.of(new ResumeData.Education("某大学", "本科", "计算机", "2018", "2022", List.of("一等奖学金"))),
                List.of(new ResumeData.Work("某公司", "实习生", "2021-06", "2021-09", "做后端", List.of("上线一个服务"))),
                List.of(new ResumeData.Project("某项目", "负责人", "2021", List.of("QPS 提升 3 倍"))),
                List.of(new ResumeData.SkillGroup("语言", List.of("Java", "Python"))),
                List.of(new ResumeData.Award("优秀毕业生", "某大学", "2022")),
                "踏实");
        when(documents.get(7L, 1L)).thenReturn(
                new ResumeDocumentService.Detail(1L, "主简历", true, 1, data, Instant.now()));

        String text = new ResumeTools.Get(documents).call(CALLER, Map.of("documentId", 1)).text();

        assertThat(text)
                .contains("## 基本信息").contains("张三")
                .contains("## 求职意向").contains("后端工程师")
                .contains("## 教育经历").contains("某大学")
                .contains("## 工作经历").contains("某公司")
                .contains("## 项目经历").contains("QPS 提升 3 倍")
                .contains("## 技能").contains("Java")
                .contains("## 奖项").contains("优秀毕业生")
                .contains("## 自我评价").contains("踏实");
    }
}
