package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.xiaou61.knowledge.internal.question.QuestionIndexService;
import io.github.xiaou61.knowledge.QuestionLabelingPort;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 难度 AI 标注端口（WORK-083 TASK-008）：端口给出标签则写入；端口抛错则回退规则估算。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class QuestionLabelingIntegrationTest {

    private static final String QUIZ_BODY = """
            ```quiz
            {"id":"it-label","questions":[
              {"question":"L one?","options":["a","b"],"answer":0}
            ]}
            ```
            """;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    QuestionIndexService indexService;

    @MockitoBean
    QuestionLabelingPort labelingPort;

    private long entryId;

    @BeforeEach
    void setUp() {
        String title = "难度标注IT-" + System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO knowledge_entries (title, body, status, published_at)
                VALUES (?, ?, 'published', UTC_TIMESTAMP(3))
                """, title, QUIZ_BODY);
        entryId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_entries WHERE title = ?", Long.class, title);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM question_index WHERE entry_id = ?", entryId);
        jdbcTemplate.update("DELETE FROM question_difficulty WHERE entry_id = ?", entryId);
        jdbcTemplate.update("DELETE FROM knowledge_entries WHERE id = ?", entryId);
    }

    private String storedLabel() {
        return jdbcTemplate.queryForObject(
                "SELECT difficulty_label FROM question_index WHERE entry_id = ?", String.class, entryId);
    }

    @Test
    @DisplayName("端口给出 difficulty 则写入该标签")
    void portLabelIsStored() {
        String questionKey = indexService.snapshots(entryId).get(0).questionKey();
        when(labelingPort.tag(any()))
                .thenReturn(new QuestionLabelingPort.Result(Map.of(questionKey, "hard")));

        indexService.reconcile(entryId);

        assertThat(storedLabel()).isEqualTo("hard");
    }

    @Test
    @DisplayName("端口抛错则回退规则兜底（2 选项判易），不中断索引")
    void portFailureFallsBackToRule() {
        when(labelingPort.tag(any())).thenThrow(new RuntimeException("boom"));

        indexService.reconcile(entryId);

        assertThat(storedLabel()).isEqualTo("easy");
    }
}
