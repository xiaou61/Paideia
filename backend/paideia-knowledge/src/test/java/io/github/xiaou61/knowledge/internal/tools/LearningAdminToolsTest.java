package io.github.xiaou61.knowledge.internal.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.knowledge.internal.question.QuestionConceptAdminService;
import io.github.xiaou61.knowledge.internal.question.QuestionConceptSuggester;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 题目标注工具（WORK-038 TASK-006）：整组替换与权重夹取。 */
class LearningAdminToolsTest {

    private static final ToolContext ADMIN =
            new ToolContext(1L, "admin", Set.of(ToolScope.ADMIN), ToolContext.Origin.EXTERNAL);

    private final QuestionConceptAdminService service = mock(QuestionConceptAdminService.class);

    @Test
    void descriptorsMatchTheGroupContract() throws Exception {
        List<ToolDescriptor> descriptors = new ArrayList<>();
        for (Class<?> type : LearningAdminTools.class.getDeclaredClasses()) {
            if (!PaideiaTool.class.isAssignableFrom(type)) {
                continue;
            }
            var constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object[] args = new Object[constructor.getParameterCount()];
            descriptors.add(((PaideiaTool) constructor.newInstance(args)).descriptor());
        }

        assertThat(descriptors).hasSize(4);
        Set<String> names = new HashSet<>();
        for (ToolDescriptor descriptor : descriptors) {
            assertThat(descriptor.name()).startsWith("admin_learning_");
            assertThat(descriptor.toolset()).isEqualTo("admin_learning");
            assertThat(descriptor.scope()).isEqualTo(ToolScope.ADMIN);
            assertThat(names.add(descriptor.name())).isTrue();
        }
        assertThat(descriptors).filteredOn(ToolDescriptor::readOnly).hasSize(3);
    }

    @Test
    void replaceClampsWeightAndPassesRequests() {
        ToolResult result = new LearningAdminTools.SetQuestionConcepts(service).call(ADMIN, Map.of(
                "entryId", 7,
                "blockKey", "quiz-1",
                "questionKey", "q1",
                "concepts", List.of(
                        Map.of("conceptId", 5, "weight", 0.8),
                        Map.of("conceptId", 6, "weight", 3)),
                "source", "author"));

        assertThat(result.error()).isFalse();
        verify(service).replace(eq(7L), eq("quiz-1"), eq("q1"), eq(List.of(
                new QuestionConceptAdminService.TagRequest(5L, 0.8),
                new QuestionConceptAdminService.TagRequest(6L, 1.0))), eq("author"));
    }

    @Test
    void replaceRequiresConceptsArray() {
        ToolResult result = new LearningAdminTools.SetQuestionConcepts(service).call(ADMIN, Map.of(
                "entryId", 7, "blockKey", "quiz-1", "questionKey", "q1"));

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("concepts");
    }

    @Test
    void suggestionsAreTextLines() {
        when(service.suggestions(7L, "quiz-1", "q1", 5)).thenReturn(List.of(
                new QuestionConceptSuggester.Suggestion(5L, "索引", 0.9, List.of("索引"))));

        ToolResult result = new LearningAdminTools.SuggestConcepts(service).call(ADMIN, Map.of(
                "entryId", 7, "blockKey", "quiz-1", "questionKey", "q1"));

        assertThat(result.error()).isFalse();
        assertThat(result.text()).contains("索引").contains("id=5");
        verify(service).suggestions(7L, "quiz-1", "q1", 5);
    }
}
