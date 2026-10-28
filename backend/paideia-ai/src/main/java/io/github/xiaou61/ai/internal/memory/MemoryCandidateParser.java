package io.github.xiaou61.ai.internal.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.ai.MemoryCandidate;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 把模型返回的 JSON 规范化成可执行的候选（WORK-013）。
 *
 * <p><b>为什么必须有它</b>：与 {@code SummarySchemaValidator} 同一个理由——模型返回的**空内容**是
 * 本项目已知的真实故障模式（DeepSeek 把 `max_tokens` 吃在思考上时会**静默返回空**）。没有这道校验，
 * "空"会被当成"这几轮没什么值得记的"，于是记忆永远不增长，而没有任何报错能提示这件事。
 *
 * <p>所以：解析失败、字段缺失、动作不认识、`UPDATE`/`DELETE` 没有指向、内容超长，**一律整次放弃**
 * （调用方不写半条）。**唯一的例外**是 `kind` 越界——那不是错误，按设计规范成全文那路
 * （见 {@link MemoryKinds}）。
 *
 * <p>这里是**规范化**而不是"照抄模型的话"：`action` 大小写、`store` 的写法、`kind` 的别名都在这里
 * 收口。模型输出是不可信输入，而它决定的是"我们能对库做什么"，所以这一层宁可严。
 */
@Component
public class MemoryCandidateParser {

    /** 单次整理最多采纳多少条候选。防"一口气给 50 条"把一次整理变成一次批量导入。 */
    private static final int MAX_CANDIDATES = 10;
    /** 与迁移列宽一致（两路的上限不同，见下）。 */
    private static final int MAX_STRUCTURED_CHARS = 512;
    private static final int MAX_TEXT_CHARS = 1024;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 解析根对象里的 `candidates` 数组。空数组是**合法**结果；空内容不是（见类注释）。 */
    public List<MemoryCandidate> parse(String rawModelOutput) {
        if (rawModelOutput == null || rawModelOutput.isBlank()) {
            throw failure("模型没有返回任何内容");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(stripFence(rawModelOutput));
        } catch (Exception exception) {
            throw failure("模型返回的内容不是合法 JSON");
        }
        JsonNode array = root.isArray() ? root : root.get("candidates");
        if (array == null || !array.isArray()) {
            throw failure("返回里没有 candidates 数组");
        }
        if (array.size() > MAX_CANDIDATES) {
            throw failure("候选数量超出上限（" + MAX_CANDIDATES + "）");
        }

        List<MemoryCandidate> candidates = new ArrayList<>();
        for (JsonNode node : array) {
            candidates.add(one(node));
        }
        return candidates;
    }

