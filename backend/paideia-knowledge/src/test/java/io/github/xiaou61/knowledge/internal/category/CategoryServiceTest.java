package io.github.xiaou61.knowledge.internal.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 学习者侧分类树的行为。
 *
 * <p>2026-10-26 用户反馈：从 `/learn` 点关卡进入的条目，在知识库里找不到对应分类。
 * 原因是这里曾把带 `line_kind` 的分类过滤掉。这个用例把"轨道分类必须出现在浏览树里"
 * 固定下来，避免以后又按"从零开始不要是知识库"的理由把它藏回去。
 */
class CategoryServiceTest {

    private static Category category(long id, String name, String slug, String lineKind) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        category.setSlug(slug);
        category.setLineKind(lineKind);
        return category;
    }

    @Test
    void browsingTreeKeepsLearningTrackCategories() {
        CategoryMapper mapper = mock(CategoryMapper.class);
        when(mapper.findAll()).thenReturn(List.of(
                category(1L, "Git", "git", "reading"),
                category(2L, "计算机基础", "cs-basics", null)));

        List<CategoryTree.Node> tree = new CategoryService(mapper).tree();

        assertThat(tree).extracting(CategoryTree.Node::name).containsExactly("Git", "计算机基础");
        assertThat(tree.get(0).lineKind()).isEqualTo("reading");
        assertThat(tree.get(1).lineKind()).isNull();
    }
}
