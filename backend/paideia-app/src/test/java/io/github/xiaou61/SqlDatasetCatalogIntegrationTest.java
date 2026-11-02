package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.security.AuthPort;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 数据集目录的可见性（WORK-060 AC-003/004/007）。
 *
 * <p>**为什么绕开上传端点直接插行**：上传要先过沙箱干跑，而本机没有 Docker（沙箱不可用）。
 * 这里要验的是**读侧**——私有只给本人、公开给所有人、上传者名字能解析；
 * 写侧的可见性映射由 `SqlDatasetUploadServiceTest` 与 `SqlDatasetControllerTest` 覆盖。
 *
 * <p>需要真实 MySQL 与 {@code PAIDEIA_TEST_DB_PASSWORD}，未配置时整类跳过。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class SqlDatasetCatalogIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuthPort authPort;

    private static final long AUTHOR = 710001L;
    private static final long STRANGER = 720001L;

    private final List<String> keys = new java.util.ArrayList<>();
    private final List<String> usernames = new java.util.ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String key : keys) {
            jdbc.update("DELETE FROM sql_datasets WHERE dataset_key = ?", key);
        }
        for (String username : usernames) {
            jdbc.update("DELETE FROM users WHERE username = ?", username);
        }
    }

    private String insertDataset(String source, String status, Long owner) {
        String key = UUID.randomUUID().toString();
        keys.add(key);
        jdbc.update("""
                INSERT INTO sql_datasets
                    (dataset_key, name, source, owner_user_id, storage_id, schema_json,
                     table_count, row_count, size_bytes, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'storage-test', NULL, 1, 1, 10, ?, ?, ?)
                """, key, "目录用例-" + key.substring(0, 8), source, owner, status,
                Instant.now(), Instant.now());
        return key;
    }

    private List<String> catalog(long userId) throws Exception {
        String body = mockMvc.perform(get("/api/v1/sql-datasets/catalog")
                        .header("Authorization", token(userId)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data[*].key");
    }

    private String ownerName(long userId, String key) throws Exception {
        String body = mockMvc.perform(get("/api/v1/sql-datasets/catalog")
                        .header("Authorization", token(userId)))
                .andReturn().getResponse().getContentAsString();
        List<String> names = JsonPath.read(body, "$.data[?(@.key=='" + key + "')].ownerName");
        return names.isEmpty() ? null : names.get(0);
    }

    private String token(long userId) {
        return TestTokens.bearer(authPort, String.valueOf(userId));
    }

    private int postVisibility(long userId, String key, String visibility) throws Exception {
        return mockMvc.perform(post("/api/v1/sql-datasets/" + key + "/visibility")
                        .header("Authorization", token(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"" + visibility + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    /** AC-003：私有集只出现在作者的目录里。 */
    @Test
    void 私有集只对作者可见() throws Exception {
        String key = insertDataset("private", "draft", AUTHOR);

        assertThat(catalog(AUTHOR)).contains(key);
        assertThat(catalog(STRANGER)).doesNotContain(key);
    }

    /** AC-004：公开集对陌生人可见，且显示上传者用户名。 */
    @Test
    void 公开集对所有人可见并显示上传者() throws Exception {
        String username = "catalog" + (System.nanoTime() % 1_000_000L);
        long authorId = insertUser(username);
        String key = insertDataset("community", "published", authorId);

        assertThat(catalog(STRANGER)).contains(key);
        assertThat(ownerName(STRANGER, key)).isEqualTo(username);
    }

    /** AC-007：已下架的社区集不进目录（管理员下架优先）。 */
    @Test
    void 已下架的社区集不出现在目录() throws Exception {
        String key = insertDataset("community", "taken_down", AUTHOR);

        assertThat(catalog(AUTHOR)).doesNotContain(key);
        assertThat(catalog(STRANGER)).doesNotContain(key);
    }

    /** AC-010：作者注销后显示「已注销用户」，不回退到用户 id。 */
    @Test
    void 注销作者显示已注销用户() throws Exception {
        String key = insertDataset("community", "published", null);

        assertThat(catalog(STRANGER)).contains(key);
        assertThat(ownerName(STRANGER, key)).isEqualTo("已注销用户");
    }

    /** AC-005：作者把公开集收回私有后，陌生人立刻看不到，作者自己仍能看到。 */
    @Test
    void 作者可以收回私有且陌生人不再可见() throws Exception {
        String key = insertDataset("community", "published", AUTHOR);
        assertThat(catalog(STRANGER)).contains(key);

        assertThat(postVisibility(AUTHOR, key, "private")).isEqualTo(200);

        assertThat(catalog(STRANGER)).doesNotContain(key);
        assertThat(catalog(AUTHOR)).contains(key);
    }

    /** AC-004 的另一半：作者把私有集设为公开后，陌生人可见。 */
    @Test
    void 作者可以设为公开且陌生人可见() throws Exception {
        String key = insertDataset("private", "draft", AUTHOR);
        assertThat(catalog(STRANGER)).doesNotContain(key);

        assertThat(postVisibility(AUTHOR, key, "public")).isEqualTo(200);

        assertThat(catalog(STRANGER)).contains(key);
    }

    /** AC-006：非作者切换得到 404，且数据集仍不对他可见。 */
    @Test
    void 非作者不能切换可见性() throws Exception {
        String key = insertDataset("private", "draft", AUTHOR);

        assertThat(postVisibility(STRANGER, key, "public")).isEqualTo(404);
        assertThat(catalog(STRANGER)).doesNotContain(key);
    }

    /** AC-006：管理员下架优先，作者不能自行改私有或重新公开。 */
    @Test
    void 已下架集不能由作者切换() throws Exception {
        String key = insertDataset("community", "taken_down", AUTHOR);

        assertThat(postVisibility(AUTHOR, key, "private")).isEqualTo(400);
        assertThat(postVisibility(AUTHOR, key, "public")).isEqualTo(400);
    }

    /** AC-006：内置集不在表里，不能被切换。 */
    @Test
    void 内置集不能切换() throws Exception {
        assertThat(postVisibility(AUTHOR, "orders-public", "private")).isEqualTo(400);
    }

    /** AC-010：管理端社区列表显示上传者用户名（不是用户 id）。 */
    @Test
    void 管理端列表显示上传者用户名() throws Exception {
        String username = "adminview" + (System.nanoTime() % 1_000_000L);
        long authorId = insertUser(username);
        String key = insertDataset("community", "published", authorId);

        String body = mockMvc.perform(get("/api/v1/sandbox/admin/sql-datasets")
                        .header("Authorization", TestTokens.bearer(authPort, "1", AuthPort.ROLE_ADMIN)))
                .andReturn().getResponse().getContentAsString();

        List<String> names = JsonPath.read(body, "$.data[?(@.datasetKey=='" + key + "')].ownerName");
        assertThat(names).containsExactly(username);
    }

    private long insertUser(String username) {
        usernames.add(username);
        jdbc.update("""
                INSERT INTO users (username, email, password_hash, role, email_verified_at)
                VALUES (?, ?, 'not-a-real-hash', 'learner', ?)
                """, username, username + "@test.local", Instant.now());
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
        return id == null ? 0L : id;
    }
}