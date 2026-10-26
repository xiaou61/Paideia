package io.github.xiaou61.platform;

import java.util.Optional;

/**
 * 按账号 id 读角色（WORK-038）。
 *
 * <p><b>为什么是一个纯接口放在平台库</b>：`paideia-mcp` 只依赖契约、不 import 任何业务包
 * （WORK-019 的边界约定）。PAT 里只有 userId，没有角色，而管理类工具必须判角色——
 * 把"角色从哪来"收在这个接口后面，mcp 就不需要认识账号模块。
 *
 * <p><b>每次请求都查，不缓存</b>：账号被降权后，旧 PAT 必须立刻失去管理能力。
 * 代价是一次主键查询；将来若成为瓶颈，只能加"降权后 TTL 内生效可接受"的短缓存，
 * 不能把角色写进 token 行。
 */
public interface RoleLookup {

    /** 账号不存在时返回空；返回值是账号模块的角色名（`admin` / `learner`）。 */
    Optional<String> roleOf(long userId);
}
