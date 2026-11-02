package io.github.xiaou61.security;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前请求的身份。
 *
 * <p>身份只从安全上下文读取，也就是只从令牌来。接口层不允许接受客户端传入的用户标识——
 * 一旦允许，越权读取就只是改一个参数的问题。
 */
public final class CurrentUser {

    private static final String ROLE_AUTHORITY_PREFIX = "ROLE_";

    private CurrentUser() {
    }

    public static String subjectId() {
        return authentication().getName();
    }

    /**
     * 当前主体转成账号 id（WORK-062 收敛：全仓原来有 11 份逐字拷贝 + 6 处裸解析）。
     *
     * <p>令牌里的 {@code sub} 是账号 id 的**字符串形式**，而各表的外键是数字。合法令牌
     * 必然是数字（账号主键自增）；非数字只可能来自伪造或版本错配的令牌。匿名请求由
     * {@link #authentication()} 先抛 {@code UNAUTHENTICATED}；这里把"非数字"也归到同一条
     * 401 上——它同样是"身份不被接受"，不该表现为 500。
     *
     * <p>要"读不到就放过"的读路径（例如知识库的标记读取）请在本模块内包 try/catch，
     * 不要给这个方法加宽松变体：写路径上误用宽松版会把越权读变成静默。
     */
    public static long userId() {
        try {
            return Long.parseLong(subjectId());
        } catch (NumberFormatException | BizException exception) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "令牌主体不是有效的账号标识");
        }
    }

    /**
     * 当前主体在当前请求中的角色（小写，与令牌 claim 和数据库一致）；没有角色时返回空串。
     *
     * <p>取值来自权限而不是重新解析令牌：权限是安全链已经算过的结果，
     * 再从令牌读一遍等于把同一个判断做两遍、还可能不一致。
     */
    public static String role() {
        for (GrantedAuthority authority : authentication().getAuthorities()) {
            String value = authority.getAuthority();
            if (value.startsWith(ROLE_AUTHORITY_PREFIX)) {
                return value.substring(ROLE_AUTHORITY_PREFIX.length()).toLowerCase(java.util.Locale.ROOT);
            }
        }
        return "";
    }

    private static Authentication authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // AnonymousAuthenticationToken 的 isAuthenticated() 同样是 true（匿名过滤器默认开启），
        // 不单独排除的话，permitAll 路径上取到的身份会是字符串 "anonymousUser" 而不是"未认证"。
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        return authentication;
    }
}
