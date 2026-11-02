package io.github.xiaou61.ai.internal.resume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.xiaou61.ai.ResumeData;
import io.github.xiaou61.ai.internal.support.ModelJson;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 简历 JSON 的**严格解析与裁剪**（WORK-018 TASK-003）。
 *
 * <p>三条纪律，都是"模型会怎么错"的实测结论推出来的：
 * <ol>
 *   <li><b>未知字段直接丢</b>，不报错。模型给多字段是常态，为此失败只会让整次调用白花钱。</li>
 *   <li><b>超长与超额裁剪并记录</b>，不静默。用户需要知道"你这份简历太长，后半段没被读进去"。</li>
 *   <li><b>类型不对就当作缺失</b>。模型把 {@code bullets} 写成字符串（而不是数组）是真实发生过的
 *       一类错误；把它当空数组处理比整段失败更有用，而"内容丢了"会在核对页立刻看见。</li>
 * </ol>
 *
 * <p>{@link #parseDraft} 额外的第四件事：**引证校验**。模型给的 {@code quote} 必须真的出现在
 * 原文里（归一化后子串匹配），否则丢掉该条目并计入 {@code dropped}。这是"模型编造一段经历"
 * 最廉价也最有效的一道闸门——它不花 token，也不依赖模型的自觉。
 */
@Component
public class ResumeJson {

    /** 单字段长度上限。超过就截断（不是拒绝）：拒绝会让用户丢掉整份简历。 */
    static final int MAX_TEXT = 4000;
    /** 单条目内要点数上限。 */
    static final int MAX_BULLETS = 30;
    /** 单个数组的条目数上限。 */
    static final int MAX_ITEMS = 50;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 解析结果：结构化数据 + 丢弃计数（给核对页提示用）。 */
    public record ParsedDraft(ResumeData data, int dropped, int truncatedFields) {
    }

    /**
     * 解析模型返回的**草稿**，并做引证校验。
     *
     * @param rawModelOutput 模型原文（可能被 ```json 包住）
     * @param plainText      抽取出的简历原文，引证要能在这里找到
     */
    public ParsedDraft parseDraft(String rawModelOutput, String plainText) {
        ObjectNode root = readObject(rawModelOutput);
        String normalizedSource = TextNormalizer.normalize(plainText);
        Counters counters = new Counters();
        ResumeData data = read(root, counters, normalizedSource, true);
        return new ParsedDraft(data, counters.dropped, counters.truncatedFields);
    }

    /**
     * 解析**已保存**的简历（读库时用）。
     *
     * <p>不做引证校验：库里的数据是用户确认过的，引证早就丢了；而且用户手工新增的条目本来
     * 就没有原文对应。
     */
    public ResumeData parseStored(String json) {
        ObjectNode root = readObject(json);
        return read(root, new Counters(), null, false);
    }

    /** 结构化数据 → 存库 JSON。与 {@link #parseStored} 对称。 */
    public String write(ResumeData data) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode basics = root.putObject("basics");
        basics.put("name", data.basics().name());
        basics.put("phone", data.basics().phone());
        basics.put("email", data.basics().email());
        basics.put("city", data.basics().city());
        array(basics, "links", data.basics().links());

        ObjectNode intent = root.putObject("intent");
        intent.put("role", data.intent().role());
        intent.put("city", data.intent().city());
        intent.put("availability", data.intent() == null ? "" : nullToEmpty(data.intent().availability()));

        var education = mapper.createArrayNode();
        for (ResumeData.Education item : data.education()) {
            ObjectNode node = education.addObject();
            node.put("school", item.school());
            node.put("degree", item.degree());
            node.put("major", item.major());
            node.put("start", item.start());
            node.put("end", item.end());
            array(node, "highlights", item.highlights());
        }
        root.set("education", education);

        var work = mapper.createArrayNode();
        for (ResumeData.Work item : data.work()) {
            ObjectNode node = work.addObject();
            node.put("company", item.company());
            node.put("title", item.title());
            node.put("start", item.start());
            node.put("end", item.end());
            node.put("summary", item.summary());
            array(node, "bullets", item.bullets());
        }
        root.set("work", work);

        var projects = mapper.createArrayNode();
        for (ResumeData.Project item : data.projects()) {
            ObjectNode node = projects.addObject();
            node.put("name", item.name());
            node.put("role", item.role());
            node.put("period", item.period());
            array(node, "bullets", item.bullets());
        }
        root.set("projects", projects);

        var skills = mapper.createArrayNode();
        for (ResumeData.SkillGroup item : data.skills()) {
            ObjectNode node = skills.addObject();
            node.put("group", item.group());
            array(node, "items", item.items());
        }
        root.set("skills", skills);

        var awards = mapper.createArrayNode();
        for (ResumeData.Award item : data.awards()) {
            ObjectNode node = awards.addObject();
            node.put("name", item.name());
            node.put("issuer", item.issuer());
            node.put("date", item.date());
        }
        root.set("awards", awards);

        root.put("selfEvaluation", data.selfEvaluation());
        return root.toString();
    }

    // ── 读取 ────────────────────────────────────────────────────────

    private ResumeData read(ObjectNode root, Counters counters, String normalizedSource, boolean verifyQuotes) {
        ObjectNode basicsNode = object(root, "basics");
        ResumeData.Basics basics = new ResumeData.Basics(
                text(basicsNode, "name", counters),
                text(basicsNode, "phone", counters),
                text(basicsNode, "email", counters),
                text(basicsNode, "city", counters),
                strings(basicsNode, "links", counters));

        ObjectNode intentNode = object(root, "intent");
        ResumeData.Intent intent = new ResumeData.Intent(
                text(intentNode, "role", counters),
                text(intentNode, "city", counters),
                text(intentNode, "availability", counters));

        List<ResumeData.Education> education = new ArrayList<>();
        for (JsonNode node : arrayOf(root, "education", counters)) {
            if (verifyQuotes && !quoteVerified(node, normalizedSource, counters)) {
                continue;
            }
            education.add(new ResumeData.Education(
                    text(node, "school", counters), text(node, "degree", counters),
                    text(node, "major", counters), text(node, "start", counters),
                    text(node, "end", counters), strings(node, "highlights", counters)));
        }

        List<ResumeData.Work> work = new ArrayList<>();
        for (JsonNode node : arrayOf(root, "work", counters)) {
            if (verifyQuotes && !quoteVerified(node, normalizedSource, counters)) {
                continue;
            }
            work.add(new ResumeData.Work(
                    text(node, "company", counters), text(node, "title", counters),
                    text(node, "start", counters), text(node, "end", counters),
                    text(node, "summary", counters), strings(node, "bullets", counters)));
        }

        List<ResumeData.Project> projects = new ArrayList<>();
        for (JsonNode node : arrayOf(root, "projects", counters)) {
            if (verifyQuotes && !quoteVerified(node, normalizedSource, counters)) {
                continue;
            }
            projects.add(new ResumeData.Project(
                    text(node, "name", counters), text(node, "role", counters),
                    text(node, "period", counters), strings(node, "bullets", counters)));
        }

        List<ResumeData.SkillGroup> skills = new ArrayList<>();
        for (JsonNode node : arrayOf(root, "skills", counters)) {
            skills.add(new ResumeData.SkillGroup(
                    text(node, "group", counters), strings(node, "items", counters)));
        }

        List<ResumeData.Award> awards = new ArrayList<>();
        for (JsonNode node : arrayOf(root, "awards", counters)) {
            awards.add(new ResumeData.Award(
                    text(node, "name", counters), text(node, "issuer", counters),
                    text(node, "date", counters)));
        }

        String selfEvaluation = text(root, "selfEvaluation", counters);
        return new ResumeData(basics, intent, education, work, projects, skills, awards, selfEvaluation);
    }

    /**
     * 引证校验：{@code quote} 归一化后必须是原文归一化文本的子串。
     *
     * <p>缺 {@code quote} 时**放行**：不是所有条目都能被干净地引一句（模型给的是"这一段的整体
     * 理解"）。但一旦给了引证却对不上，就说明它在编——那种情况必须丢。
     */
    private boolean quoteVerified(JsonNode node, String normalizedSource, Counters counters) {
        String quote = node.hasNonNull("quote") ? node.get("quote").asText() : "";
        if (quote.isBlank()) {
            return true;
        }
        String normalizedQuote = TextNormalizer.normalize(quote);
        if (normalizedQuote.isEmpty() || normalizedSource.contains(normalizedQuote)) {
            return true;
        }
        counters.dropped++;
        return false;
    }

    // ── 基础读取 ─────────────────────────────────────────────────────

    private ObjectNode readObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw failure("模型没有返回任何内容");
        }
        try {
            JsonNode node = mapper.readTree(stripFence(raw));
            if (node == null || !node.isObject()) {
                throw failure("模型返回的不是一个 JSON 对象");
            }
            return (ObjectNode) node;
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure("模型返回的内容不是合法 JSON");
        }
    }

        private static String stripFence(String raw) {
        return ModelJson.stripFence(raw);
    }

    private static ObjectNode object(ObjectNode parent, String field) {
        JsonNode node = parent == null ? null : parent.get(field);
        return node instanceof ObjectNode object ? object : null;
    }

    /** 字符串字段：非字符串当空，超长截断并计数。 */
    private static String text(JsonNode parent, String field, Counters counters) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (node == null || node.isNull() || !node.isValueNode()) {
            return "";
        }
        String value = node.asText().trim();
        if (value.length() > MAX_TEXT) {
            counters.truncatedFields++;
            return value.substring(0, MAX_TEXT);
        }
        return value;
    }

    /** 字符串数组：非数组当空；元素非字符串丢弃；上限 {@value #MAX_BULLETS}。 */
    private static List<String> strings(JsonNode parent, String field, Counters counters) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : node) {
            if (values.size() >= MAX_BULLETS) {
                counters.truncatedFields++;
                break;
            }
            if (!element.isValueNode()) {
                continue;
            }
            String value = element.asText().trim();
            if (!value.isEmpty()) {
                values.add(value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) : value);
            }
        }
        return values;
    }

    /** 对象数组：非数组当空；元素非对象丢弃；上限 {@value #MAX_ITEMS}。 */
    private static List<JsonNode> arrayOf(ObjectNode parent, String field, Counters counters) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<JsonNode> values = new ArrayList<>();
        for (JsonNode element : node) {
            if (values.size() >= MAX_ITEMS) {
                counters.truncatedFields++;
                break;
            }
            if (element.isObject()) {
                values.add(element);
            }
        }
        return values;
    }

    private void array(ObjectNode parent, String field, List<String> values) {
        var node = parent.putArray(field);
        values.forEach(node::add);
    }

    private static BizException failure(String reason) {
        return new BizException(ErrorCode.INTERNAL, "没有拿到可用的简历解析结果（" + reason + "）");
    }

    /** 一次解析里的两个计数。可变、只在本类内部用。 */
    private static final class Counters {
        private int dropped;
        private int truncatedFields;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
