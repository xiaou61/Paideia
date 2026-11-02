package io.github.xiaou61;

import io.github.xiaou61.security.AuthPort;

/**
 * 测试共享件（WORK-062）：签发一个 Bearer 令牌。
 *
 * <p>原来 28 个测试类各自内联 `"Bearer " + authPort.issue(new AuthPort.Subject(...)).value()`。
 * 这里**不提供任何默认值**：subject 与 role 一律显式传——"默认管理员=1"这类隐式约定正是
 * 最容易在某个类里悄悄写错的东西（各测试用的 admin subject 本来就不一样：1 / 2 / 900 / test-role）。
 */
public final class TestTokens {

    private TestTokens() {
    }

    /** 学习者令牌（最常见的一族）。 */
    public static String bearer(AuthPort authPort, String subject) {
        return "Bearer " + authPort.issue(new AuthPort.Subject(subject, AuthPort.ROLE_LEARNER)).value();
    }

    /** 指定角色的令牌（管理员、或参数化的 role）。 */
    public static String bearer(AuthPort authPort, String subject, String role) {
        return "Bearer " + authPort.issue(new AuthPort.Subject(subject, role)).value();
    }
}