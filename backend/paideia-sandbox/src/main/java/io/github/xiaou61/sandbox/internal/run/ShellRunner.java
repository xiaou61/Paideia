package io.github.xiaou61.sandbox.internal.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Linux 判题练习场（WORK-056）：把"一段脚本 + 一道判定脚本"变成一次带结论的执行。
 *
 * <p><b>不自己起容器</b>：走已经加固好的 {@link CodeRunner}（项目模式），隔离参数只有一份。
 * 本类只做三件事——把三样东西拼成分帧文件、跑、把协议块翻成结论。
 *
 * <p><b>结论只看 judge 的退出码</b>，不看学习者的输出：`chmod 640 x` 的正确性在权限位上，
 * 不在它打印了什么。学习者脚本非 0 退出**不算失败**——很多题就是让他先看到"这条命令报错了"。
 *
 * <p><b>容器 stdout 只含协议块</b>：学习者的输出被 entry 脚本重定向进 `/tmp/.out`，
 * 所以他写 `echo '###RC'` 也伪造不了判定。
 */
@Component
public class ShellRunner {

    private static final String RC = "###RC";
    private static final String OUT = "###OUT";
    private static final String ERR = "###ERR";
    private static final String JUDGE = "###JUDGE";
    private static final String JUDGE_ERR = "###JUDGEERR";
    private static final String DIFF = "###DIFF";

    private final CodeRunner runner;
    private final SandboxProperties properties;

    ShellRunner(CodeRunner runner, SandboxProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    /** 题目声明的初始文件。路径已由控制器校验（相对、无 `..`）。 */
    public record Seed(String path, String content) {
    }

    /**
     * @param verdict `passed` / `failed` / `undetermined`（**存进接口与审计的稳定值**，不要改名）
     * @param reason  `undetermined` 时的人话原因；其它情况为 null
     */
    public record Result(String verdict, int runExit, int judgeExit, String stdout, String stderr,
                  String judgeStdout, String judgeStderr, String diff, long durationMs,
                  boolean truncated, String reason) {
    }

    /** 协议块解析结果。缺 `###RC` 时 {@link #parse} 返回 null。 */
    record Parsed(int runExit, int judgeExit, String stdout, String stderr,
                  String judgeStdout, String judgeStderr, String diff) {
    }

    public Result run(String script, String judge, List<Seed> seed, String caseId) {
        List<CodeRun.FileEntry> files = new ArrayList<>(2 + seed.size());
        // 两个脚本放工作区之外：它们不该出现在"你改了什么"的列表里
        files.add(new CodeRun.FileEntry("run.sh", script));
        files.add(new CodeRun.FileEntry("judge.sh", judge));
        for (Seed item : seed) {
            files.add(new CodeRun.FileEntry("work/" + item.path(), item.content()));
        }

        CodeRun.Result raw = runner.run(new CodeRun.Request("shell", script, caseId, files));
        long seconds = properties.timeoutMsFor("shell") / 1000;
        if (raw.timedOut()) {
            return new Result("undetermined", -1, -1, raw.stdout(), raw.stderr(), "", "", "",
                    raw.durationMs(), raw.truncated(), "超时（超过 " + seconds + " 秒）：检查有没有死循环或等输入");
        }
        Parsed parsed = parse(raw.stdout());
        if (parsed == null) {
            String reason = raw.truncated()
                    ? "输出超过上限，判定信息被截断"
                    : "执行脚本没有返回结果（容器可能被强杀，或镜像启动失败）";
            return new Result("undetermined", -1, -1, raw.stdout(), raw.stderr(), "", "", "",
                    raw.durationMs(), raw.truncated(), reason);
        }
        String verdict = parsed.judgeExit() == 0 ? "passed" : "failed";
        return new Result(verdict, parsed.runExit(), parsed.judgeExit(), parsed.stdout(),
                parsed.stderr(), parsed.judgeStdout(), parsed.judgeStderr(), parsed.diff(),
                raw.durationMs(), raw.truncated(), null);
    }

    /**
     * 解析 entry 脚本产出的协议块。
     *
     * <p>按标记行切段，每段到下一个标记行为止。**缺 `###RC` 就返回 null**：它由 entry 在
     * 两段脚本都跑完之后才写，缺了说明容器没走到那一步（超时、被杀、镜像不对）。
     */
    static Parsed parse(String output) {
        if (output == null || output.isEmpty()) {
            return null;
        }
        Map<String, StringBuilder> sections = new LinkedHashMap<>();
        String current = null;
        for (String line : output.split("\n", -1)) {
            String trimmed = line.trim();
            if (RC.equals(trimmed) || OUT.equals(trimmed) || ERR.equals(trimmed)
                    || JUDGE.equals(trimmed) || JUDGE_ERR.equals(trimmed) || DIFF.equals(trimmed)) {
                current = trimmed;
                sections.put(current, new StringBuilder());
                continue;
            }
            if (current != null) {
                sections.get(current).append(line).append('\n');
            }
        }
        StringBuilder rcSection = sections.get(RC);
        if (rcSection == null) {
            return null;
        }
        String[] codes = rcSection.toString().trim().split("\\s+");
        if (codes.length < 2) {
            return null;
        }
        Integer runExit = parseInt(codes[0]);
        Integer judgeExit = parseInt(codes[1]);
        if (runExit == null || judgeExit == null) {
            return null;
        }
        return new Parsed(runExit, judgeExit, section(sections, OUT), section(sections, ERR),
                section(sections, JUDGE), section(sections, JUDGE_ERR), section(sections, DIFF));
    }

    private static String section(Map<String, StringBuilder> sections, String key) {
        StringBuilder value = sections.get(key);
        if (value == null) {
            return "";
        }
        // head -c 之后 entry 会补一个换行；去掉尾部换行，前端展示时不再出现空行
        String text = value.toString();
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\n') {
            end--;
        }
        return text.substring(0, end);
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** 审计里存的语言标识。与围栏块的 `kind` 一致。 */
    static String language() {
        return "shell".toLowerCase(Locale.ROOT);
    }
}
