package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.security.AuthPort;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 附件上传与读取的集成测试。
 *
 * <p>三件事必须同时成立，缺一条这个功能就不安全或不可用：
 * <ol>
 *   <li><b>写要管理员</b>：匿名 401、学习者 403。</li>
 *   <li><b>读要匿名可用</b>：{@code <img>} 不带 Authorization 头，这正是当初决定"端点不鉴权"的原因；
 *       如果哪天有人给读端点加上鉴权，图片会在浏览器里全部显示不出来，而这里会先红。</li>
 *   <li><b>只收白名单类型</b>：靠**内容**判断而不是扩展名或客户端声明。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class KnowledgeFileIntegrationTest {

    /** 最小 PNG 文件头 + 填充：嗅探只看魔数，这里只需要"内容确实是 png"。 */
    private static final byte[] PNG = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 0, 0, 0, 0,
    };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthPort authPort;

    private String learner() {
        return TestTokens.bearer(authPort, "1");
    }

    private String admin() {
        return TestTokens.bearer(authPort, "2", AuthPort.ROLE_ADMIN);
    }

    private MockMultipartFile pngPart() {
        return new MockMultipartFile("file", "shot.png", "image/png", PNG);
    }

    @Test
    void uploadRequiresAdmin() throws Exception {
        mockMvc.perform(multipart("/api/v1/knowledge/admin/files").file(pngPart()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/v1/knowledge/admin/files").file(pngPart())
                        .header("Authorization", learner()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminUploadsThenAnyoneCanReadTheBytes() throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/knowledge/admin/files")
                        .file(pngPart())
                        .header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentType").value("image/png"))
                .andExpect(jsonPath("$.data.size").value(PNG.length))
                .andExpect(jsonPath("$.data.originalName").value("shot.png"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String id = JsonPath.read(response, "$.data.id");
        assertThat(id).hasSize(36);
        assertThat((String) JsonPath.read(response, "$.data.url"))
                .isEqualTo("/api/v1/knowledge/files/" + id);

        // 不带任何令牌：这就是 <img> 发出的那种请求
        byte[] bytes = mockMvc.perform(get("/api/v1/knowledge/files/" + id))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                // 禁止浏览器按内容重新猜类型（把"内容被当别的类型解释"这条路堵掉）
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(bytes).isEqualTo(PNG);
    }

    @Test
    void unknownFileIdIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge/files/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
        // 带 .. 的路径：实测由安全过滤器在进入处理器之前就拒为 400（比 404 更早、更严）。
        // 这里断言"是客户端错误"，因为真正要钉住的属性是**绝不被服务、也绝不变成 500**，
        // 而不是它恰好落在 400 还是 404 上。
        mockMvc.perform(get("/api/v1/knowledge/files/../../etc/passwd"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void svgIsRejectedEvenWhenDeclaredAsPng() throws Exception {
        // SVG 是 XML，能内嵌脚本；被当图片内联返回就是存储型 XSS。它没有魔数，因此落到"未知类型"
        MockMultipartFile svg = new MockMultipartFile("file", "evil.png", "image/png",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                        .getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/knowledge/admin/files").file(svg).header("Authorization", admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void contentThatDisagreesWithTheExtensionIsRejected() throws Exception {
        // 内容是 png、名字却叫 .svg：两重校验必须能发现这种不一致
        MockMultipartFile mismatched = new MockMultipartFile("file", "actually-svg.svg", "image/svg+xml", PNG);
        mockMvc.perform(multipart("/api/v1/knowledge/admin/files").file(mismatched)
                        .header("Authorization", admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oversizedFileIsRejected() throws Exception {
        byte[] big = new byte[11 * 1024 * 1024];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        MockMultipartFile part = new MockMultipartFile("file", "big.png", "image/png", big);

        mockMvc.perform(multipart("/api/v1/knowledge/admin/files").file(part).header("Authorization", admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void emptyUploadIsRejected() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);
        mockMvc.perform(multipart("/api/v1/knowledge/admin/files").file(empty).header("Authorization", admin()))
                .andExpect(status().isBadRequest());
    }
}
