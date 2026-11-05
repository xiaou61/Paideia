package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.platform.ToolCatalog;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolQuery;
import io.github.xiaou61.platform.ToolScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 工具治理（WORK-074 TASK-B1，B 线）。
 *
 * <p>用**真实的** {@link ToolCatalog}（Spring 收集全部 {@code PaideiaTool} 装配）核对全部工具的治理不变量，
 * 并把"对外能力清单"生成到 {@code docs/architecture/tool-inventory.md}——目录是唯一真源，静态解析源码
 * 会与运行时注册漂移，所以清单只能从运行时快照生成。
 *
 * <p>无副作用：只读目录与描述符，不调用任何工具、不写业务库。
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class ToolGovernanceTest {

    @Autowired
    ToolCatalog catalog;

    @Test
    @DisplayName("每个工具都被治理：名字前缀/scope/描述一致，名字唯一")
    void everyToolIsGoverned() {
        List<ToolDescriptor> all = catalog.snapshot();
        assertThat(all).as("工具目录不应为空").isNotEmpty();
        for (ToolDescriptor d : all) {
            assertThat(d.name()).as("工具名 %s 必须以工具组前缀开头", d.name())
                    .startsWith(d.toolset() + "_");
            assertThat(d.scope()).as("工具 %s 必须有 scope", d.name()).isNotNull();
            assertThat(d.description()).as("工具 %s 必须有描述", d.name()).isNotBlank();
            if (d.readOnly()) {
                assertThat(d.scope()).as("只读工具 %s 不应要求 WRITE scope", d.name())
                        .isNotEqualTo(ToolScope.WRITE);
            }
        }
        long distinct = all.stream().map(ToolDescriptor::name).distinct().count();
        assertThat(distinct).as("工具名必须唯一").isEqualTo(all.size());
    }

    @Test
    @DisplayName("只读视图里不出现写工具")
    void readOnlyViewExcludesWrites() {
        List<ToolDescriptor> readView =
                catalog.view(ToolQuery.view(Set.of(), false, null, Set.of(ToolScope.READ)));
        assertThat(readView).allMatch(ToolDescriptor::readOnly);
    }

    @Test
    @DisplayName("写视图（READ+WRITE scope）覆盖全部非 ADMIN 工具")
    void writeViewIncludesAllNonAdminTools() {
        List<String> visible = catalog
                .view(ToolQuery.view(Set.of(), true, null, Set.of(ToolScope.READ, ToolScope.WRITE)))
                .stream().map(ToolDescriptor::name).toList();
        List<String> expected = catalog.snapshot().stream()
                .filter(d -> d.scope() != ToolScope.ADMIN)
                .map(ToolDescriptor::name).toList();
        assertThat(visible).containsAll(expected);
    }

    @Test
    @DisplayName("生成对外能力清单 docs/architecture/tool-inventory.md")
    void writesInventoryDoc() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# MCP 工具清单（由 ToolGovernanceTest 生成，勿手改）\n\n");
        sb.append("来源：`ToolCatalog.snapshot()`（运行时唯一真源，确定性排序）。\n\n");
        sb.append("| 工具 | toolset | scope | readOnly | destructive | default |\n");
        sb.append("| --- | --- | --- | --- | --- | --- |\n");
        for (ToolDescriptor d : catalog.snapshot()) {
            sb.append(String.format("| `%s` | %s | %s | %s | %s | %s |%n",
                    d.name(), d.toolset(), d.scope().key(), d.readOnly(), d.destructive(), d.defaultGroup()));
        }
        Path repoRoot = Path.of("").toAbsolutePath();
        while (repoRoot != null && !Files.exists(repoRoot.resolve(".git"))) {
            repoRoot = repoRoot.getParent();
        }
        assertThat(repoRoot).as("无法定位仓库根").isNotNull();
        Path out = repoRoot.resolve("docs").resolve("architecture").resolve("tool-inventory.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        assertThat(Files.readString(out)).contains("| 工具 |");
    }
}
