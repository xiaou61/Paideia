package io.github.xiaou61;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 测试共享件（WORK-062）：SSE 请求的 "asyncStarted + asyncDispatch" 两行样板。
 *
 * <p>SSE 的正文由另一个线程写，直接读响应体只会读到空串——必须走 asyncStarted + asyncDispatch
 * （本目录既有约定）。原来 4 个测试类各写一遍，这里只留一份；请求怎么拼仍由调用方决定。
 */
final class MockMvcSse {

    private MockMvcSse() {
    }

    /** 发一个 SSE 请求并把流读完；参数名刻意不叫 request——会遮住同名静态导入的匹配器。 */
    static MvcResult exchange(MockMvc mockMvc, MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult started = mockMvc.perform(builder).andExpect(request().asyncStarted()).andReturn();
        return mockMvc.perform(asyncDispatch(started)).andReturn();
    }
}