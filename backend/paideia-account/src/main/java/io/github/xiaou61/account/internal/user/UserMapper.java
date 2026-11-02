package io.github.xiaou61.account.internal.user;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 账号表访问。
 *
 * <p>SQL 只写在这里（沿用本项目 {@code @Mapper} + 注解/文本块的写法，不用 XML）。
 * 唯一性由数据库的唯一索引兜底，这里的查询只用于给出**友好的**错误提示；
 * 并发插入时靠捕获唯一索引冲突，而不是"先查再插"这种有竞态的写法。
 */
@Mapper
public interface UserMapper {

    @Insert("""
            INSERT INTO users (username, email, password_hash, role, email_verified_at)
            VALUES (#{username}, #{email}, #{passwordHash}, #{role}, #{emailVerifiedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    /**
     * 按用户名或邮箱查。两者都唯一，所以"用哪个标识登录"都能落到唯一索引上。
     *
     * <p>用两次单列查询，而不是 {@code username = #{identifier} OR email = #{identifier}}：
     * 跨两列的 OR 用不上任何一个唯一索引（随用户数退化成全表扫），而且当 A 的用户名恰好等于
     * B 的邮箱时，一条语句返回哪一行并不确定。分开查则顺序明确：先用户名，再邮箱。
     *
     * <p>库的排序规则是大小写不敏感的（{@code utf8mb4_0900_ai_ci}），因此
     * {@code Alice} 与 {@code alice} 会命中同一条记录。
     */
    default Optional<User> findByIdentifier(String identifier) {
        return findByUsername(identifier).or(() -> findByEmail(identifier));
    }

    @Select("""
            SELECT id, username, email, password_hash, role, email_verified_at
            FROM users WHERE username = #{username}
            """)
    Optional<User> findByUsername(@Param("username") String username);

    @Select("""
            SELECT id, username, email, password_hash, role, email_verified_at
            FROM users WHERE email = #{email}
            """)
    Optional<User> findByEmail(@Param("email") String email);

    @Select("SELECT id, username, email, password_hash, role, email_verified_at FROM users WHERE id = #{id}")
    Optional<User> findById(@Param("id") Long id);

    /** 注销。级联删除靠应用内事件（见 {@code UserDeletedEvent}），不建跨模块外键。 */
    @Delete("DELETE FROM users WHERE id = #{id}")
    int deleteById(@Param("id") long id);

    @Select("SELECT role FROM users WHERE id = #{id}")
    Optional<String> findRoleById(@Param("id") Long id);

    /*
     * 管理端查询（WORK-030）。**单独开，不复用上面那三条**：那三条是登录链路在用的，
     * 它们会带出 password_hash；管理端不需要它，而"改那三条的列清单"会让每次登录都经过
     * 一份被管理端需求推动过的 SQL。这里只选管理端真需要的列，hash 从查询层面就不存在。
     */

    /** 管理端分页查询的行视图。字段名与 SQL 选择列表一致。 */
    final class AdminRow {
        private Long id;
        private String username;
        private String email;
        private String role;
        private java.time.Instant createdAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public java.time.Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(java.time.Instant createdAt) { this.createdAt = createdAt; }
    }

    /**
     * 分页查账号。`query` 与 `role` 都可空。
     *
     * <p>模糊匹配用 `LIKE CONCAT('%', #{query}, '%')` 而**不在 Java 里拼百分号**：拼接会把
     * 用户输入里的 `%` 与 `_` 当成通配符，而这里它们是普通字符。
     *
     * <p>排序**不接受调用方传来的列名**：`sort` 只用于在几种写死的顺序之间选，
     * 非 name 的排序由 service 层另走一条路径（先按 ai 侧排序再取账号）。
     */
    @Select("""
            <script>
            SELECT id, username, email, role, created_at
            FROM users
            <where>
              <if test="query != null and query != ''">
                (username LIKE CONCAT('%', #{query}, '%') OR email LIKE CONCAT('%', #{query}, '%'))
              </if>
              <if test="role != null and role != ''">
                AND role = #{role}
              </if>
            </where>
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AdminRow> pageAdmin(@Param("query") String query,
                             @Param("role") String role,
                             @Param("limit") int limit,
                             @Param("offset") long offset);

    /** 与 {@link #pageAdmin} 同一套过滤条件的计数。 */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM users
            <where>
              <if test="query != null and query != ''">
                (username LIKE CONCAT('%', #{query}, '%') OR email LIKE CONCAT('%', #{query}, '%'))
              </if>
              <if test="role != null and role != ''">
                AND role = #{role}
              </if>
            </where>
            </script>
            """)
    long countAdmin(@Param("query") String query, @Param("role") String role);

    /** 管理端按 id 读一个账号。同样不带 hash。 */
    @Select("SELECT id, username, email, role, created_at FROM users WHERE id = #{id}")
    Optional<AdminRow> findAdminById(@Param("id") Long id);

    /** 改角色。返回受影响行数，0 表示账号不存在。 */
    @Update("UPDATE users SET role = #{role}, updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int updateRole(@Param("id") long id, @Param("role") String role);

    /**
     * 批量读用户名（WORK-060，社区内容展示用）。
     *
     * <p>**只选 id 与 username**：邮箱、角色、口令摘要从查询层面就不存在，避免调用方
     * 顺手把整行带出去。空集合由调用方短路，不把 `IN ()` 发给 MySQL。
     */
    @Select("""
            <script>
            SELECT id, username FROM users WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
              #{id}
            </foreach>
            </script>
            """)
    List<UsernameRow> findUsernames(@Param("ids") java.util.Collection<Long> ids);

    /** 用户名目录的一行。 */
    record UsernameRow(Long id, String username) {
    }
}
