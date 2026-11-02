package io.github.xiaou61.platform;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一个 HTTP 端点要求的访问级别，用于让接口契约检查**自动发现**路由，而不是靠人工清单。
 *
 * <p><b>它不参与鉴权</b>。真正的判定仍在 {@code SecurityConfiguration} 的路径规则里——这里只是
 * 把"这个端点本来应该要什么权限"写在代码旁边，让测试能拿它与**实际行为**对照：
 * 声明为 {@link Access#ADMIN} 的端点，学习者令牌必须拿到 403；声明为其他级别的不能是 403。
 * 两者不一致就失败，那正是"路径规则漏配或顺序写反"的症状。
 *
 * <p>写在控制器类上是该控制器所有端点的默认值；写在方法上覆盖类级声明
 * （例如附件控制器：上传要 ADMIN、读取是 PUBLIC_READ）。
 *
 * <p>{@code ApiAccessDeclarationTest} 要求每个 {@code /api/v1/**} 端点都能解析到一个声明——
 * 新增控制器或方法时忘了标，构建即失败。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ApiAccess {

    Access value();

    /** 访问级别。与 {@code ApiSurface} 时期的名字保持一致，语义未变。 */
    enum Access {
        /** 匿名可读。附件与公开分享——id 是不可枚举的 UUID，即凭据。 */
        PUBLIC_READ,
        /** 匿名可写，但只放凭据交换（{@code /api/v1/auth/**}）。矩阵不调用：会发信、会累计限流。 */
        PUBLIC_WRITE,
        /** 只要求已登录。 */
        AUTHENTICATED,
        /**
         * 只允许本机（回环）来源。机器到机器：Grafana 告警 webhook 这类端点。
         * 没有 JWT——来源即身份，端点自身再校验共享令牌（没有令牌一律拒绝）。
         */
        LOCAL_ONLY,
        /** 要求 {@code ROLE_ADMIN}。 */
        ADMIN
    }
}
