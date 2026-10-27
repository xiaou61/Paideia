package io.github.xiaou61.ai.internal.tools;

import io.github.xiaou61.ai.ResumeData;
import io.github.xiaou61.ai.internal.resume.ResumeDocumentService;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 简历域的外部工具（WORK-044 TASK-007）。
 *
 * <p>**只有读**。`analyze` / `propose` 刻意不做：它们各是一次真实上游调用、要扣积分、
 * 且需要 JD 文本与版本上下文。外部助手做这件事的正确方式是先用 `resume_get` 拿到内容、
 * 再在自己的模型里分析——我们不该替别人的模型付钱。
 *
 * <p>工具放在 `paideia-ai` 而不是新模块：`ResumeDocumentService` 在本模块的 `internal.resume`，
 * Spring Modulith 禁止跨模块访问 internal 包，硬要集中就得先给它开一层公开端口，
 * 而"集中"本身没有价值（工具已经按 toolset 分组了）。
 */
public final class ResumeTools {

    private static final String GROUP = "resume";

    private ResumeTools() {
    }

    /** 列出本人简历。 */
    @Component
    public static class ListDocuments implements PaideiaTool {

        private final ResumeDocumentService documents;

        ListDocuments(ResumeDocumentService documents) {
            this.documents = documents;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "resume_list", GROUP, "列出我的简历",
                    "列出**当前调用者自己**的简历（id、标题、是否默认、最后更新时间）。"
                            + "用户问「我有几份简历」「哪份是默认的」时用它；要读内容再用 resume_get。",
                    objectSchema(Map.of()),
                    null, true, false, true, ToolScope.READ, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            List<ResumeDocumentService.Summary> items = documents.list(context.userId());
            if (items.isEmpty()) {
                return ToolResult.ok("当前用户还没有任何简历。");
            }
            List<String> lines = new ArrayList<>();
            for (ResumeDocumentService.Summary item : items) {
                lines.add("id=" + item.id() + "  标题=" + item.title()
                        + (item.isDefault() ? "（默认）" : "")
                        + "  更新于=" + item.updatedAt());
            }
            return ToolResult.ok(String.join("\n", lines));
        }
    }

    /** 读一份简历的内容。 */
    @Component
    public static class Get implements PaideiaTool {

        private final ResumeDocumentService documents;

        Get(ResumeDocumentService documents) {
            this.documents = documents;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "resume_get", GROUP, "读取简历内容",
                    "按 id 读取**当前调用者自己**的一份简历全文（基本信息、求职意向、教育、工作、"
                            + "项目、技能、奖项、自我评价）。id 一般来自 resume_list。"
                            + "不存在或不是本人的都返回「没有找到」——两种情况刻意不区分。",
                    objectSchema(Map.of("documentId", Map.of("type", "integer", "description", "简历 id"))),
                    null, true, false, true, ToolScope.READ, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            Long id = longOf(arguments, "documentId");
            if (id == null) {
                return ToolResult.fail("缺少 documentId");
            }
            ResumeDocumentService.Detail detail;
            try {
                detail = documents.get(context.userId(), id);
            } catch (RuntimeException missing) {
                // 不存在与不是本人的走同一条（底层 require 抛 404）
                return ToolResult.fail("没有找到这份简历。");
            }
            return ToolResult.ok(render(detail));
        }
    }

    /**
     * 把结构化简历渲染成带小标题的纯文本。
     *
     * <p>**不返回原始 JSON**：模型读结构化 JSON 的 token 效率低于带小标题的文本，
     * 而这份数据的主要消费者就是模型。
     */
    static String render(ResumeDocumentService.Detail detail) {
        ResumeData data = detail.data();
        StringBuilder out = new StringBuilder();
        out.append("简历：").append(detail.title())
                .append(detail.isDefault() ? "（默认）" : "").append('\n');

        ResumeData.Basics basics = data.basics();
        if (basics != null && notBlank(basics.name(), basics.phone(), basics.email(), basics.city())) {
            out.append("\n## 基本信息\n");
            appendLine(out, "姓名", basics.name());
            appendLine(out, "电话", basics.phone());
            appendLine(out, "邮箱", basics.email());
            appendLine(out, "城市", basics.city());
            if (basics.links() != null && !basics.links().isEmpty()) {
                appendLine(out, "链接", String.join("、", basics.links()));
            }
        }

        ResumeData.Intent intent = data.intent();
        if (intent != null && notBlank(intent.role(), intent.city(), intent.availability())) {
            out.append("\n## 求职意向\n");
            appendLine(out, "岗位", intent.role());
            appendLine(out, "城市", intent.city());
            appendLine(out, "到岗时间", intent.availability());
        }

        if (data.education() != null && !data.education().isEmpty()) {
            out.append("\n## 教育经历\n");
            for (ResumeData.Education item : data.education()) {
                out.append("- ").append(joinNonBlank(" ", item.school(), item.degree(), item.major()))
                        .append(period(item.start(), item.end())).append('\n');
                appendBullets(out, item.highlights());
            }
        }

        if (data.work() != null && !data.work().isEmpty()) {
            out.append("\n## 工作经历\n");
            for (ResumeData.Work item : data.work()) {
                out.append("- ").append(joinNonBlank(" ", item.company(), item.title()))
                        .append(period(item.start(), item.end())).append('\n');
                appendLine(out, "  概述", item.summary());
                appendBullets(out, item.bullets());
            }
        }

        if (data.projects() != null && !data.projects().isEmpty()) {
            out.append("\n## 项目经历\n");
            for (ResumeData.Project item : data.projects()) {
                out.append("- ").append(joinNonBlank(" ", item.name(), item.role()))
                        .append(period(item.period(), null)).append('\n');
                appendBullets(out, item.bullets());
            }
        }

        if (data.skills() != null && !data.skills().isEmpty()) {
            out.append("\n## 技能\n");
            for (ResumeData.SkillGroup item : data.skills()) {
                out.append("- ").append(item.group() == null ? "" : item.group()).append("：")
                        .append(item.items() == null ? "" : String.join("、", item.items())).append('\n');
            }
        }

        if (data.awards() != null && !data.awards().isEmpty()) {
            out.append("\n## 奖项\n");
            for (ResumeData.Award item : data.awards()) {
                out.append("- ").append(joinNonBlank(" ", item.name(), item.issuer(), item.date())).append('\n');
            }
        }

        if (data.selfEvaluation() != null && !data.selfEvaluation().isBlank()) {
            out.append("\n## 自我评价\n").append(data.selfEvaluation().strip()).append('\n');
        }
        return out.toString();
    }

    private static void appendLine(StringBuilder out, String label, String value) {
        if (value != null && !value.isBlank()) {
            out.append(label).append("：").append(value.strip()).append('\n');
        }
    }

    private static void appendBullets(StringBuilder out, List<String> bullets) {
        if (bullets == null) {
            return;
        }
        for (String bullet : bullets) {
            if (bullet != null && !bullet.isBlank()) {
                out.append("  - ").append(bullet.strip()).append('\n');
            }
        }
    }

    private static String period(String start, String end) {
        String joined = joinNonBlank(" – ", start, end);
        return joined.isEmpty() ? "" : "（" + joined + "）";
    }

    private static String joinNonBlank(String separator, String... parts) {
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                kept.add(part.strip());
            }
        }
        return String.join(separator, kept);
    }

    private static boolean notBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    static Map<String, Object> objectSchema(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", List.of());
    }

    static Long longOf(Map<String, Object> arguments, String key) {
        Object raw = arguments == null ? null : arguments.get(key);
        return raw instanceof Number number ? number.longValue() : null;
    }
}
