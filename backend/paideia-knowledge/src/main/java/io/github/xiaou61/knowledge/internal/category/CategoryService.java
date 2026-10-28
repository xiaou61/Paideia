package io.github.xiaou61.knowledge.internal.category;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 分类的读取用例。
 *
 * <p>写用例（新建／改名／换父／删除）在 `admin` 侧单独一个服务——读路径与写路径的校验
 * 完全不同，混在一起会让"读"也被迫依赖管理员才有的东西。
 */
@Service
public class CategoryService {

    private final CategoryMapper categoryMapper;

    CategoryService(CategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    /** 扁平全量，按展示顺序。装树、取子树、取分类名都基于这一份。 */
    public List<Category> all() {
        return categoryMapper.findAll();
    }

    /**
     * 知识库浏览用的分类树。
     *
     * <p>**学习轨道下的分类也在这里**（2026-10-26 用户反馈后恢复）：轨道关卡的正文本来
     * 就是知识库条目，学习者从 `/learn` 点进去落到的也是 `/knowledge/entries/{id}`。
     * 把它们从分类树里藏掉会让人在知识库里找不到刚读过的内容，也看不出两者是同一批数据。
     * 引导式入口仍然只有 `/learn`（关卡地图），知识库这边是"按分类翻阅"的另一条路。
     */
    public List<CategoryTree.Node> tree() {
        return CategoryTree.assemble(categoryMapper.findAll());
    }

    /**
     * **全部分类**。
     *
     * <p>管理端与导入脚本用它：它们要维护这些分类，所以不能看到被过滤后的树
     * （否则导入脚本会以为分类不存在，去建一个同名的，撞唯一键 409）。
     *
     * <p>学习者侧现在也返回全量（见 {@link #tree()}），这个入口保留是因为它的调用方
     * 关心的是"全部"，而 `tree()` 以后可能再按浏览语义调整。
     */
    public List<CategoryTree.Node> fullTree() {
        return CategoryTree.assemble(categoryMapper.findAll());
    }
}
