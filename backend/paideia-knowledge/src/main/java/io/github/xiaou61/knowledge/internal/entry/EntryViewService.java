package io.github.xiaou61.knowledge.internal.entry;

import io.github.xiaou61.knowledge.internal.entry.Entry;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 浏览记录写入（WORK-021）。
 *
 * <p>它记录的是"路过"，与 {@code learning_progress}（"开始学这一课"）是两件事：
 * 前者不该进课程进度统计，后者不能被一次无意点击触发。
 *
 * <p>失败不该影响阅读：写入异常在调用方吞掉（见 {@code EntryViewController}），
 * 这是旁路数据，不值得让阅读页报错。
 */
@Service
public class EntryViewService {

    private final EntryViewMapper mapper;
    private final EntryMapper entryMapper;

    EntryViewService(EntryViewMapper mapper, EntryMapper entryMapper) {
        this.mapper = mapper;
        this.entryMapper = entryMapper;
    }

    /**
     * 记一次浏览。
     *
     * <p>先校验条目仍在发布：对草稿上报成功等于告诉调用者"这里有个未发布的条目"，
     * 与自评、学习记录同一口径。
     */
    public void record(long userId, long entryId) {
        Entry entry = entryMapper.findPublishedById(entryId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "条目不存在"));
        mapper.upsert(userId, entry.getId(), Instant.now());
    }

    /**
     * 某个用户看过的全部条目（2026-11-11）。
     *
     * <p>给知识库列表用：左栏显示「已读 x / y」、右栏把已读行标出来。数据本来就在库里
     * （{@link #record} 每次打开都在累加），这里只是把它读出来——**不需要客户端自己记**
     * （换设备、清缓存都不丢）。
     *
     * <p>只下发 id、次数、最近时间，不带标题：列表页手里已经有标题，重复下发等于把
     * 650 条标题再传一遍。
     */
    public List<ViewedEntry> listViewed(long userId) {
        return mapper.findByUser(userId).stream()
                .map(row -> new ViewedEntry(row.entryId(), row.viewCount(), row.lastViewedAt()))
                .toList();
    }

    /** 对外形状：某个条目「看过几次、最后一次什么时候」。 */
    public record ViewedEntry(long entryId, int viewCount, Instant lastViewedAt) {
    }
}
