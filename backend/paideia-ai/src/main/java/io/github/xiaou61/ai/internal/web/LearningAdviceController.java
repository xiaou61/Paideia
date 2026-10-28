package io.github.xiaou61.ai.internal.web;

import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.internal.billing.CreditService;
import io.github.xiaou61.ai.internal.credential.CredentialService;
import io.github.xiaou61.ai.internal.provider.LearningAdvisor;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.security.CurrentUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学习推荐的三句话理由（WORK-047）。
 *
 * <p><b>它不是推荐接口。</b>排序在前端用纯函数算完了，这里只把已经排好的 Top 3 交给模型
 * 润色一次。所以入参里**没有**"全部轨道"，服务端也就不可能替你重新排序。
 *
 * <p><b>失败一律 200 + 空理由。</b>理由缺失是产品允许状态（前端会退回规则句），
 * 把它变成 5xx 只会让一次可选的润色看起来像页面坏了。只有入参非法才 400。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/learning")
class LearningAdviceController {

    private static final Logger log = LoggerFactory.getLogger(LearningAdviceController.class);

    /** slug 只可能是导入脚本生成的小写短横线形式；卡死在白名单形状上是这里唯一的注入防线。 */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]{1,64}$");
    private static final Pattern ROLE = Pattern.compile("^[a-z0-9-]{1,32}$");
    /** 名称与岗位名来自客户端，只允许常见字符，且限长——它们会进提示词。 */
    private static final Pattern SAFE_TEXT = Pattern.compile("^[\\p{IsHan}A-Za-z0-9 ()（）·.,:：/\\-]{1,60}$");

    private final LearningAdvisor advisor;
    private final CredentialService credentials;
    private final CreditService credits;

    LearningAdviceController(LearningAdvisor advisor, CredentialService credentials, CreditService credits) {
        this.advisor = advisor;
        this.credentials = credentials;
        this.credits = credits;
    }

    record PickRequest(String slug, String name, String tier, Integer passed, Integer total) {
    }

    record AdviceRequest(String role, String roleLabel, List<PickRequest> picks) {
    }

    record Reason(String slug, String reason) {
    }

    record AdviceResponse(List<Reason> reasons) {
    }

    @PostMapping("/advice")
    ApiResponse<AdviceResponse> advice(@RequestBody(required = false) AdviceRequest body) {
        List<LearningAdvisor.Pick> picks = validate(body);
        // 平台没配 AI：直接给空理由，前端用规则句。这不值得报错。
        Optional<ModelAccess> access = credentials.access(0);
        if (access.isEmpty()) {
            return ApiResponse.ok(new AdviceResponse(List.of()));
        }
        long userId = Long.parseLong(CurrentUser.subjectId());
        String roleLabel = body.roleLabel().trim();

        long reserved = credits.reserve(userId, credits.estimate());
        try {
            LearningAdvisor.Result result = advisor.write(roleLabel, picks, access.get());
            credits.settle(userId, reserved, access.get().model(),
                    result.inputTokens(), result.outputTokens(), null);
            List<Reason> reasons = new ArrayList<>();
            // 模型只许回答**给过它的** slug：多出来的丢掉，防止它凭空加一条
            for (LearningAdvisor.Pick pick : picks) {
                String text = result.reasons().get(pick.slug());
                if (text != null && !text.isBlank()) {
                    reasons.add(new Reason(pick.slug(), text));
                }
            }
            return ApiResponse.ok(new AdviceResponse(reasons));
        } catch (RuntimeException failure) {
            // 预留的钱要退回去，否则用户为一次失败的润色付了费
            credits.refund(userId, reserved, null, "推荐理由生成失败");
            log.info("learning.advice.degraded reason={}", failure.getClass().getSimpleName());
            return ApiResponse.ok(new AdviceResponse(List.of()));
        }
    }

    /** 白名单校验：任何一项不合格就整单 400，不做"部分接受"——那会让前端拿到不一致的输入。 */
    private static List<LearningAdvisor.Pick> validate(AdviceRequest body) {
        if (body == null || body.picks() == null || body.picks().isEmpty() || body.picks().size() > 3) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "推荐条数必须是 1 到 3 条");
        }
        if (body.role() == null || !ROLE.matcher(body.role()).matches()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "role 不合法");
        }
        if (body.roleLabel() == null || !SAFE_TEXT.matcher(body.roleLabel().trim()).matches()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "roleLabel 不合法");
        }
        List<LearningAdvisor.Pick> picks = new ArrayList<>();
        for (PickRequest item : body.picks()) {
            if (item == null || item.slug() == null || !SLUG.matcher(item.slug()).matches()) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "slug 不合法");
            }
            if (item.name() == null || !SAFE_TEXT.matcher(item.name().trim()).matches()) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "name 不合法");
            }
            if (item.tier() == null || !List.of("core", "advanced", "optional").contains(item.tier())) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "tier 不合法");
            }
            int passed = item.passed() == null ? 0 : item.passed();
            int total = item.total() == null ? 0 : item.total();
            if (passed < 0 || total < 0 || passed > 999 || total > 999) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "进度数值不合法");
            }
            picks.add(new LearningAdvisor.Pick(item.slug(), item.name().trim(), item.tier(), passed, total));
        }
        return picks;
    }
}
