package io.github.xiaou61.note.internal.link;

import io.github.xiaou61.note.internal.note.NoteMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 双链（WORK-087 TASK-005）。
 *
 * <p>语法：正文里写 {@code [[标题]]} 或 {@code [[id:123]]}。保存文档时**按 source 全量重建**
 * 本篇的出链：先删旧关系，再解析正文里的引用并落 {@code note_links}。
 *
 * <p>解析规则：
 * <ul>
 *   <li>{@code [[id:N]]}：直接按 id 找本人文档（找不到=悬空，保留标题文本）；</li>
 *   <li>{@code [[标题]]}：在**同一知识库**内按标题精确匹配本人文档（重名取最早一条）；</li>
 *   <li>指向自己、或同一目标的重复引用会被忽略。</li>
 * </ul>
 */
@Service
public class NoteLinkService {

    private static final Pattern LINK = Pattern.compile("\\[\\[([^\\]]+)\\]\\]");
    /** 单篇最多记多少条出链，防一篇正文刷爆关系表。 */
    private static final int MAX_LINKS = 200;

    private final NoteLinkMapper mapper;
    private final NoteMapper notes;

    NoteLinkService(NoteLinkMapper mapper, NoteMapper notes) {
        this.mapper = mapper;
        this.notes = notes;
    }

    /** 一条引用：目标文档 id（悬空为 null）与显示用标题。 */
    public record LinkRef(Long noteId, String title) {
    }

    /** 一篇文档的出链与入链。 */
    public record Links(List<LinkRef> outbound, List<LinkRef> inbound) {
    }

    /** 保存文档时重建本篇出链。 */
    @Transactional
    public void rebuild(long userId, long sourceNoteId, long bookId, String body) {
        mapper.deleteBySource(sourceNoteId, userId);
        if (body == null || body.isEmpty()) {
            return;
        }
        Map<String, NoteLink> unique = new LinkedHashMap<>();
        Matcher matcher = LINK.matcher(body);
        while (matcher.find() && unique.size() < MAX_LINKS) {
            String raw = matcher.group(1).trim();
            if (raw.isEmpty()) {
                continue;
            }
            Long targetId = null;
            String targetTitle = raw;
            if (raw.regionMatches(true, 0, "id:", 0, 3)) {
                Long parsed = parseLong(raw.substring(3).trim());
                if (parsed == null || parsed == sourceNoteId) {
                    continue;
                }
                String foundTitle = notes.findDocTitleById(parsed, userId);
                if (foundTitle != null) {
                    targetId = parsed;
                    targetTitle = foundTitle;
                }
            } else {
                Long found = notes.findDocIdByTitle(userId, bookId, raw);
                if (found != null) {
                    if (found == sourceNoteId) {
                        continue;
                    }
                    targetId = found;
                }
            }
            String key = targetId != null ? "i:" + targetId : "t:" + targetTitle;
            if (unique.containsKey(key)) {
                continue;
            }
            NoteLink link = new NoteLink();
            link.setUserId(userId);
            link.setSourceNoteId(sourceNoteId);
            link.setTargetNoteId(targetId);
            link.setTargetTitle(targetTitle);
            unique.put(key, link);
        }
        for (NoteLink link : unique.values()) {
            mapper.insert(link);
        }
    }

    /** 删除某篇文档的全部出链（文档被删时清理）。 */
    @Transactional
    public void deleteForSource(long userId, long sourceNoteId) {
        mapper.deleteBySource(sourceNoteId, userId);
    }

    /** 出链 + 入链。调用方需先确认该文档属于本人（否则应 404）。 */
    public Links links(long userId, long noteId) {
        List<LinkRef> outbound = new ArrayList<>();
        for (NoteLinkMapper.LinkRow row : mapper.listOutbound(userId, noteId)) {
            outbound.add(new LinkRef(row.getTargetNoteId(), row.getTargetTitle()));
        }
        List<LinkRef> inbound = new ArrayList<>();
        for (NoteLinkMapper.LinkRow row : mapper.listInbound(userId, noteId)) {
            inbound.add(new LinkRef(row.getTargetNoteId(), row.getTargetTitle()));
        }
        return new Links(outbound, inbound);
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