    private MemoryCandidate one(JsonNode node) {
        MemoryCandidate.Action action = action(text(node, "action"));
        String reason = text(node, "reason");
        Long refId = positiveId(text(node, "refId"));

        // SKIP 与 PROPOSE **不强制 store**：两者都不写正式表，放哪一路是后话。
        // 基线实测这条很现实——模型对灰区内容会给"没有 store"的对象，严格校验会把整次整理废掉。
        if (action == MemoryCandidate.Action.SKIP) {
            return new MemoryCandidate(action, storeOrDefault(text(node, "store")), null, null, null, null, reason);
        }

        if (action == MemoryCandidate.Action.PROPOSE) {
            String proposed = text(node, "content");
            if (proposed == null || proposed.isBlank()) {
                throw failure("PROPOSE 的内容为空");
            }
            String trimmed = proposed.trim();
            if (trimmed.length() > MAX_TEXT_CHARS) {
                throw failure("PROPOSE 的内容超长");
            }
            return new MemoryCandidate(action, storeOrDefault(text(node, "store")), null,
                    MemoryKinds.normalize(text(node, "kind")), refId, trimmed, reason);
        }

        MemoryCandidate.Store store = store(text(node, "store"));

        if (action == MemoryCandidate.Action.DELETE) {
            Long targetId = positiveId(text(node, "targetId"));
            if (targetId == null) {
                throw failure("DELETE 没有给 targetId");
            }
            return new MemoryCandidate(action, store, targetId, null, null, null, reason);
        }

        // ADD / UPDATE：都要有内容
        String content = text(node, "content");
        if (content == null || content.isBlank()) {
            throw failure(action + " 的内容为空");
        }
        String trimmed = content.trim();
        int limit = store == MemoryCandidate.Store.STRUCTURED ? MAX_STRUCTURED_CHARS : MAX_TEXT_CHARS;
        if (trimmed.length() > limit) {
            throw failure(action + " 的内容超长");
        }

        String kind = MemoryKinds.normalize(text(node, "kind"));
        MemoryCandidate.Store effective = store;
        // kind 只对 ADD 有意义。
        //
        // **UPDATE 绝不能因为 kind 缺失/不认识就改道到 TEXT**：两路的 id 各自自增、彼此独立，
        // 拿结构化记忆的 targetId 去 update ai_memories_text 会命中同一用户另一条无辜记忆并
        // 覆盖它的内容（比抛错更难发现）。而提示词对 UPDATE 只要求给 targetId 与所在 store，
        // 模型很可能不回 kind —— 这条路径相当现实。
        if (action != MemoryCandidate.Action.UPDATE
                && effective == MemoryCandidate.Store.STRUCTURED && kind == null) {
            // 结构化那路必须有白名单内的 kind；模型没给或不认识 → 落全文那路（设计 §4）
            effective = MemoryCandidate.Store.TEXT;
        }
        if (effective == MemoryCandidate.Store.STRUCTURED) {
            // UPDATE 也必须带 targetId：结构化那路同样要按 id 覆盖，缺了它会带 null 到
            // applyAuto，那里参数是基本类型 long，拆箱即 NPE，整批整理一起回滚。
            Long targetId = action == MemoryCandidate.Action.UPDATE
                    ? positiveId(text(node, "targetId"))
                    : null;
            if (action == MemoryCandidate.Action.UPDATE && targetId == null) {
                throw failure("UPDATE 没有给 targetId");
            }
            return new MemoryCandidate(action, effective, targetId, kind, refId, trimmed, reason);
        }

        if (action == MemoryCandidate.Action.UPDATE) {
            Long targetId = positiveId(text(node, "targetId"));
            if (targetId == null) {
                throw failure("UPDATE 没有给 targetId");
            }
            return new MemoryCandidate(action, effective, targetId, null, null, trimmed, reason);
        }
        return new MemoryCandidate(action, effective, null, null, null, trimmed, reason);
    }

    private static MemoryCandidate.Action action(String raw) {
        if (raw == null) {
            throw failure("缺少 action");
        }
        try {
            return MemoryCandidate.Action.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw failure("action 不是五种动作之一");
        }
    }

    /** 缺省按全文那一路：它是"他说过的话"，比"事实"更保守（不会污染结构化白名单）。 */
    private static MemoryCandidate.Store storeOrDefault(String raw) {
        return raw == null || raw.isBlank() ? MemoryCandidate.Store.TEXT : store(raw);
    }

    private static MemoryCandidate.Store store(String raw) {
        if (raw == null) {
            throw failure("缺少 store");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "structured" -> MemoryCandidate.Store.STRUCTURED;
            case "text" -> MemoryCandidate.Store.TEXT;
            default -> throw failure("store 只能是 structured 或 text");
        };
    }

    /** id 只接受正整数：0 与负数一定是幻觉，而"猜一个 id"会改到别人的行（归属另由 SQL 兜）。 */
    private static Long positiveId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** 模型偶尔会套一层 ```json 代码块；只在整段被包住时剥掉，不做花哨清洗。 */
    private static String stripFence(String raw) {
        String trimmed = raw.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return trimmed;
        }
        return trimmed.substring(firstNewline + 1, lastFence).trim();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static BizException failure(String reason) {
        return new BizException(ErrorCode.INTERNAL, "记忆整理没有返回可用结果（" + reason + "）");
    }
}
