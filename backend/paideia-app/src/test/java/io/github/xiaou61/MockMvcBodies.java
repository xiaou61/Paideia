package io.github.xiaou61;

import java.nio.charset.StandardCharsets;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 测试共享件（WORK-062）：读 MockMvc 的响应体。
 *
 * <p>原来 14 个测试类各自抄了一份逐字相同的私有方法，这里只留一份。
 * 放在 app 测试根包、package-private 静态——零 pom 改动、零 Spring 上下文成本
 * （共享基类会按 {@code @Import} 配置拆上下文，每份 15–25 秒，见 TestProfileConfiguration 的说明）。
 */
final class MockMvcBodies {

    private MockMvcBodies() {
    }

    static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}