package io.github.xiaou61.sandbox.internal.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.sandbox.DatasetStorage;
import io.github.xiaou61.sandbox.SqlDatasetGeneratorPort;
import io.github.xiaou61.sandbox.SqlQuestionPort;
import io.github.xiaou61.sandbox.internal.audit.SandboxQuotaService;
import io.github.xiaou61.sandbox.internal.run.CodeRun;
import io.github.xiaou61.sandbox.internal.run.CodeRunner;
import io.github.xiaou61.sandbox.internal.run.RunSlot;
import io.github.xiaou61.sandbox.internal.run.SandboxProperties;
import io.github.xiaou61.sandbox.internal.run.SqlDatasets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * AI 出题与造数据（WORK-035 T-12 / T-13）。
 *
 * <p>**最该守的一条：期望结果集来自真跑，不是模型说的。**
 *
 * <p>如果实现偷懒（把模型给的"预期结果"直接回给前端），这里所有测试都能过，
 * 而线上表现是"用户写对了判错"——那种错没人会怀疑是 AI 编的。
 *
 * <p>第二条：参考解跑不通时必须**拒绝出题**，而不是给一道判不了的题。
 */
class SqlAiServiceTest {

    private static final long USER = 7L;
    private static final String DATASET = "orders-public";

    /** 假的执行器：按预设返回，不碰 Docker。 */
    private static CodeRunner runner(CodeRun.Result result) {
        return new CodeRunner() {
            @Override
            public CodeRun.Result run(CodeRun.Request request) {
                return result;
            }

            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public String unavailableReason() {
                return null;
            }

            @Override
            public CodeRun.TraceResult trace(String code, String stdin, String script) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static CodeRun.Result result(List<String> columns, List<List<String>> rows, int exitCode) {
        return new CodeRun.Result("", exitCode == 0 ? "" : "boom", exitCode, 1, false, false, false,
                exitCode == 0 ? List.of(new CodeRun.Table(0, columns, rows)) : List.of());
    }

    private static SqlDatasets datasets() {
        SqlDatasetMapper mapper = (SqlDatasetMapper) java.lang.reflect.Proxy.newProxyInstance(
                SqlDatasetMapper.class.getClassLoader(),
                new Class<?>[] {SqlDatasetMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByKey" -> Optional.empty();
                    case "listVisible" -> List.of();
                    case "toString" -> "FakeMapper";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        DatasetStorage storage = new DatasetStorage() {
            @Override
            public Optional<byte[]> read(String storageId) {
                return Optional.of("CREATE TABLE t (id INT);".getBytes());
            }

            @Override
            public String put(byte[] content, String contentType) {
                return "storage-1";
            }
        };
        return new SqlDatasets(mapper, storage);
    }

    /** 出题端口：固定返回一道题。 */
    private static SqlQuestionPort questionPort(String referenceSql) {
        return new SqlQuestionPort() {
            @Override
            public Result generate(Request request) {
                return new Result(
                        List.of(new Question("列出所有 id", referenceSql, "只要 id 列，不排序", "讲解")),
                        null, "v1");
            }
        };
    }

    /** 造数据端口：固定返回给定 SQL。 */
    private static SqlDatasetGeneratorPort generatorPort(String sql, String name) {
        return new SqlDatasetGeneratorPort() {
            @Override
            public Result generate(Request request) {
                return new Result(sql, name, null, "v1");
            }
        };
    }

    private static SqlAiService service(CodeRunner runner, SqlQuestionPort questions,
                                        SqlDatasetGeneratorPort generators,
                                        SqlDatasetUploadService uploads) {
        SandboxQuotaService quota = mock(SandboxQuotaService.class);
        RunSlot slots = new RunSlot(new SandboxProperties());
        return new SqlAiService(questions, generators, datasets(), uploads, runner, quota, slots);
    }

    private static SqlDatasetUploadService uploads() {
        return mock(SqlDatasetUploadService.class);
    }

    /**
     * **期望结果集来自真跑**：执行器回什么，题目带的就是什么。
     *
     * <p>模型说的"预期 3 行"没有被用到——这是这个功能唯一可信的地方。
     */
    @Test
    void 期望结果集来自实际执行() {
        SqlAiService service = service(
                runner(result(List.of("id"), List.of(List.of("1"), List.of("2")), 0)),
                questionPort("SELECT id FROM t"),
                generatorPort(null, null),
                uploads());

        SqlAiService.GeneratedQuestion question = service.generateQuestion(USER, DATASET, null);

        assertThat(question.stem()).isEqualTo("列出所有 id");
        assertThat(question.criteria()).contains("id");
        assertThat(question.columns()).containsExactly("id");
        assertThat(question.rows()).hasSize(2);
    }

    /**
     * **参考解跑不通时必须拒绝出题**。
     *
     * <p>不拒的话会给一道判不了的题——用户写对了也判错，而他不会怀疑是题错了。
     */
    @Test
    void 参考解跑不通时拒绝出题() {
        SqlAiService service = service(
                runner(result(List.of(), List.of(), 1)),
                questionPort("SELECT bad FROM t"),
                generatorPort(null, null),
                uploads());

        assertThatThrownBy(() -> service.generateQuestion(USER, DATASET, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("跑不通");
    }

    /** AI 没出成题（没凭据 / 上游超时）时给可读错误，不是空题。 */
    @Test
    void 模型没出成题时报错() {
        SqlQuestionPort empty = new SqlQuestionPort() {
            @Override
            public Result generate(Request request) {
                return new Result(List.of(), null, "v1");
            }
        };
        SqlAiService service = service(
                runner(result(List.of("id"), List.of(List.of("1")), 0)),
                empty, generatorPort(null, null), uploads());

        assertThatThrownBy(() -> service.generateQuestion(USER, DATASET, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有出题成功");
    }

    /** 未知数据集 + 无权访问同一句文案（区分了就给出可枚举的 oracle）。 */
    @Test
    void 未知数据集返回未找到() {
        SqlAiService service = service(
                runner(result(List.of("id"), List.of(List.of("1")), 0)),
                questionPort("SELECT id FROM t"),
                generatorPort(null, null),
                uploads());

        assertThatThrownBy(() -> service.generateQuestion(USER, "no-such", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有这个数据集");
    }

    /** 参考解返回行数过多时拒绝：那说明题的范围太大，不是一道好题。 */
    @Test
    void 参考解返回行数过多时拒绝() {
        List<List<String>> many = java.util.stream.IntStream.range(0, 500)
                .mapToObj(index -> List.of(String.valueOf(index)))
                .toList();
        SqlAiService service = service(
                runner(result(List.of("id"), many, 0)),
                questionPort("SELECT id FROM t"),
                generatorPort(null, null),
                uploads());

        assertThatThrownBy(() -> service.generateQuestion(USER, DATASET, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("题目范围太大");
    }

    /**
     * 生成数据集走**上传的同一套检查**——这里断言它确实调了 upload，
     * 而不是自己另写一条更宽松的保存路径。
     */
    @Test
    void 生成的数据集走上传流程() {
        SqlDatasetUploadService uploads = uploads();
        when(uploads.upload(anyLong(), anyString(), anyString(), anyString())).thenReturn("new-key");
        SqlAiService service = service(
                runner(result(List.of("id"), List.of(List.of("1")), 0)),
                questionPort("SELECT id FROM t"),
                generatorPort("CREATE TABLE t (id INT);", "小库"),
                uploads);

        String key = service.generateDataset(USER, "随便", "private");

        assertThat(key).isEqualTo("new-key");
        // 白名单、干跑、抓结构都在 upload 里——复用而不是另开一条路
        verify(uploads).upload(eq(USER), eq("小库"), eq("CREATE TABLE t (id INT);"), eq("private"));
    }

    /** 生成失败（没有 SQL）时给可读错误，且**不写任何东西**。 */
    @Test
    void 模型没生成数据集时报错且不写库() {
        SqlDatasetUploadService uploads = uploads();
        SqlAiService service = service(
                runner(result(List.of("id"), List.of(List.of("1")), 0)),
                questionPort("SELECT id FROM t"),
                generatorPort(null, null),
                uploads);

        assertThatThrownBy(() -> service.generateDataset(USER, "随便", "private"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有生成数据集");
        verify(uploads, org.mockito.Mockito.never()).upload(anyLong(), anyString(), anyString(), anyString());
    }

    /** WORK-065：向导只要 SQL，不落库；保存仍由用户在页面上点。 */
    @Test
    void 只生成不保存() {
        SqlDatasetUploadService uploads = uploads();
        SqlAiService service = service(
                runner(result(List.of("id"), List.of(List.of("1")), 0)),
                questionPort("SELECT id FROM t"),
                generatorPort("CREATE TABLE hotels (id INT);", null),
                uploads);

        SqlAiService.GeneratedDraft draft = service.draftDataset("造一个酒店库");

        assertThat(draft.name()).isEqualTo("AI 生成的数据集");
        assertThat(draft.sql()).contains("CREATE TABLE hotels");
        org.mockito.Mockito.verifyNoInteractions(uploads);
    }
}
