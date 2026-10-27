package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** trace 属性白名单（WORK-043 AC-016）。 */
class TempoTraceRedactorTest {

    @Test
    @DisplayName("只保留能解释慢/错的低敏属性")
    void keepsOnlyAllowedKeys() {
        Map<String, String> filtered = TempoTraceRedactor.filter(Map.of(
                "db.system", "mysql",
                "db.operation", "SELECT",
                "http.route", "/api/v1/notes",
                "db.statement", "select * from notes where content like ?",
                "http.request.body", "{\"content\":\"secret\"}",
                "authorization", "Bearer secret",
                "cookie", "session=abc"));

        assertThat(filtered)
                .containsEntry("db.system", "mysql")
                .containsEntry("db.operation", "SELECT")
                .containsEntry("http.route", "/api/v1/notes")
                .doesNotContainKeys("db.statement", "http.request.body", "authorization", "cookie");
    }
}
