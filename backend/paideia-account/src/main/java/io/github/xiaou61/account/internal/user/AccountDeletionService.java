package io.github.xiaou61.account.internal.user;

import io.github.xiaou61.account.internal.token.RefreshTokenMapper;
import io.github.xiaou61.account.internal.home.HomeLayoutService;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.platform.UserDeletedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注销账号（用户裁决 10：**级联硬删、不保留、不可恢复**）。
 *
 * <p>三步，在同一个事务里：
 * <ol>
 *   <li>删掉这个人的 refresh 记录——留着等于留着一把能换新令牌的钥匙；</li>
 *   <li>删掉 users 那一行；</li>
 *   <li>发布 {@link UserDeletedEvent}，由各模块**自己**删掉自己那份用户数据。</li>
 * </ol>
 *
 * <p><b>要求密码</b>：注销不可逆，只凭一个被偷走的令牌就能清空一个人的全部数据，代价不对等。
 * 这里不校验"两次确认"之类的东西——那是界面的事，接口层守住"必须有凭据"这一条。
 *
 * <p>没有宽限期、没有回收站、也没有定时任务：删就是删（用户明确选了这一条）。
 */
@Service
public class AccountDeletionService {

    private final UserMapper users;
    private final RefreshTokenMapper refreshTokens;
    private final PasswordHasher hasher;
    private final ApplicationEventPublisher events;
    /** 画像与本模块同库同域，这里直接删（不绕事件）。 */
    private final io.github.xiaou61.account.internal.profile.LearnerProfileService profiles;
    /** 首页布局同属本模块，随注销一起删。 */
    private final HomeLayoutService homeLayouts;

    AccountDeletionService(UserMapper users,
                           RefreshTokenMapper refreshTokens,
                           PasswordHasher hasher,
                           ApplicationEventPublisher events,
                           io.github.xiaou61.account.internal.profile.LearnerProfileService profiles,
                           HomeLayoutService homeLayouts) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.hasher = hasher;
        this.events = events;
        this.profiles = profiles;
        this.homeLayouts = homeLayouts;
    }

    /**
     * 自助注销。
     *
     * <p><b>确认方式取决于账号有没有密码</b>（WORK-037）：第三方注册的账号从来没有密码
     * （见 {@code OAuthLoginService}），要求密码就等于让这批人永远注销不了自己的账号——
     * 而注销是「不可恢复」的，两条叠加会造出一块删不掉的数据。
     *
     * <p>没有密码时**仍然要求一次显式确认**（{@code confirmed = true}）。这不是形式主义：
     * 注销只凭一个被偷走的令牌就能清空一个人的全部数据，代价不对等。有密码的账号路
     * 保持原样，一个字没放松。
     *
     * @param confirmed 无密码账号必填 true；有密码账号忽略此参数
     */
    @Transactional
    public void delete(long userId, String rawPassword, boolean confirmed) {
        User user = users.findById(userId).orElseThrow(() -> notFound());
        if (user.getPasswordHash() == null) {
            if (!confirmed) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT,
                        "注销不可恢复，请先确认。此账号未设置密码，因此以显式确认为凭据。");
            }
            deleteCascade(userId);
            return;
        }
        if (rawPassword == null || !hasher.matches(rawPassword, user.getPasswordHash())) {
            // 与"账号不存在"用同一条消息不合适（这里身份是已知的），但也不回显任何账号信息
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "密码不正确");
        }
        deleteCascade(userId);
    }

    /**
     * 管理员删号（WORK-030）：**不校验对方的密码**。
     *
     * <p>为什么要这条路径：自助注销要求密码是对的（不可逆的操作得凭据），而管理员拿不到
     * 别人的密码。两条路径**共用同一段级联** {@link #deleteCascade(long)}——不许复制一份，
     * 否则以后加表只会改一处、漏一处。
     */
    @Transactional
    public void deleteByAdmin(long userId) {
        if (users.findById(userId).isEmpty()) {
            throw notFound();
        }
        deleteCascade(userId);
    }

    /** 唯一的级联实现。两个删除入口都走它。 */
    private void deleteCascade(long userId) {
        refreshTokens.deleteByUser(userId);
        // 画像与本模块同库同域（WORK-010）：直接删，不必绕一圈事件。
        // 其它模块的用户数据仍由 UserDeletedEvent 各自删除（方向是单向的）。
        profiles.deleteByUser(userId);
        homeLayouts.deleteByUser(userId);
        users.deleteById(userId);
        // 事务提交前由各模块监听并删掉自己的数据（见 UserDeletedEvent 的说明）
        events.publishEvent(new UserDeletedEvent(userId));
    }

    private static BizException notFound() {
        return new BizException(ErrorCode.NOT_FOUND, "账号不存在");
    }
}
