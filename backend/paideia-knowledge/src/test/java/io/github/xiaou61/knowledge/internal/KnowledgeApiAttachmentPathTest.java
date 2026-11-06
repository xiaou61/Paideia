package io.github.xiaou61.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.knowledge.internal.assessment.SelfAssessmentService;
import io.github.xiaou61.knowledge.internal.cache.KnowledgeRedisCache;
import io.github.xiaou61.knowledge.internal.category.Category;
import io.github.xiaou61.knowledge.internal.category.CategoryMapper;
import io.github.xiaou61.knowledge.internal.category.CategoryService;
import io.github.xiaou61.knowledge.internal.concept.ConceptLinkMapper;
import io.github.xiaou61.knowledge.internal.concept.ConceptMapper;
import io.github.xiaou61.knowledge.internal.entry.EntryMapper;
import io.github.xiaou61.knowledge.internal.entry.EntryService;
import io.github.xiaou61.knowledge.internal.file.FileStorage;
import io.github.xiaou61.knowledge.internal.file.StoredFile;
import io.github.xiaou61.knowledge.internal.file.StoredFileMapper;
import io.github.xiaou61.knowledge.internal.learning.LearningProgressService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * WORK-076 TASK-002：KnowledgeApi 新增的 findAttachment / ensureCategoryPath。
 *
 * <p>纯单元测试（Mockito 桩），不连库：验的是“读附件字节”和“按路径复用/建分类”的编排逻辑，
 * 以及路径清洗（防目录穿越、去空段、限长）。
 */
class KnowledgeApiAttachmentPathTest {

    private final EntryMapper entryMapper = mock(EntryMapper.class);
    private final ConceptMapper conceptMapper = mock(ConceptMapper.class);
    private final ConceptLinkMapper conceptLinkMapper = mock(ConceptLinkMapper.class);
    private final EntryService entryService = mock(EntryService.class);
    private final CategoryService categoryService = mock(CategoryService.class);
    private final CategoryMapper categoryMapper = mock(CategoryMapper.class);
    private final StoredFileMapper storedFiles = mock(StoredFileMapper.class);
    private final FileStorage fileStorage = mock(FileStorage.class);
    private final SelfAssessmentService selfAssessments = mock(SelfAssessmentService.class);
    private final LearningProgressService learningProgress = mock(LearningProgressService.class);
    private final KnowledgeRedisCache cache = mock(KnowledgeRedisCache.class);

    private KnowledgeApiImplementation impl() {
        return new KnowledgeApiImplementation(entryMapper, conceptMapper, conceptLinkMapper, entryService,
                categoryService, categoryMapper, storedFiles, fileStorage, selfAssessments, learningProgress, cache);
    }

    // ── findAttachment ──────────────────────────────────────────────

    @Test
    @DisplayName("文件存在：返回字节与元数据")
    void findAttachmentReturnsBytes() {
        StoredFile meta = new StoredFile();
        meta.setId("f-1");
        meta.setOriginalName("背景.pdf");
        meta.setContentType("application/pdf");
        meta.setSizeBytes(3);
        when(storedFiles.findById("f-1")).thenReturn(Optional.of(meta));
        when(fileStorage.read("f-1")).thenReturn(Optional.of("abc".getBytes(StandardCharsets.UTF_8)));

        var result = impl().findAttachment("f-1");

        assertThat(result).isPresent();
        assertThat(result.get().originalName()).isEqualTo("背景.pdf");
        assertThat(result.get().contentType()).isEqualTo("application/pdf");
        assertThat(new String(result.get().bytes(), StandardCharsets.UTF_8)).isEqualTo("abc");
    }

    @Test
    @DisplayName("文件不存在或字节缺失：返回空，不抛异常")
    void findAttachmentMissingIsEmpty() {
        when(storedFiles.findById("nope")).thenReturn(Optional.empty());
        assertThat(impl().findAttachment("nope")).isEmpty();

        StoredFile meta = new StoredFile();
        meta.setId("f-2");
        when(storedFiles.findById("f-2")).thenReturn(Optional.of(meta));
        when(fileStorage.read("f-2")).thenReturn(Optional.empty());
        assertThat(impl().findAttachment("f-2")).isEmpty();
    }

    // ── ensureCategoryPath ──────────────────────────────────────────

    @Test
    @DisplayName("空路径返回 null（表示未分类）")
    void emptyPathIsNull() {
        assertThat(impl().ensureCategoryPath(List.of())).isNull();
        assertThat(impl().ensureCategoryPath(null)).isNull();
    }

    @Test
    @DisplayName("逐层创建：父链正确、slug 为 imp- 前缀、返回叶子 id")
    void createsNestedPath() {
        when(categoryMapper.findByParentAndName(any(), anyString())).thenReturn(null);
        when(categoryMapper.countBySlug(anyString(), any())).thenReturn(0L);
        List<Category> inserted = new ArrayList<>();
        AtomicLong seq = new AtomicLong(100);
        when(categoryMapper.insert(any(Category.class))).thenAnswer(inv -> {
            Category c = inv.getArgument(0);
            c.setId(seq.incrementAndGet());
            inserted.add(c);
            return 1;
        });

        Long leaf = impl().ensureCategoryPath(List.of("第一章", "背景"));

        assertThat(leaf).isEqualTo(102L);
        assertThat(inserted).hasSize(2);
        assertThat(inserted.get(0).getName()).isEqualTo("第一章");
        assertThat(inserted.get(0).getParentId()).isNull();
        assertThat(inserted.get(0).getSlug()).startsWith("imp-");
        assertThat(inserted.get(1).getName()).isEqualTo("背景");
        assertThat(inserted.get(1).getParentId()).isEqualTo(101L);
        verify(cache).evictCategories();
    }

    @Test
    @DisplayName("同名同父已存在：复用不重建，返回已存在的叶子")
    void reusesExisting() {
        Category existing = new Category();
        existing.setId(7L);
        existing.setName("第一章");
        when(categoryMapper.findByParentAndName(null, "第一章")).thenReturn(existing);

        Long leaf = impl().ensureCategoryPath(List.of("第一章"));

        assertThat(leaf).isEqualTo(7L);
        verify(categoryMapper, never()).insert(any());
        verify(cache, never()).evictCategories();
    }

    @Test
    @DisplayName("路径清洗：去 ../ 前缀、跳过空段与 ..、超长按 64 截断")
    void sanitizesPath() {
        when(categoryMapper.findByParentAndName(any(), anyString())).thenReturn(null);
        when(categoryMapper.countBySlug(anyString(), any())).thenReturn(0L);
        List<Category> inserted = new ArrayList<>();
        AtomicLong seq = new AtomicLong(1);
        when(categoryMapper.insert(any(Category.class))).thenAnswer(inv -> {
            Category c = inv.getArgument(0);
            c.setId(seq.incrementAndGet());
            inserted.add(c);
            return 1;
        });

        String longName = "长".repeat(80);
        impl().ensureCategoryPath(List.of("../evil", "", "..", "  ", longName));

        // ../evil → 取末段 evil；空/.. 跳过；长名截到 64
        assertThat(inserted).extracting(Category::getName).containsExactly("evil", "长".repeat(64));
    }
}
