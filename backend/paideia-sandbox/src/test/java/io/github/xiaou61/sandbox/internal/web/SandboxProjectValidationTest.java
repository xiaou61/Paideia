package io.github.xiaou61.sandbox.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.sandbox.internal.run.CodeRun;
import io.github.xiaou61.sandbox.internal.run.SandboxProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 项目模式的边界校验（WORK-038 TASK-012）。
 *
 * <p>这里的每一条都是**安全边界**而不是体验问题：路径会被拼进容器的 shell 脚本里写文件，
 * 三个上限决定一次提交能把容器撑多大。只测纯校验逻辑，不起 Spring、不起 Docker。
 */
class SandboxProjectValidationTest {

    private static SandboxController controller(SandboxProperties properties) {
        // 校验只用到 properties，其余依赖传 null 即可——不启动任何容器
        return new SandboxController(properties, null, null, null, null, null);
    }

    private static SandboxController.RunRequest request(List<SandboxController.ProjectFile> files) {
        return new SandboxController.RunRequest("java", "", null, null, null, null, null, files);
    }

    private static List<SandboxController.ProjectFile> files(int count) {
        List<SandboxController.ProjectFile> list = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            list.add(new SandboxController.ProjectFile("F" + index + ".java", "class F" + index + " {}"));
        }
        return list;
    }

    @Test
    void 没有files时不是项目模式() {
        SandboxProperties properties = new SandboxProperties();
        assertThat(controller(properties).validateProject(request(null), "java")).isEmpty();
        assertThat(controller(properties).validateProject(request(List.of()), "java")).isEmpty();
    }

    @Test
    void 超过文件数上限被拒() {
        SandboxProperties properties = new SandboxProperties();
        assertThatThrownBy(() -> controller(properties)
                .validateProject(request(files(properties.getMaxProjectFiles() + 1)), "java"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最多提交");
    }

    @Test
    void 路径穿越与绝对路径都被拒() {
        SandboxProperties properties = new SandboxProperties();
        SandboxController controller = controller(properties);
        for (String path : List.of("../evil.java", "/etc/passwd", "a/../../b.java", "a b.java",
                "a;rm -rf /.java", "a$(id).java")) {
            assertThatThrownBy(() -> controller.validateProject(
                    request(List.of(new SandboxController.ProjectFile(path, "x"))), "java"))
                    .as("应当拒绝：%s", path)
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("文件名不合法");
        }
    }

    @Test
    void 单文件与总量超限被拒() {
        SandboxProperties properties = new SandboxProperties();
        SandboxController controller = controller(properties);

        String tooLong = "a".repeat(properties.getMaxProjectFileChars() + 1);
        assertThatThrownBy(() -> controller.validateProject(
                request(List.of(new SandboxController.ProjectFile("Main.java", tooLong))), "java"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超过");

        // 每个文件都合法，但加起来超总量
        String each = "a".repeat(properties.getMaxProjectFileChars());
        int count = properties.getMaxProjectTotalChars() / each.length() + 1;
        assertThat(count).isLessThanOrEqualTo(properties.getMaxProjectFiles());
        assertThatThrownBy(() -> controller.validateProject(request(filesOf(count, each)), "java"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("合计超过");
    }

    private static List<SandboxController.ProjectFile> filesOf(int count, String content) {
        List<SandboxController.ProjectFile> list = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            list.add(new SandboxController.ProjectFile("F" + index + ".java", content));
        }
        return list;
    }

    @Test
    void 合法输入原样通过且顺序不变() {
        SandboxProperties properties = new SandboxProperties();
        List<CodeRun.FileEntry> accepted = controller(properties).validateProject(
                request(List.of(
                        new SandboxController.ProjectFile("Main.java", "class Main {}"),
                        new SandboxController.ProjectFile("pkg/Helper.java", "package pkg; class Helper {}"))),
                "java");
        assertThat(accepted).extracting(CodeRun.FileEntry::path)
                .containsExactly("Main.java", "pkg/Helper.java");
    }

    @Test
    void sql不支持项目模式() {
        SandboxProperties properties = new SandboxProperties();
        assertThatThrownBy(() -> controller(properties)
                .validateProject(request(files(1)), "sql"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("SQL 不支持项目模式");
    }
}
