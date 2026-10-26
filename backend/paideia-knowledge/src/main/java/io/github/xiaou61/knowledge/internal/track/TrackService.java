package io.github.xiaou61.knowledge.internal.track;

import io.github.xiaou61.knowledge.internal.learning.LearningProgressService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 学习轨道的投影（WORK-024）。
 *
 * <p><b>与 WORK-020 的差别</b>：那时候"一条线"是知识库分类的标记，于是课程和文章混在
 * 同一个浏览入口里，也没有关卡概念。现在轨道是一张独立的表，关卡有自己的顺序、
 * 章节分组与**前置依赖**——地图上"锁住 / 可进入 / 已通关"三种状态才有意义。
 *
 * <p><b>关卡状态怎么算</b>：
 * <ul>
 *   <li>`done`：该关的条目在 `learning_progress` 里是 passed；</li>
 *   <li>`current`：没通关——任何一关都可以直接点进去。</li>
 * </ul>
 * 不做前置锁定：地图上的顺序与连线已经表达了"建议先学哪一关"，再锁住只会挡住
 * 只想查某一关的人。前置关系（`requires_entry_id`）仍然保留在数据里，它表达依赖，不表达门槛。
 */
@Service
public class TrackService {

    private final TrackMapper tracks;
    private final LearningProgressService progress;

    TrackService(TrackMapper tracks, LearningProgressService progress) {
        this.tracks = tracks;
        this.progress = progress;
    }

    /** 列表页的一行：轨道 + 我的进度。`mode` 决定它落在理论栏还是实战栏（WORK-038）。 */
    public record TrackView(long id, String slug, String name, String summary, String kind,
                            String mode, String theoryTrackSlug,
                            int stageCount, int passedCount, Long nextEntryId, String nextTitle) {
    }

    /** 地图上的一格。 */
    public record StageView(long entryId, int seq, String title, String status, Long requiresEntryId,
                            String level) {
    }

    /** 地图的一章。 */
    public record ChapterView(String chapter, List<StageView> stages) {
    }

    /** 一条轨道的完整地图。 */
    public record TrackMap(long id, String slug, String name, String summary, String kind,
                           String mode, String theoryTrackSlug,
                           List<ChapterView> chapters, int stageCount, int passedCount) {
    }

    /** 全部轨道 + 各自的进度。空轨道也算（用户要能看到"待更新"而不是消失）。 */
    public List<TrackView> list(long userId) {
        Map<Long, LearningProgressService.View> mine = progressByEntry(userId);
        List<TrackView> views = new ArrayList<>();
        for (TrackMapper.Track track : tracks.findAll()) {
            List<TrackMapper.Stage> stages = tracks.findStages(track.id());
            int passed = 0;
            Long nextEntry = null;
            String nextTitle = null;
            for (TrackMapper.Stage stage : stages) {
                LearningProgressService.View view = mine.get(stage.entryId());
                boolean done = view != null && LearningProgressService.STATUS_PASSED.equals(view.status());
                if (done) {
                    passed++;
                } else if (nextEntry == null) {
                    nextEntry = stage.entryId();
                    nextTitle = stage.title();
                }
            }
            views.add(new TrackView(track.id(), track.slug(), track.name(), track.summary(),
                    track.kind(), track.mode(), track.theoryTrackSlug(),
                    stages.size(), passed, nextEntry, nextTitle));
        }
        return List.copyOf(views);
    }

    /** 一条轨道的地图。找不到轨道时返回空。 */
    public Optional<TrackMap> map(long userId, String slug) {
        TrackMapper.Track track = tracks.findBySlug(slug);
        if (track == null) {
            return Optional.empty();
        }
        Map<Long, LearningProgressService.View> mine = progressByEntry(userId);
        List<TrackMapper.Stage> stages = tracks.findStages(track.id());

        Map<String, List<StageView>> byChapter = new LinkedHashMap<>();
        int passedCount = 0;
        for (TrackMapper.Stage stage : stages) {
            LearningProgressService.View view = mine.get(stage.entryId());
            boolean done = view != null && LearningProgressService.STATUS_PASSED.equals(view.status());
            if (done) {
                passedCount++;
            }
            String status = done ? "done" : "current";
            byChapter.computeIfAbsent(stage.chapter() == null ? "" : stage.chapter(),
                            key -> new ArrayList<>())
                    .add(new StageView(stage.entryId(), stage.seq(), stage.title(), status,
                            stage.requiresEntryId(), stage.level()));
        }

        List<ChapterView> chapters = new ArrayList<>();
        // 章节顺序按首次出现的顺序保留（LinkedHashMap 已经保证了这一点）
        for (Map.Entry<String, List<StageView>> entry : byChapter.entrySet()) {
            chapters.add(new ChapterView(entry.getKey(), List.copyOf(entry.getValue())));
        }
        return Optional.of(new TrackMap(track.id(), track.slug(), track.name(), track.summary(),
                track.kind(), track.mode(), track.theoryTrackSlug(),
                List.copyOf(chapters), stages.size(), passedCount));
    }

    /**
     * 我的进度按 entryId 索引。
     *
     * <p>要的是"这个用户全部关卡的进度"，不是一页：上限截断会把较早通关的关卡当成未通关。
     * 20 条轨道 × 几十关 + 知识库里的进度，几千行以内，取全量比漏算便宜。
     */
    private Map<Long, LearningProgressService.View> progressByEntry(long userId) {
        Map<Long, LearningProgressService.View> mine = new LinkedHashMap<>();
        for (LearningProgressService.View view : progress.listMine(userId, 2000)) {
            mine.put(view.entryId(), view);
        }
        return mine;
    }

    /** 一条轨道里出现过的章节（供导入脚本自检）。 */
    public List<String> chapters(long trackId) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (TrackMapper.Stage stage : tracks.findStages(trackId)) {
            names.add(stage.chapter());
        }
        return List.copyOf(names);
    }
}
