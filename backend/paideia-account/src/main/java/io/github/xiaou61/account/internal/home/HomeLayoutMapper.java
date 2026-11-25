package io.github.xiaou61.account.internal.home;

import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 首页布局存取（WORK：首页自定义）。每用户一行，`layout_json` 是不透明 JSON 文本，
 * 结构由前端拥有，服务端不解析业务字段。
 */
@Mapper
public interface HomeLayoutMapper {

    @Select("SELECT layout_json FROM user_home_layout WHERE user_id = #{userId}")
    Optional<String> findJson(@Param("userId") long userId);

    /** 时间戳用库端 UTC_TIMESTAMP，省掉 Instant→DATETIME 的 TypeHandler 依赖。 */
    @Insert("""
            INSERT INTO user_home_layout (user_id, layout_json, updated_at)
            VALUES (#{userId}, #{layoutJson}, UTC_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE layout_json = VALUES(layout_json), updated_at = UTC_TIMESTAMP(3)
            """)
    int upsert(@Param("userId") long userId, @Param("layoutJson") String layoutJson);

    /** 注销级联用（清单在 {@code AccountDeletionCascadeIntegrationTest} 中被逐表断言）。 */
    @Delete("DELETE FROM user_home_layout WHERE user_id = #{userId}")
    int deleteByUser(@Param("userId") long userId);
}
