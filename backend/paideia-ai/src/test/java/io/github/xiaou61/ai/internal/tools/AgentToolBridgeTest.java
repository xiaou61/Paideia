package io.github.xiaou61.ai.internal.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.model.ToolSchema;
import io.github.xiaou61.ai.Harness;
import io.github.xiaou61.ai.NoteDraft;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolCatalog;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 内部桥接的三件事：阈值以下不出现 find_tools、命中后工具能进下一轮、未知工具不炸。
 */
class AgentToolBridgeTest {

    private static final Map<String, Object> OBJECT_SCHEMA = Map.of("type", "object", "properties", Map.of());

    @Test
    void noFindToolBelowThreshold() {
        AgentToolBridge bridge = bridge(List.of(
                stub("knowledge_search", "knowledge", true),
                stub("note_list", "note", true)), 12);

        assertThat(bridge.discoveryEnabled()).isFalse();
        assertThat(bridge.discoverySchemas()).isEmpty();
    }

    @Test
    void findToolAppearsAboveThreshold() {
        List<PaideiaTool> many = new ArrayList<>();
        for (int index = 0; index < 15; index++) {
            many.add(stub("knowledge_tool" + index, "knowledge", true));
        }
        AgentToolBridge bridge = bridge(many, 12);

        assertThat(bridge.discoveryEnabled()).isTrue();
        assertThat(bridge.discoverySchemas()).extracting(ToolSchema::getName)
                .containsExactly(AgentToolBridge.FIND_TOOLS);
    }

    @Test
    void coreOnlyExposesReadToolsOfThatHarness() {
        AgentToolBridge bridge = bridge(List.of(
                stub("knowledge_search", "knowledge", true),
                stub("assessment_mark_entry", "assessment", false),
                stub("note_create", "note", false)), 12);

        assertThat(bridge.core(Harness.ASSISTANT)).extracting(ToolSchema::getName)
                .containsExactly("knowledge_search");
        assertThat(bridge.core(Harness.REVIEW)).isEmpty();
    }

    @Test
    void discoveredToolJoinsNextRound() {
        List<PaideiaTool> many = new ArrayList<>();
        for (int index = 0; index < 15; index++) {
            many.add(stub("knowledge_tool" + index, "knowledge", true));
        }
        AgentToolBridge bridge = bridge(many, 12);

        AgentToolBridge.Outcome discovery = bridge.run(7L, Harness.ASSISTANT, AgentToolBridge.FIND_TOOLS,
                Map.of("query", "knowledge"));

        assertThat(discovery.error()).isFalse();
        assertThat(discovery.discovered()).isNotEmpty();

        List<ToolSchema> merged = bridge.concat(bridge.core(Harness.ASSISTANT), discovery.discovered());
        assertThat(merged).extracting(ToolSchema::getName)
                .containsAll(discovery.discovered());
    }

    @Test
    void unrelatedQuerySaysSoWithoutThrowing() {
        List<PaideiaTool> many = new ArrayList<>();
        for (int index = 0; index < 15; index++) {
            many.add(stub("knowledge_tool" + index, "knowledge", true));
        }
        AgentToolBridge bridge = bridge(many, 12);

        AgentToolBridge.Outcome result = bridge.run(7L, Harness.ASSISTANT, AgentToolBridge.FIND_TOOLS,
                Map.of("query", "区块链量子计算"));

        assertThat(result.error()).isFalse();
        assertThat(result.text()).contains("没有匹配");
        assertThat(result.discovered()).isEmpty();
    }

    @Test
    void findToolOnlySearchesToolsetsVisibleToThatHarness() {
        List<PaideiaTool> many = new ArrayList<>();
        for (int index = 0; index < 15; index++) {
            many.add(stub("note_tool" + index, "note", true));
        }
        AgentToolBridge bridge = bridge(many, 12);

        // REVIEW 没有任何工具组：即便目录里有 note 工具也不该被搜到
        AgentToolBridge.Outcome result = bridge.run(7L, Harness.REVIEW, AgentToolBridge.FIND_TOOLS,
                Map.of("query", "note"));

        assertThat(result.discovered()).isEmpty();
    }

    @Test
    void writeToolCannotBeCalledByNameEvenOutOfView() {
        // 只按名字找会绕过 core() 的只读过滤：模型拼出 note_create 就能写库。
        AgentToolBridge bridge = bridge(List.of(
                stub("knowledge_search", "knowledge", true),
                stub("note_create", "note", false)), 12);

        AgentToolBridge.Outcome blocked = bridge.run(7L, Harness.ASSISTANT, "note_create", Map.of());

        assertThat(blocked.error()).isTrue();
        assertThat(blocked.text()).contains("没有名为");
    }

    @Test
    void unknownToolReturnsErrorOutcome() {        AgentToolBridge bridge = bridge(List.of(stub("knowledge_search", "knowledge", true)), 12);

        AgentToolBridge.Outcome result = bridge.run(7L, Harness.ASSISTANT, "delete_everything", Map.of());

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("没有名为");
    }

    @Test
    void draftNoteProducesDraftAndWritesNothing() {
        AgentToolBridge bridge = bridge(List.of(new DraftNoteTool()), 12);

        AgentToolBridge.Outcome result = bridge.run(7L, Harness.ASSISTANT, "assistant_draft_note",
                Map.of("title", "标题", "body", "正文"));

        assertThat(result.error()).isFalse();
        assertThat(result.draft()).isEqualTo(new NoteDraft("标题", "正文"));
    }

    @Test
    void draftNoteFallsBackToDefaultTitle() {
        AgentToolBridge bridge = bridge(List.of(new DraftNoteTool()), 12);

        AgentToolBridge.Outcome result = bridge.run(7L, Harness.ASSISTANT, "assistant_draft_note",
                Map.of("body", "只有正文"));

        assertThat(result.draft()).isNotNull();
        assertThat(result.draft().title()).isEqualTo(DraftNoteTool.DEFAULT_TITLE);
    }

    // --------------------------------------------------------------- fixtures

    private static AgentToolBridge bridge(List<PaideiaTool> tools, int eagerLimit) {
        return new AgentToolBridge(new ToolCatalog(tools), eagerLimit);
    }

    private static PaideiaTool stub(String name, String toolset, boolean readOnly) {
        ToolDescriptor descriptor = new ToolDescriptor(name, toolset, "标题 " + name,
                "描述 " + name + " 用于检索", OBJECT_SCHEMA, null, readOnly, false, true,
                readOnly ? ToolScope.READ : ToolScope.WRITE, true);
        return new PaideiaTool() {
            @Override
            public ToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ToolResult call(ToolContext context, Map<String, Object> arguments) {
                return ToolResult.ok("结果 " + name);
            }
        };
    }

    @Test
    void plannerSeesOnlyReadOnlyGroups() {
        // 规划场景与学习记录页配套：只给只读组，生成类工具单独放 planner 组，
        // 免得通用助手（ASSISTANT）也能看到"生成数据集"这种会写库的入口。
        assertThat(AgentToolBridge.toolsetsOf(Harness.PLANNER))
                .containsExactlyInAnyOrder("knowledge", "learning", "assessment", "assistant", "planner");
        assertThat(AgentToolBridge.toolsetsOf(Harness.ASSISTANT)).doesNotContain("planner");
    }
}
