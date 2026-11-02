package io.github.xiaou61.ai.internal.web;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.security.CurrentUser;

/**
 * 把令牌主体转成账号 id。
 *
 * <p>与知识库那份同理（令牌的 {@code sub} 是账号 id 的字符串形式），但这里**一律用
 * {@link #require()}**：本模块的每个端点都在写或生成"与某个具体用户绑定"的东西——
 * 凭据是私有的、结果行要记 owner、限流按用户计数。没有身份就没有正确行为，因此不提供
 * "读不到就返回空"的宽松版本，免得有人在一个写路径上误用。
 */
final class AiRequester {

    private AiRequester() {
    }

    static long require() {
        return CurrentUser.userId();
    }
}
