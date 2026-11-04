package io.github.xiaou61.ai.internal.provider;

import io.github.xiaou61.ai.internal.prompt.PromptFences;
import java.util.List;

/**
 * 提示词。
 *
 * <p><b>{@link #VERSION} 是缓存键的一部分</b>：改提示词等于换键，旧结果不再被复用——
 * 否则用户会拿到"用旧规则生成、却以为按新规则来"的卡片。改文案就必须改版本号。
 *
 * <p>要结构化卡片但又不用框架的结构化输出通道：AgentScope 对 DeepSeek 的结构化输出走的是
 * "合成工具 + 强制工具调用"的回退路径，要靠我们自己构造工具 schema 并解析工具调用参数，
 * 面比收益大。这里改成**要求模型只回一段 JSON**，再由 {@link SummarySchemaValidator}
 * 逐字段校验、失败即判失败——校验器本来就不可少（原生结构化输出也可能字段漂移），
 * 于是少一层协议就是纯粹的净收益。这一点是对设计的偏离，已记入更新历史。
 */
public final class SummaryPrompt {

    /** 与缓存键绑定；改提示词必须同步改它。 */
    public static final String VERSION = "summary-card-v2";

    private SummaryPrompt() {
    }

    public static String system() {
        return """
                你是学习平台的内容精简助手。把用户给出的知识条目正文压缩成一张便于快速阅读的卡片。
                只输出一个 JSON 对象，不要输出解释、不要用 markdown 代码块包裹。
                JSON 结构：{"headline": "一句话总述", "sections": [{"heading": "小节标题", "body": "小节正文"}]}
                要求：
                1. headline 不超过 40 字，覆盖这条内容最核心的结论。
                2. sections 给 2 到 4 节；每节 heading 不超过 16 字，body 2 到 4 句。
                3. 只依据给定正文，不要补充正文里没有的事实，不要编造数字。
                4. 正文本身已经很短时，可以只给 1 节。
                """;
    }

    public static String user(String title, String body) {
        return PromptFences.DATA_ONLY + "标题：" + title + "\n\n正文：\n" + body;
    }

    /** 让模型把话说全，同时避免把整篇文章抄回来。 */
    static List<String> guardrails() {
        return List.of("不要输出 markdown 代码块", "不要输出 JSON 之外的内容");
    }
}
