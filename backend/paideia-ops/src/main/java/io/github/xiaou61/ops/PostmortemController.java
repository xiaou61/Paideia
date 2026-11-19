package io.github.xiaou61.ops;

import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维复盘的管理端端点（WORK-086）。
 *
 * <p>权限沿用 {@code /api/v1/ops/admin/** → hasRole("ADMIN")}，不逐方法加注解；这里只负责
 * 参数形状与 HTTP 语义。
 *
 * <p>没有 DELETE：复盘是事故记录，删掉等于把"当时为什么这么判断"擦掉。不想让它进知识库就
 * 归档（ARCHIVED），历史仍在。
 */
@ApiAccess(ApiAccess.Access.ADMIN)
@RestController
@RequestMapping("/api/v1/ops/admin/postmortems")
class PostmortemController {

    private final PostmortemService postmortems;

    PostmortemController(PostmortemService postmortems) {
        this.postmortems = postmortems;
    }

    /** 从诊断起草复盘；请求体里除 `diagnosisId` 外的字段作为初始值覆盖模板。 */
    @PostMapping
    ApiResponse<PostmortemService.PostmortemDetail> create(@RequestBody CreateRequest body) {
        if (body == null || body.diagnosisId() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "diagnosisId 不能为空");
        }
        long userId = CurrentUser.userId();
        long id = postmortems.createDraft(body.diagnosisId(), userId);
        if (body.hasOverrides()) {
            postmortems.update(id, body.toDraft(), userId);
        }
        return ApiResponse.ok(postmortems.get(id));
    }

    @PutMapping("/{id}")
    ApiResponse<PostmortemService.PostmortemDetail> update(@PathVariable("id") long id,
                                                          @RequestBody DraftRequest body) {
        postmortems.update(id, body.toDraft(), CurrentUser.userId());
        return ApiResponse.ok(postmortems.get(id));
    }

    @PostMapping("/{id}/approve")
    ApiResponse<PostmortemService.PostmortemDetail> approve(@PathVariable("id") long id) {
        postmortems.approve(id, CurrentUser.userId());
        return ApiResponse.ok(postmortems.get(id));
    }

    @PostMapping("/{id}/archive")
    ApiResponse<PostmortemService.PostmortemDetail> archive(@PathVariable("id") long id) {
        postmortems.archive(id, CurrentUser.userId());
        return ApiResponse.ok(postmortems.get(id));
    }

    @GetMapping
    ApiResponse<PostmortemService.Page> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size) {
        int size1 = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, page - 1) * size1;
        return ApiResponse.ok(postmortems.list(status, size1, offset));
    }

    @GetMapping("/{id}")
    ApiResponse<PostmortemService.PostmortemDetail> detail(@PathVariable("id") long id) {
        return ApiResponse.ok(postmortems.get(id));
    }

    /** 导读：下载 `.md` 原件，方便贴进 GitLab Issue 或其它知识载体。 */
    @GetMapping("/{id}/download")
    ResponseEntity<byte[]> download(@PathVariable("id") long id) {
        PostmortemService.PostmortemDetail detail = postmortems.get(id);
        String body = detail.bodyMd() == null ? "" : detail.bodyMd();
        String filename = "postmortem-" + id + ".md";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- 请求体

    /** 全字段可空：null 表示"这一项不改"，空串表示"清空"。 */
    record DraftRequest(String title, String severity, String alertName, String rootCause,
                        String impact, String timelineMd, String actionsMd, String evidenceRefs,
                        String bodyMd) {

        PostmortemService.PostmortemDraft toDraft() {
            return new PostmortemService.PostmortemDraft(title, severity, alertName, rootCause,
                    impact, timelineMd, actionsMd, evidenceRefs, bodyMd);
        }
    }

    record CreateRequest(Long diagnosisId, String title, String severity, String alertName,
                         String rootCause, String impact, String timelineMd, String actionsMd,
                         String evidenceRefs, String bodyMd) {

        boolean hasOverrides() {
            return title != null || severity != null || alertName != null || rootCause != null
                    || impact != null || timelineMd != null || actionsMd != null
                    || evidenceRefs != null || bodyMd != null;
        }

        PostmortemService.PostmortemDraft toDraft() {
            return new PostmortemService.PostmortemDraft(title, severity, alertName, rootCause,
                    impact, timelineMd, actionsMd, evidenceRefs, bodyMd);
        }
    }
}

