package io.github.xiaou61.ai;

/**
 * 一次对话的**公共行为**（WORK-010）。
 *
 * <p><b>一条对话 = 公共行为 × 私有数据</b>：harness 是前者（用什么提示词、能不能用工具），
 * 记忆/历史/凭据是后者（按用户取）。所以它叫"行为"，不叫"人"——**不是"每个场景一个 Agent"**，
 * 而是同一个助手在不同页面按这套行为说话。
 *
 * <p>一期三套：
 * <ul>
 *   <li>{@link #ASSISTANT}：通用学习助手，既有行为，**提示词一字不改**。</li>
 *   <li>{@link #INTERVIEW}：面试官。多轮追问，题源是知识库已发布条目。</li>
 *   <li>{@link #REVIEW}：点评官。**工具集为空**的一次独立调用——这同时绕开 DeepSeek
 *       "工具调用与结构化输出不能在同一次调用里并用"那条限制（那次本来就该不带工具）。</li>
 *   <li>{@link #TUTOR}：编程课导师（WORK-020）。按站点辅导：先要思路、四级提示、默认不给
 *       完整答案，并且能看到本人的学习记录。</li>
 * </ul>
 *
 * <p><b>选择规则</b>：`页面显式给的 harness` 优先；没给则用身份默认——一期身份默认**恒为**
 * {@link #ASSISTANT}。所以"不同身份的默认行为不同"在一期**不可观察**，这是登记的已知缺口，
 * 不是漏做（design §10）。
 *
 * <p>库里存的是 {@link #key()}（小写）。读到不认识的值时退回 {@link #ASSISTANT}：
 * 一个脏值不该让整段会话打不开，也不该让用户突然被当成面试者追问。
 */
public enum Harness {

    ASSISTANT("assistant"),
    INTERVIEW("interview"),
    REVIEW("review"),
    /** 编程课导师（WORK-020）：先要思路、分级提示、默认不给完整答案。 */
    TUTOR("tutor"),
    /** 学习规划（WORK-053）：看着学习记录快照先反问、再给具体建议；只能建议，不能替用户写。 */
    PLANNER("planner");

    private final String key;

    Harness(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** 库里/页面上来的字符串 → 枚举。null 与不认识的值都退回通用助手。 */
    public static Harness ofOrDefault(String raw) {
        if (raw == null) {
            return ASSISTANT;
        }
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        for (Harness harness : values()) {
            if (harness.key.equals(normalized)) {
                return harness;
            }
        }
        return ASSISTANT;
    }
}
