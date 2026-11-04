package io.github.xiaou61.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 提示词版本号守卫（WORK-070）。
 *
 * <p><b>为什么扫源码而不反射</b>：提示词类大多是包私有的，反射要先把类名硬编码一遍——那就等于
 * 每加一个提示词都要记得改这个测试，守卫也就失去意义。扫源码能自动发现新文件。
 *
 * <p><b>为什么这条守卫值得存在</b>：版本号写进 `ai_runs.prompt_version`。改了提示词却没改版本，
 * 前后两批行长得一模一样，事后**再也分不出"涨的那两个点来自哪一版"**——这是补不回来的。
 */
class PromptVersionGuardTest {

    /** 注册表，不是提示词本体：版本号在它委托的各个提示词类里。 */
    private static final Set<String> NOT_PROMPTS = Set.of("HarnessPrompts.java");

    /** `<场景>-v<数字>`：场景用 kebab-case，禁止裸 `v1`——那样的键在统计页上分不清属于谁。 */
    private static final Pattern VERSION = Pattern.compile("VERSION\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*-v[0-9]+$");

    @Test
    @DisplayName("每个提示词类都有版本号，且命名是 <场景>-v<数字>")
    void everyPromptCarriesANamedVersion() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path file : promptFiles()) {
            String name = file.getFileName().toString();
            if (NOT_PROMPTS.contains(name)) {
                continue;
            }
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = VERSION.matcher(source);
            if (!matcher.find()) {
                problems.add(name + "：没有 VERSION 常量");
                continue;
            }
            String value = matcher.group(1);
            if (!NAME.matcher(value).matches()) {
                problems.add(name + "：VERSION \"" + value + "\" 不符合 <场景>-v<数字>");
            }
        }
        assertThat(problems).as("新加提示词忘了版本号，或命名走了样").isEmpty();
    }

    /** `src/main/java` 下所有提示词源码。surefire 的工作目录是模块根。 */
    private static List<Path> promptFiles() throws IOException {
        Path root = Path.of("src/main/java");
        assertThat(root).as("找不到 %s，测试工作目录不对", root.toAbsolutePath()).isDirectory();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.getFileName().toString().endsWith("Prompt.java")
                            || path.getFileName().toString().endsWith("Prompts.java"))
                    .toList();
        }
    }
}
