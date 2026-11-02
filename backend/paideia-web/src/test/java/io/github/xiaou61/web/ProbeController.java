package io.github.xiaou61.web;

import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试专用的探针控制器：只为驱动 Web 基础设施（响应包装、异常映射、校验）而存在，
 * 不承载任何业务语义，也不属于应用装配的一部分——它位于测试源码目录。
 */
@RestController
class ProbeController {

    @GetMapping("/probe/ok")
    Map<String, Object> ok() {
        return Map.of("value", "payload");
    }

    @GetMapping("/probe/missing")
    Map<String, Object> missing() {
        throw new BizException(ErrorCode.NOT_FOUND);
    }

    @GetMapping("/probe/boom")
    Map<String, Object> boom() {
        throw new IllegalStateException("boom");
    }

    @GetMapping("/probe/wrapped")
    ApiResponse<Map<String, Object>> wrapped() {
        return ApiResponse.ok(Map.of("value", "inner"));
    }

    @PostMapping("/probe/validate")
    Map<String, Object> validate(@Valid @RequestBody ProbeRequest request) {
        return Map.of("name", request.name());
    }

    /** 缺参（不传 date）与类型不对（date=abc）都靠这一个端点驱动。 */
    @GetMapping("/probe/requires-param")
    Map<String, Object> requiresParam(@RequestParam("date") LocalDate date) {
        return Map.of("date", date.toString());
    }

    record ProbeRequest(@NotBlank String name) {
    }
}
