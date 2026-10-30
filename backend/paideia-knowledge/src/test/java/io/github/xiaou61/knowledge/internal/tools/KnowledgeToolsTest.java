package io.github.xiaou61.knowledge.internal.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.knowledge.EntryContent;
import io.github.xiaou61.knowledge.KnowledgeApi;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 知识库工具的映射与归属。
 *
 * <p>重点不是"能不能调通"，而是两条纪律：**归属只来自上下文**、**模型给的条数要被夹住**。
 * 这两条都不会在正常路径上出错，只会在别人传参或模型乱填时暴露。
 */
class KnowledgeToolsTest {

    private static final ToolContext CALLER = ToolContext.internal(7L, "USER");

    private static final KnowledgeApi KNOWLEDGE = new KnowledgeApi() {
        @Override
        public Optional<EntryContent> findPublishedContent(long entryId) {
            return entryId == 1L
                    ? Optional.of(new EntryContent(1L, "单体的边界", "正文内容", java.time.Instant.EPOCH))
                    : Optional.empty();
        }

        @Override
        public List<EntryBrief> searchPublished(String keyword, int limit) {
            return List.of(new EntryBrief(1L, "单体的边界"), new EntryBrief(2L, "索引与回表"));
        }

        @Override
        public List<CategoryNode> listCategories() {
            return List.of(new CategoryNode(10L, "架构", "arch", null), new CategoryNode(11L, "数据库", "db", 10L));
        }

        @Override
        public List<MarkedEntry> findMarks(long userId, int limit) {
            return userId == 7L ? List.of(new MarkedEntry(2L, "索引与回表", "unsure")) : List.of();
        }
    };

    @Test
    void searchFormatsHits() {
        ToolResult result = new KnowledgeTools.Search(KNOWLEDGE).call(CALLER, Map.of("keyword", "索引"));
        assertThat(result.error()).isFalse();
        assertThat(result.text()).contains("id=1", "单体的边界").contains("id=2", "索引与回表");
    }

    @Test
    void searchExplainsEmptyResult() {
        KnowledgeApi empty = new KnowledgeApi() {
            @Override
            public Optional<EntryContent> findPublishedContent(long entryId) {
                return Optional.empty();
            }

            @Override
            public List<EntryBrief> searchPublished(String keyword, int limit) {
                return List.of();
            }

            @Override
            public List<CategoryNode> listCategories() {
                return List.of();
            }

            @Override
            public List<MarkedEntry> findMarks(long userId, int limit) {
                return List.of();
            }
        };

        ToolResult result = new KnowledgeTools.Search(empty).call(CALLER, Map.of("keyword", "区块链"));
        assertThat(result.error()).isFalse();
        assertThat(result.text()).contains("没有检索到");
    }

    @Test
    void limitIsClampedAndNeverTrusted() {
        int[] observed = {-1};
        KnowledgeApi capture = new KnowledgeApi() {
            @Override
            public Optional<EntryContent> findPublishedContent(long entryId) {
                return Optional.empty();
            }

            @Override
            public List<EntryBrief> searchPublished(String keyword, int limit) {
                observed[0] = limit;
                return List.of();
            }

            @Override
            public List<CategoryNode> listCategories() {
                return List.of();
            }

            @Override
            public List<MarkedEntry> findMarks(long userId, int limit) {
                return List.of();
            }
        };
        new KnowledgeTools.Search(capture).call(CALLER, Map.of("limit", 1000));
        assertThat(observed[0]).isEqualTo(20);
        new KnowledgeTools.Search(capture).call(CALLER, Map.of("limit", 0));
        assertThat(observed[0]).isEqualTo(5);
        new KnowledgeTools.Search(capture).call(CALLER, Map.of("limit", "abc"));
        assertThat(observed[0]).isEqualTo(5);
    }

    @Test
    void myMarksUsesContextUserAndIgnoresUserIdArgument() {
        // 参数里塞一个别人的 id：不该改变任何行为，归属仍是调用上下文的 7L
        ToolResult result = new KnowledgeTools.MyMarks(KNOWLEDGE)
                .call(CALLER, Map.of("userId", 999L, "ownerId", 999L));
        assertThat(result.error()).isFalse();
        assertThat(result.text()).contains("索引与回表", "unsure");
    }

    @Test
    void getEntryReturnsContentOrNotFound() {
        assertThat(new KnowledgeTools.GetEntry(KNOWLEDGE).call(CALLER, Map.of("entryId", 1)).text())
                .contains("单体的边界", "正文内容");
        ToolResult missing = new KnowledgeTools.GetEntry(KNOWLEDGE).call(CALLER, Map.of("entryId", 2));
        assertThat(missing.error()).isTrue();
        assertThat(missing.text()).contains("没有找到");
    }

    @Test
    void getEntryWithoutIdFailsReadably() {
        ToolResult result = new KnowledgeTools.GetEntry(KNOWLEDGE).call(CALLER, Map.of());
        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("entryId");
    }

    @Test
    void getEntryAcceptsObjectUri() {
        // 前端递过来的附件是 entry:123 这种引用（WORK-058）；选中片段带 /selection 后缀也认
        assertThat(new KnowledgeTools.GetEntry(KNOWLEDGE).call(CALLER, Map.of("uri", "entry:1")).text())
                .contains("单体的边界");
        assertThat(new KnowledgeTools.GetEntry(KNOWLEDGE).call(CALLER, Map.of("uri", "entry:1/selection")).text())
                .contains("单体的边界");
        // 类型不符不能当成别的对象读
        assertThat(new KnowledgeTools.GetEntry(KNOWLEDGE).call(CALLER, Map.of("uri", "note:1")).error())
                .isTrue();
    }

    @Test
    void descriptorsAreReadOnlyAndInKnowledgeToolset() {
        List<ToolDescriptor> descriptors = List.of(
                new KnowledgeTools.Search(KNOWLEDGE).descriptor(),
                new KnowledgeTools.GetEntry(KNOWLEDGE).descriptor(),
                new KnowledgeTools.ListCategories(KNOWLEDGE).descriptor(),
                new KnowledgeTools.MyMarks(KNOWLEDGE).descriptor());

        assertThat(descriptors).allSatisfy(descriptor -> {
            assertThat(descriptor.toolset()).isEqualTo("knowledge");
            assertThat(descriptor.readOnly()).isTrue();
            assertThat(descriptor.scope()).isEqualTo(ToolScope.READ);
            assertThat(descriptor.inputSchema()).containsEntry("type", "object");
        });
    }
}
