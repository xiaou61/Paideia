package io.github.xiaou61.account.internal;

import io.github.xiaou61.account.AccountDirectory;
import io.github.xiaou61.account.internal.user.UserMapper;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** {@link AccountDirectory} 的实现。只读、只返回用户名。 */
@Component
class AccountDirectoryImplementation implements AccountDirectory {

    private final UserMapper users;

    AccountDirectoryImplementation(UserMapper users) {
        this.users = users;
    }

    @Override
    public Map<Long, String> usernames(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new LinkedHashMap<>();
        for (UserMapper.UsernameRow row : users.findUsernames(ids)) {
            result.put(row.id(), row.username());
        }
        return Map.copyOf(result);
    }
}
