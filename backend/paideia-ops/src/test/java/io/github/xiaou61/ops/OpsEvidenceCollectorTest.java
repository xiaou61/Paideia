package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.xiaou61.ai.OpsDiagnoser;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 诊断证据里的日志段（WORK-086）：窗口锚定、三段查询、签名聚合、预算与降级。
 *
 * <p>用 JDK 自带的 HttpServer 造 Loki / Prometheus / Tempo 的响应，不引 MockWebServer——
 * 这里要验证的是"查询怎么被组织、结果怎么被归约"，不是 HTTP 客户端本身。
 */
class OpsEvidenceCollectorTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> lokiQueries = new CopyOnWriteArrayList<>();
    /** changes() 返回的重启次数；测试可按需改写。 */
    private volatile double prometheusRestarts = 4;

    /** 每个 unit 的日志，按 unit 名区分；没有登记的 unit 返回空。 */
    private final List<String> restartLines = List.of(
            line("2026-11-19T14:16:59+08:00", "INFO", "Started PaideiaApplication in 28.417 seconds"),
            line("2026-11-19T14:20:46+08:00", "INFO", "Started PaideiaApplication in 27.912 seconds"),
            line("2026-11-19T14:23:47+08:00", "INFO", "Started PaideiaApplication in 29.501 seconds"),
            line("2026-11-19T14:26:30+08:00", "INFO", "Started PaideiaApplication in 31.002 seconds"));

    private final List<String> errorLines = List.of(
            line("2026-11-19T14:17:02+08:00", "ERROR", "Connection is not available, request timed out"),
            line("2026-11-19T14:17:09+08:00", "ERROR", "Connection is not available, request timed out"));

    private final List<String> contextLines = List.of(
            line("2026-11-19T14:17:05+08:00", "INFO", "HikariPool-1 - Shutdown initiated"));

    private final List<String> infoOnlyLines = List.of(
            line("2026-11-19T14:18:00+08:00", "INFO", "审核通过"),
            line("2026-11-19T14:19:00+08:00", "INFO", "知识库分类缓存重建"),
            line("2026-11-19T14:19:30+08:00", "INFO", "定时任务开始"));

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/loki/api/v1/query_range", this::loki);
        server.createContext("/api/v1/query", this::prometheus);
        server.createContext("/api/search", this::tempo);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String line(String at, String level, String message) {
        return at + " " + level + " 1 --- [main] io.github.xiaou61.ops.Probe : " + message;
    }

    private void loki(HttpExchange exchange) throws IOException {
        String query = URLDecoder.decode(param(exchange.getRequestURI().getRawQuery(), "query"), StandardCharsets.UTF_8);
        lokiQueries.add(exchange.getRequestURI().getRawQuery());
        List<String> body;
        if (query.contains("mysql.service")) {
            body = List.of();
        } else if (query.contains("(?i)(started|starting")) {
            body = restartLines;
        } else if (query.contains("(?i)(error|exception|failed|timeout)")) {
            body = "paideia".equals(unitOf(query)) ? errorLines : List.of();
        } else if (query.contains("\"context\"")) {
            body = contextLines;
        } else if (query.contains("laya.service")) {
            body = List.of();
        } else {
            body = concat(errorLines, restartLines, contextLines, infoOnlyLines);
        }
        int limit = Integer.parseInt(param(exchange.getRequestURI().getRawQuery(), "limit"));
        if (limit < body.size()) {
            body = body.subList(0, limit);
        }
        respond(exchange, 200, streams(body));
    }

    private static String unitOf(String query) {
        int at = query.indexOf("unit=\"");
        if (at < 0) {
            return "";
        }
        String rest = query.substring(at + 6);
        int end = rest.indexOf(".service\"");
        return end < 0 ? rest : rest.substring(0, end);
    }

    private void prometheus(HttpExchange exchange) throws IOException {
        String query = URLDecoder.decode(param(exchange.getRequestURI().getRawQuery(), "query"), StandardCharsets.UTF_8);
        String value = null;
        if (query.startsWith("changes(process_start_time_seconds")) {
            value = String.valueOf(prometheusRestarts);
        } else if (query.startsWith("time() - process_start_time_seconds")) {
            value = "4320";
        } else if (query.startsWith("increase(node_vmstat_oom_kill")) {
            value = "0";
        }
        if (value == null) {
            respond(exchange, 200, "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}");
            return;
        }
        respond(exchange, 200, "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                + "{\"metric\":{},\"value\":[1700000000.0,\"" + value + "\"]}]}}");
    }

    private void tempo(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "{\"traces\":[]}");
    }

    private static String streams(List<String> lines) {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i);
            // 时间戳由行首的 ISO 时间决定，同一行在不同响应里必须拿到同一个 nanos，
            // 否则去重会失效、计数会翻倍（测试本身也会失真）。
            long nanos = Instant.parse(text.substring(0, text.indexOf(' '))).toEpochMilli() * 1_000_000L;
            if (i > 0) {
                values.append(',');
            }
            values.append("[\"").append(nanos).append("\",\"")
                    .append(text.replace("\"", "\\\"")).append("\"]");
        }
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":["
                + "{\"stream\":{\"unit\":\"paideia.service\"},\"values\":[" + values + "]}]}}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String param(String rawQuery, String name) {
        if (rawQuery == null) {
            return "";
        }
        for (String pair : rawQuery.split("&")) {
            int at = pair.indexOf('=');
            if (at > 0 && pair.substring(0, at).equals(name)) {
                return pair.substring(at + 1);
            }
        }
        return "";
    }

    private static List<String> concat(List<String>... parts) {
        List<String> all = new ArrayList<>();
        for (List<String> part : parts) {
            all.addAll(part);
        }
        return all;
    }

    private OpsEvidenceCollector collector(List<String> units, int signatureTop, int maxEvidenceChars,
                                           boolean prometheusReachable) {
        OpsProperties properties = new OpsProperties();
        properties.setPrometheusUrl(baseUrl);
        properties.setLokiUrl(baseUrl);
        properties.setTempoUrl(baseUrl);
        properties.setQueryTimeoutMs(2000);
        properties.getDiagnosis().setUnits(units);
        properties.getDiagnosis().setSignatureTop(signatureTop);
        properties.getDiagnosis().setLogsPerUnit(50);
        properties.getDiagnosis().setMaxEvidenceChars(maxEvidenceChars);
        OpsSummaryService summary = mock(OpsSummaryService.class);
        when(summary.summary()).thenReturn(
                new OpsSummary("2026-11-19T14:30:00+08:00", prometheusReachable, List.of()));
        return new OpsEvidenceCollector(properties, summary,
                new PrometheusClient(baseUrl, 2000),
                new LokiClient(baseUrl, 2000),
                new TempoClient(baseUrl, 2000));
    }

    private static OpsEvidenceWindow window() {
        Instant end = Instant.parse("2026-11-19T06:30:00Z");
        return new OpsEvidenceWindow(end.minus(20, ChronoUnit.MINUTES), end, "事件 #17", false);
    }

    @Test
    @DisplayName("同一签名的多次重启与多次报错各聚成一行，带次数与首末时间")
    void aggregatesBySignatureWithCount() {
        OpsDiagnoser.Evidence evidence = collector(List.of("paideia"), 10, 16000, true)
                .collect("alert", "日志模板异常待处理", null, null, window());

        assertThat(evidence.logs())
                .contains("[paideia]")
                .contains("×4")
                .contains("×2")
                .contains("Started PaideiaApplication")
                .contains("Connection is not available");
    }

    @Test
    @DisplayName("日志查询锚定证据窗口，而不是从此刻往前一小时")
    void queriesUseEvidenceWindow() {
        OpsEvidenceWindow window = window();
        collector(List.of("paideia"), 10, 16000, true)
                .collect("alert", "日志模板异常待处理", null, null, window);

        assertThat(lokiQueries).isNotEmpty();
        assertThat(lokiQueries).allSatisfy(raw -> {
            String expectedStart = window.start().getEpochSecond() + "000000000";
            String expectedEnd = window.end().getEpochSecond() + "000000000";
            assertThat(param(raw, "start")).isEqualTo(expectedStart);
            assertThat(param(raw, "end")).isEqualTo(expectedEnd);
        });
    }

    @Test
    @DisplayName("每个单元都跑三段查询：异常、生命周期、上下文")
    void runsThreeSegmentsPerUnit() {
        collector(List.of("paideia"), 10, 16000, true)
                .collect("alert", "日志模板异常待处理", null, null, window());

        assertThat(lokiQueries).anySatisfy(raw ->
                assertThat(URLDecoder.decode(raw, StandardCharsets.UTF_8)).contains("error|exception"));
        assertThat(lokiQueries).anySatisfy(raw ->
                assertThat(URLDecoder.decode(raw, StandardCharsets.UTF_8)).contains("started|starting"));
        assertThat(lokiQueries).anySatisfy(raw ->
                assertThat(URLDecoder.decode(raw, StandardCharsets.UTF_8))
                        .contains("unit=\"paideia.service\""));
    }

    @Test
    @DisplayName("窗口内没有日志的单元被明确标注为可能未接入，而不是静默消失")
    void silentUnitIsLabelled() {
        OpsDiagnoser.Evidence evidence = collector(List.of("paideia", "mysql"), 10, 16000, true)
                .collect("alert", "日志模板异常待处理", null, null, window());

        assertThat(evidence.logs())
                .contains("[mysql]")
                .contains("未接入 Loki");
    }

    @Test
    @DisplayName("signatureTop 只压非异常签名，异常行再少也保留")
    void errorSignaturesSurviveCap() {
        Instant base = Instant.parse("2026-11-19T06:17:00Z");
        List<LokiClient.LogLine> lines = List.of(
                new LokiClient.LogLine(base.plusSeconds(3), "2026-11-19T14:17:03 ERROR boom once"),
                new LokiClient.LogLine(base, "2026-11-19T14:17:00 INFO alpha"),
                new LokiClient.LogLine(base.plusSeconds(1), "2026-11-19T14:17:01 INFO beta"),
                new LokiClient.LogLine(base.plusSeconds(2), "2026-11-19T14:17:02 INFO gamma"));

        List<String> rows = OpsEvidenceCollector.renderSignatures(lines, 1);

        assertThat(rows).anySatisfy(row -> assertThat(row).contains("boom once"));
        assertThat(String.join("\n", rows)).contains("已省略");
    }

    @Test
    @DisplayName("证据预算耗尽时不打 Loki，直接说明未采集日志")
    void emptyBudgetSkipsLogs() {
        OpsDiagnoser.Evidence evidence = collector(List.of("paideia"), 10, 0, true)
                .collect("alert", "日志模板异常待处理", null, null, window());

        assertThat(evidence.logs()).contains("证据预算已用完");
        assertThat(lokiQueries).isEmpty();
    }

    @Test
    @DisplayName("Loki 不可达时日志段降级为空，不抛异常也不假装有日志")
    void lokiDownDegrades() {
        OpsProperties properties = new OpsProperties();
        properties.setPrometheusUrl("http://127.0.0.1:1");
        properties.setLokiUrl("http://127.0.0.1:1");
        properties.setTempoUrl("http://127.0.0.1:1");
        properties.setQueryTimeoutMs(300);
        properties.getDiagnosis().setUnits(List.of("paideia"));
        OpsSummaryService summary = mock(OpsSummaryService.class);
        when(summary.summary()).thenReturn(new OpsSummary("2026-11-19T14:30:00+08:00", false, List.of()));
        OpsEvidenceCollector collector = new OpsEvidenceCollector(properties, summary,
                new PrometheusClient("http://127.0.0.1:1", 300),
                new LokiClient("http://127.0.0.1:1", 300),
                new TempoClient("http://127.0.0.1:1", 300));

        OpsDiagnoser.Evidence evidence = collector.collect("alert", "x", null, null, window());

        assertThat(evidence.logs()).contains("未接入 Loki");
    }

    @Test
    @DisplayName("运行态与日志的重启次数一致时只报一个数字")
    void restartCountAgreesWithLogs() {
        prometheusRestarts = 4;

        OpsDiagnoser.Evidence evidence = collector(List.of("paideia"), 10, 16000, true)
                .collect("alert", "日志模板异常待处理", null, null, window());

        assertThat(evidence.metrics())
                .contains("进程重启 4 次")
                .doesNotContain("两者不一致");
    }

    @Test
    @DisplayName("指标说重启 5 次、日志只有 4 次启动时两个数字都给人看")
    void restartCountMismatchIsReported() {
        prometheusRestarts = 5;

        OpsDiagnoser.Evidence evidence = collector(List.of("paideia"), 10, 16000, true)
                .collect("alert", "日志模板异常待处理", null, null, window());

        assertThat(evidence.metrics())
                .contains("进程重启 5 次")
                .contains("日志记录 4 次启动");
    }

    @Test
    @DisplayName("运行态段带上窗口分钟数，进程存活时长按人读的粒度展示")
    void runtimeUsesWindowAndHumanDurations() {
        OpsDiagnoser.Evidence evidence = collector(List.of("paideia"), 10, 16000, true)
                .collect("alert", "日志模板异常待处理", null, null, window());

        assertThat(evidence.metrics())
                .contains("运行态（窗口 20 分钟）")
                .contains("进程已运行")
                .contains("内存 OOM kill");
    }
}
