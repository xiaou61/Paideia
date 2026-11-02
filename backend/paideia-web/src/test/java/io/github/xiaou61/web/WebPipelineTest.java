package io.github.xiaou61.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest
class WebPipelineTest {

    @Autowired
    MockMvc mockMvc;

    MockMvcTester mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.create(mockMvc);
    }

    @Test
    @DisplayName("正常返回值被包装为统一结构并带上追踪标识")
    void wrapsSuccessfulBody() {
        assertThat(mvc.get().uri("/probe/ok"))
                .matches(status().isOk())
                .bodyText()
                .contains("\"code\":0")
                .contains("\"value\":\"payload\"")
                .contains("\"traceId\":");
    }

    @Test
    @DisplayName("业务异常按错误码映射为 HTTP 状态，且不把异常类型泄给调用方")
    void mapsBizExceptionToStatus() {
        assertThat(mvc.get().uri("/probe/missing"))
                .matches(status().isNotFound())
                .bodyText()
                .contains("\"code\":40400")
                .doesNotContain("BizException")
                .doesNotContain("\tat ");
    }

    @Test
    @DisplayName("参数校验失败归为 400 并指出出问题的字段")
    void mapsValidationFailureToBadRequest() {
        assertThat(mvc.post().uri("/probe/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
                .matches(status().isBadRequest())
                .bodyText()
                .contains("\"code\":40000")
                .contains("name");
    }

    @Test
    @DisplayName("未捕获异常兜底为 500，对外只暴露错误码与追踪标识")
    void mapsUnexpectedExceptionToInternalError() {
        assertThat(mvc.get().uri("/probe/boom"))
                .matches(status().isInternalServerError())
                .bodyText()
                .contains("\"code\":50000")
                .doesNotContain("boom");
    }

    @Test
    @DisplayName("缺少必填参数归为 400（曾经落进兜底变 500）")
    void missingRequiredParamIsBadRequest() {
        assertThat(mvc.get().uri("/probe/requires-param"))
                .matches(status().isBadRequest())
                .bodyText()
                .contains("\"code\":40000")
                .contains("date");
    }

    @Test
    @DisplayName("参数类型对不上归为 400（date=abc，而不是 500）")
    void malformedParamIsBadRequest() {
        assertThat(mvc.get().uri("/probe/requires-param").param("date", "abc"))
                .matches(status().isBadRequest())
                .bodyText()
                .contains("\"code\":40000");
    }

    @Test
    @DisplayName("控制器自己构造的响应不被二次包装，但同样会被补上追踪标识")
    void doesNotDoubleWrapButFillsTraceId() {
        assertThat(mvc.get().uri("/probe/wrapped"))
                .matches(status().isOk())
                .bodyText()
                .contains("\"value\":\"inner\"")
                .doesNotContain("\"data\":{\"code\"")
                // 曾经这里恒为 null：早期实现直接跳过已包装的响应，导致这类接口拿不到 traceId
                .doesNotContain("\"traceId\":null");
    }
}
