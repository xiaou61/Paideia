package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 跨模块契约守卫（WORK-074 TASK-004/005，C 期）。
 *
 * <p>**纯源码扫描**，不启 Spring、不连库——把"谁能发事件 / 谁不能 import 别人的 internal"这类
 * 架构约束变成构建期可执行的断言，防止它们在某次重构里悄悄退化。
 *
 * <p>扫的是 `backend/&lt;module&gt;/src/main` 的真实源码文本，因此不受"编译能过"的掩盖：
 * 跨模块 import 别人 internal 往往能编译通过（Maven 导出了全部包），只有这里能拦住。
 */
class CrossModuleContractTest {

    private static final Path REPO_ROOT = findRepoRoot();
    private static final Path BACKEND = REPO_ROOT.resolve("backend");

    private static Path findRepoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve(".git"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("无法定位仓库根");
        }
        return p;
    }

    private record SrcFile(String module, String rel, String content) {
    }

    private static List<SrcFile> mainSources() throws IOException {
        List<SrcFile> out = new ArrayList<>();
        if (!Files.isDirectory(BACKEND)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(BACKEND)) {
            List<Path> files = walk
                    .filter(x -> x.toString().endsWith(".java"))
                    .filter(x -> x.toString().replace('\\', '/').contains("/src/main/"))
                    .toList();
            for (Path f : files) {
                String rel = REPO_ROOT.relativize(f).toString().replace('\\', '/');
                String[] parts = rel.split("/");
                // backend/<module>/src/main/...
                String module = parts.length > 1 ? parts[1] : "";
                out.add(new SrcFile(module, rel, Files.readString(f, StandardCharsets.UTF_8)));
            }
        }
        return out;
    }

    @Test
    @DisplayName("跨模块事件只允许由拥有模块发布")
    void eventsPublishedOnlyByOwner() throws IOException {
        Map<String, Set<String>> owners = Map.of(
                "UserDeletedEvent", Set.of("paideia-account"),
                "EntryAssessmentChangedEvent", Set.of("paideia-knowledge"));
        Pattern pattern = Pattern.compile("new\\s+(UserDeletedEvent|EntryAssessmentChangedEvent)\\s*\\(");
        List<String> violations = new ArrayList<>();
        int seen = 0;
        for (SrcFile f : mainSources()) {
            Matcher m = pattern.matcher(f.content());
            while (m.find()) {
                seen++;
                String event = m.group(1);
                if (!owners.get(event).contains(f.module())) {
                    violations.add(event + " 在 " + f.rel() + "（模块 " + f.module()
                            + "）发布，只允许 " + owners.get(event));
                }
            }
        }
        assertThat(seen).as("应至少发现事件发布点（否则扫描路径失效）").isGreaterThan(0);
        assertThat(violations).as("事件只允许由拥有模块发布").isEmpty();
    }

    @Test
    @DisplayName("非 ai 模块的生产代码不得 import ai.internal")
    void noAiInternalImportsFromOtherModules() throws IOException {
        List<String> violations = new ArrayList<>();
        for (SrcFile f : mainSources()) {
            if ("paideia-ai".equals(f.module())) {
                continue;
            }
            if (f.content().contains("import io.github.xiaou61.ai.internal.")) {
                violations.add(f.rel());
            }
        }
        assertThat(violations).as("非 ai 模块的 src/main 不得 import io.github.xiaou61.ai.internal").isEmpty();
    }

    @Test
    @DisplayName("ScheduledTaskRecorder 只由 ops 模块实现")
    void scheduledTaskRecorderImplementedOnlyByOps() throws IOException {
        List<String> violations = new ArrayList<>();
        for (SrcFile f : mainSources()) {
            if (f.content().contains("implements ScheduledTaskRecorder") && !"paideia-ops".equals(f.module())) {
                violations.add(f.rel());
            }
        }
        assertThat(violations).as("ScheduledTaskRecorder 只应由 ops 实现").isEmpty();
    }
}
