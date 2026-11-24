package io.github.xiaou61.note.internal.share;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 分享的读写。
 *
 * <p>两条不对称的归属纪律：
 * <ul>
 *   <li><b>读分享内容不带 user_id 条件</b>——那是免鉴权端点，访问者没有身份。它的安全性靠
 *       "share_id 不可枚举"，与文件端点同构。</li>
 *   <li><b>撤回与查自己的分享必须带 user_id</b>：撤回别人的分享不该可能。</li>
 * </ul>
 *
 * <p>失效判定写在 SQL 里（{@code revoked_at IS NULL AND expires_at > now}）：读取时判，
 * 过期与撤回在调用方看来都是"不存在"，不需要定时清理任务。
 */
@Mapper
interface NoteShareMapper {

    @Insert("""
            INSERT INTO note_shares (share_id, note_id, target_type, target_id, user_id, expires_at)
            VALUES (#{shareId}, #{noteId}, 'note', #{noteId}, #{userId}, #{expiresAt})
            """)
    int insert(@Param("shareId") String shareId,
               @Param("noteId") long noteId,
               @Param("userId") long userId,
               @Param("expiresAt") Instant expiresAt);

    /** 整库分享：没有单篇 note_id，只有 target_type='book' 与 target_id=bookId。 */
    @Insert("""
            INSERT INTO note_shares (share_id, note_id, target_type, target_id, user_id, expires_at)
            VALUES (#{shareId}, NULL, 'book', #{bookId}, #{userId}, #{expiresAt})
            """)
    int insertBook(@Param("shareId") String shareId,
                   @Param("bookId") long bookId,
                   @Param("userId") long userId,
                   @Param("expiresAt") Instant expiresAt);

    /**
     * 读一条**仍然有效**的分享连同笔记正文。
     *
     * <p>一次 join 取回，不给"先查分享、再查笔记"留出中间窗口。产物里**没有** user_id、
     * 没有记忆、没有标记——那三样都不在这条 SQL 的 SELECT 里，所以不可能漏出去。
     */
    @Select("""
            SELECT n.title, n.body, n.entry_id
            FROM note_shares s
            JOIN notes n ON n.id = s.target_id AND s.target_type = 'note'
            WHERE s.share_id = #{shareId}
              AND s.revoked_at IS NULL
              AND s.expires_at > #{now}
            """)
    Optional<SharedNote> findValid(@Param("shareId") String shareId, @Param("now") Instant now);

    /**
     * 读一条**仍然有效**的整库分享，返回库名与分享者（用于限定遍历范围）。
     *
     * <p>同样一次 join 取回；这里多取 {@code user_id} 不是为了展示，而是让后续"遍历哪些文档"
     * 被钉死在分享者本人，杜绝读到别人在同一 bookId 下的数据。
     */
    @Select("""
            SELECT b.id AS book_id, b.name AS name, s.user_id AS user_id
            FROM note_shares s
            JOIN note_books b ON b.id = s.target_id AND s.target_type = 'book'
            WHERE s.share_id = #{shareId}
              AND s.revoked_at IS NULL
              AND s.expires_at > #{now}
            """)
    Optional<SharedBook> findValidBook(@Param("shareId") String shareId, @Param("now") Instant now);

    /**
     * 某条笔记**当前有效**的分享 id（本人）。
     *
     * <p>没有它，"提前撤回"就只兑现一半：链接只在刚生成的那一刻存在于界面上，用户离开页面
     * 就再也找不到自己分享过什么、也就撤不回来。
     */
    @Select("""
            SELECT share_id
            FROM note_shares
            WHERE note_id = #{noteId} AND user_id = #{userId}
              AND revoked_at IS NULL
              AND expires_at > #{now}
            ORDER BY created_at DESC
            """)
    List<String> findActiveForNote(@Param("noteId") long noteId,
                                   @Param("userId") long userId,
                                   @Param("now") Instant now);

    /** 某个知识库当前有效的分享 id（本人）。 */
    @Select("""
            SELECT share_id
            FROM note_shares
            WHERE target_type = 'book' AND target_id = #{bookId} AND user_id = #{userId}
              AND revoked_at IS NULL
              AND expires_at > #{now}
            ORDER BY created_at DESC
            """)
    List<String> findActiveForBook(@Param("bookId") long bookId,
                                   @Param("userId") long userId,
                                   @Param("now") Instant now);

    /** 撤回（自己那条）。返回 0 表示不存在、不是本人的、或已经失效。 */
    @Update("""
            UPDATE note_shares
            SET revoked_at = #{now}
            WHERE share_id = #{shareId} AND user_id = #{userId} AND revoked_at IS NULL
            """)
    int revoke(@Param("shareId") String shareId, @Param("userId") long userId, @Param("now") Instant now);

    /** 管理员下架：不看归属，但同样只对还有效的行生效（幂等）。 */
    @Update("""
            UPDATE note_shares
            SET revoked_at = #{now}
            WHERE share_id = #{shareId} AND revoked_at IS NULL
            """)
    int revokeByAdmin(@Param("shareId") String shareId, @Param("now") Instant now);

    /** 分享给谁看的产物。**只有这三样**：标题、正文、所挂条目 id。 */
    class SharedNote {

        private String title;
        private String body;
        private Long entryId;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getBody() {
            return body;
        }

        public void setBody(String body) {
            this.body = body;
        }

        public Long getEntryId() {
            return entryId;
        }

        public void setEntryId(Long entryId) {
            this.entryId = entryId;
        }
    }

    /** 整库分享读取的最小投影：库 id、库名、分享者。 */
    class SharedBook {

        private Long bookId;
        private String name;
        private Long userId;

        public Long getBookId() {
            return bookId;
        }

        public void setBookId(Long bookId) {
            this.bookId = bookId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }
    }
}
