package io.github.xiaou61.web;

import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常映射。业务异常按其错误码映射 HTTP 状态；参数校验失败归为 400；
 * 未捕获异常兜底为 500，对外只暴露错误码与追踪标识，堆栈只进日志。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException exception, HttpServletRequest request) {
        ErrorCode errorCode = exception.errorCode();
        return ResponseEntity.status(httpStatusOf(errorCode))
                .body(ApiResponse.<Void>failure(errorCode, exception.getMessage()).withTraceId(traceIdOf(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(ApiResponse.<Void>failure(ErrorCode.INVALID_ARGUMENT, detail).withTraceId(traceIdOf(request)));
    }

    /**
     * 未知路由。
     *
     * <p>必须显式处理：下面的 {@code @ExceptionHandler(Exception.class)} 会把它兜成 500，
     * 于是"路径写错了"看起来像"服务端崩了"，而且掩盖了一个事实——本该存在的端点不存在。
     * 这条对"已删除的端点确实不存在"这类验收尤其重要。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.<Void>failure(ErrorCode.NOT_FOUND).withTraceId(traceIdOf(request)));
    }

    /**
     * 请求体读不出来（不是合法 JSON、类型对不上）。
     *
     * <p>必须显式处理：下面的兜底会把它兜成 500，而 500 意味着"服务端的问题"——
     * 一个客户端拼错的请求体会被记成我们的故障，排查方向从一开始就偏了。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.<Void>failure(ErrorCode.INVALID_ARGUMENT, "请求体无法解析")
                        .withTraceId(traceIdOf(request)));
    }

    /**
     * 缺少必填参数 / 参数类型对不上。
     *
     * <p>必须显式处理：兜底会把它们兜成 500——调用方漏传或写错一个参数会被记成"服务端故障"，
     * 监控与告警就数错了账（2026-11-03 线上冒烟实测：`/learning/plan-items` 不带 date 时 500）。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.<Void>failure(ErrorCode.INVALID_ARGUMENT, "缺少必填参数：" + exception.getParameterName())
                        .withTraceId(traceIdOf(request)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleParameterTypeMismatch(MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.<Void>failure(ErrorCode.INVALID_ARGUMENT, "参数格式不正确：" + exception.getName())
                        .withTraceId(traceIdOf(request)));
    }

    /** 路径存在但方法不对（例如用 GET 打只接受 POST 的端点）：405 而不是 500。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.<Void>failure(ErrorCode.INVALID_ARGUMENT, "该路径不支持此请求方法")
                        .withTraceId(traceIdOf(request)));
    }

    /** Content-Type 不受支持：415 而不是 500。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.<Void>failure(ErrorCode.INVALID_ARGUMENT, "不支持的 Content-Type")
                        .withTraceId(traceIdOf(request)));
    }

    /**
     * 上传内容超过容器上限。
     *
     * <p>必须显式处理：兜底会把它变成 500，而"传太大了"是客户端问题，应该是 400——
     * 否则用户看到"服务器内部错误"会去查后端日志，而原因在他自己的文件上。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.<Void>failure(ErrorCode.INVALID_ARGUMENT, "上传内容超过大小上限")
                        .withTraceId(traceIdOf(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception, HttpServletRequest request) {
        String traceId = traceIdOf(request);
        log.error("未捕获异常 traceId={}", traceId, exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.<Void>failure(ErrorCode.INTERNAL).withTraceId(traceId));
    }

    /**
     * 错误码 → HTTP 状态。
     *
     * <p><b>新增 {@link ErrorCode} 时必须在这里加一条</b>：这是个穷尽 switch，漏掉会静默落到
     * {@code default -> 500}——业务码在响应体里是对的，只有 HTTP 状态错。2026-10-16 加
     * {@code INSUFFICIENT_CREDITS} 时就踩过一次，是集成测试抓出来的（界面按 500 处理会显示
     * "服务器错误"，而它其实该提示"去充值/联系管理员"）。
     */
    private HttpStatus httpStatusOf(ErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case INSUFFICIENT_CREDITS -> HttpStatus.PAYMENT_REQUIRED;
            case MEMORY_DISABLED -> HttpStatus.CONFLICT;
            case SANDBOX_DISABLED -> HttpStatus.SERVICE_UNAVAILABLE;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private String traceIdOf(HttpServletRequest request) {
        Object value = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return value == null ? null : value.toString();
    }
}
