package io.github.xiaou61.ai;

import io.github.xiaou61.platform.BizException;
import java.util.List;

/**
 * 对话端口：一轮多轮对话，产出**逐段**的思考与正文。
 *
 * <p><b>签名里没有任何框架类型</b>（D-09 的要求，与 {@link EntrySummarizer} 同一纪律）：
 * 换模型实现、升级 AgentScope 都不动调用方。实现放在 {@code internal.conversation}。
 *
 * <p>端口只负责"给我上下文与凭据，我把这一轮的增量吐给你"：**不落库、不管会话归属、
 * 不做限流**——那些是调用方的事。因此它可以被桩替换，测试里不碰网络。
 *
 * <p>为什么用回调而不是返回一个流对象：本项目跑在 Spring MVC 上（不是 WebFlux），
 * 调用方是 {@code SseEmitter}；回调让"消费上游流"这件事留在实现内部，
 * 端口不必泄漏任何响应式类型。
 *
 * <p>失败语义：上游不可用、被拒、返回空内容，一律走 {@link Listener#onFailed(BizException)}，
 * **且不回显上游原始报文**。调用方不得把失败结果当作正常回复落库。
 */
public interface AssistantConversation {

    /**
     * @param request 这一轮的上下文与用户输入
     * @param access  本次调用用的凭据（明文只在这次调用期间存在，与 {@link EntrySummarizer} 同一纪律）
     * @param run     这一轮的观测作用域（WORK-022）。实现把每次上游调用、每次工具调用各开一步；
     *                **它只记不判**：采集关掉时拿到的是空实现，七个调用点一字不改
     * @param listener 增量回调
     */
    void stream(Request request, ModelAccess access, RunRecorder.RunScope run, Listener listener);

    /**
     * @param userId         当前调用者的账号 id。**工具读数据只认它**——模型无法提供或改写这个值
     * @param history        之前的对话（旧 → 新），供模型保持多轮上下文
     * @param userMessage    本轮用户输入
     * @param entryTitle     可选：当前条目标题（为空表示这轮没有条目上下文）
     * @param entryBody      可选：当前条目正文；超长由实现截断
     * @param memorySnippets 召回的记忆（本人私有），每条一行；为空表示这次没有可用记忆
     * @param harness        这一轮用哪套**公共行为**（WORK-010）。`null` 视为 {@link Harness#ASSISTANT}；
     *                       面试页显式给 {@link Harness#INTERVIEW}，点评给 {@link Harness#REVIEW}。
     *                       **它与记忆/历史/凭据无关**——那些是私有数据，按用户取
     */
    record Request(long userId,
                   List<ConversationTurn> history,
                   String userMessage,
                   String entryTitle,
                   String entryBody,
                   /** 本人这一站的学习记录摘要（WORK-020）；只有 TUTOR 行为会注入。空串表示没有记录。 */
                   String learningSummary,
                   List<String> memorySnippets,
                   Harness harness,
                   /**
                    * 页面场景（WORK-035 T-15）：前端已截断的一段文本，如 SQL 工作台的草稿与最近报错。
                    *
                    * <p>**只进提示词，不落库**——会话历史里不该出现"某次页面上写了什么"。
                    * `null` 表示当前页面没有场景（绝大多数页面）。
                    */
                   String pageContext,
                   /** RAG 检索上下文（WORK-072）；只进提示词，不落库。 */
                   String knowledgeContext) {

        public Request {
            history = history == null ? List.of() : List.copyOf(history);
            memorySnippets = memorySnippets == null ? List.of() : List.copyOf(memorySnippets);
            harness = harness == null ? Harness.ASSISTANT : harness;
        }

        /** 不带页面场景/RAG 的形态：既有调用点与测试用它。 */
        public Request(long userId, List<ConversationTurn> history, String userMessage,
                       String entryTitle, String entryBody, String learningSummary,
                       List<String> memorySnippets, Harness harness) {
            this(userId, history, userMessage, entryTitle, entryBody, learningSummary,
                    memorySnippets, harness, null, null);
        }

        /**
         * 兼容构造：只给前六项时用通用助手行为。
         *
         * <p>留着它是为了让既有调用方与不关心 harness 的测试不必都多写一个 `null`——
         * 而"没写就是通用助手"恰好就是默认语义。
         */
        public Request(long userId, List<ConversationTurn> history, String userMessage,
                       String entryTitle, String entryBody, List<String> memorySnippets) {
            this(userId, history, userMessage, entryTitle, entryBody, "", memorySnippets, Harness.ASSISTANT);
        }

        /** 有没有页面场景。实现据此决定是否把它放进提示词。 */
        public boolean hasPageContext() {
            return pageContext != null && !pageContext.isBlank();
        }

        /** 有没有 RAG 检索上下文。 */
        public boolean hasKnowledgeContext() {
            return knowledgeContext != null && !knowledgeContext.isBlank();
        }

        /** 有没有条目上下文。实现据此决定是否把正文放进提示词。 */
        public boolean hasEntryContext() {
            return entryBody != null && !entryBody.isBlank();
        }
    }

    /**
     * 增量回调。
     *
     * <p>两个内容回调**分开**是需求（AC-002）：思考过程要在界面里单独折叠，不能和正文混在一起。
     * {@code onCompleted} 与 {@code onFailed} 互斥，且各自只会被调用一次。
     */
    interface Listener {

        /** 思考过程的增量。模型不产出思考时一次都不会调用。 */
        void onThinking(String delta);

        /** 正文的增量。 */
        void onContent(String delta);

        /**
         * 助手产出了一条**笔记草稿**（不是笔记本身）。
         *
         * <p>由工具参数承载，界面据此显示"要不要存成笔记"；真正落库要等用户点击。
         * 一轮里可能出现多次（模型分几次提）。
         */
        void onDraft(NoteDraft draft);

        /**
         * 这一趟调用的**总**用量，在流终止时（成功或失败都）调用**一次**，且**先于**
         * {@link #onCompleted()} / {@link #onFailed(BizException)}。
         *
         * <p><b>为什么与结果分开</b>：用量和结果是两件事——调用可能失败，但已经烧掉的 token 是真的。
         * 分开之后，"要不要落库这条消息"与"要不要为这次调用扣费"成为两个独立决定，错误路径不必重复判断。
         *
         * <p><b>为什么是"总"量</b>：工具循环里每一轮都是一次真实的上游调用，这里给的是**各轮之和**
         * （WORK-009 T-01 修的就是"只报最后一轮、中间轮次全丢"那个漏记）。
         *
         * <p>可能为 {@code null}：整趟没有任何一轮返回用量时是这样，与"用量为 0"不是一回事。
         */
        void onUsage(TokenUsage usage);

        /** 正常结束。用量走 {@link #onUsage(TokenUsage)}，这里不再携带。 */
        void onCompleted();

        /** 失败。调用方据此给用户可见的结果，并**不**把这轮写进历史。 */
        void onFailed(BizException failure);
    }
}
