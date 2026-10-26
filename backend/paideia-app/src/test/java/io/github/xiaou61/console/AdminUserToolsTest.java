package io.github.xiaou61.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.account.AccountAdminApi;
import io.github.xiaou61.ai.AiAdminApi;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 账号与积分工具的不变量与自伤防护（WORK-038 TASK-003）。
 */
class AdminUserToolsTest {

    private static final ToolContext ADMIN =
            new ToolContext(1L, "admin", Set.of(ToolScope.ADMIN), ToolContext.Origin.EXTERNAL);

    private final AccountAdminApi accounts = mock(AccountAdminApi.class);
    private final AiAdminApi ai = mock(AiAdminApi.class);

    @Test
    void allUserToolsAreAdminScopedAndPrefixed() throws Exception {
        List<ToolDescriptor> descriptors = new ArrayList<>();
        for (Class<?> type : AdminUserTools.class.getDeclaredClasses()) {
            if (!PaideiaTool.class.isAssignableFrom(type)) {
                continue;
            }
            var constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object[] args = new Object[constructor.getParameterCount()];
            descriptors.add(((PaideiaTool) constructor.newInstance(args)).descriptor());
        }

        assertThat(descriptors).hasSize(6);
        Set<String> names = new HashSet<>();
        for (ToolDescriptor descriptor : descriptors) {
            assertThat(descriptor.name()).startsWith(descriptor.toolset() + "_");
            assertThat(descriptor.toolset()).isEqualTo("admin_users");
            assertThat(descriptor.scope()).isEqualTo(ToolScope.ADMIN);
            assertThat(descriptor.readOnly() && descriptor.destructive()).isFalse();
            assertThat(names.add(descriptor.name())).as("重名：" + descriptor.name()).isTrue();
        }
        assertThat(descriptors).filteredOn(ToolDescriptor::destructive).hasSize(1);
        assertThat(descriptors).filteredOn(ToolDescriptor::readOnly).hasSize(3);
    }

    @Test
    void cannotAdjustOwnCredits() {
        ToolResult result = new AdminUserTools.AdjustCredits(ai).call(ADMIN,
                Map.of("targetUserId", 1, "delta", 100, "note", "补偿"));

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("自己");
        verify(ai, org.mockito.Mockito.never()).adjust(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void adjustRequiresNoteAndNonZeroDelta() {
        assertThat(new AdminUserTools.AdjustCredits(ai).call(ADMIN,
                Map.of("targetUserId", 2, "delta", 100, "note", "  ")).error()).isTrue();
        assertThat(new AdminUserTools.AdjustCredits(ai).call(ADMIN,
                Map.of("targetUserId", 2, "delta", 0, "note", "补偿")).text()).contains("不能为 0");
    }

    @Test
    void adjustReturnsNewBalance() {
        when(ai.adjust(2L, 500L, 1L, "活动奖励")).thenReturn(1500L);

        ToolResult result = new AdminUserTools.AdjustCredits(ai).call(ADMIN,
                Map.of("targetUserId", 2, "delta", 500, "note", "活动奖励"));

        assertThat(result.error()).isFalse();
        assertThat(result.text()).contains("+500").contains("1500");
    }

    @Test
    void listMergesUsageIntoRows() {
        when(accounts.list("ali", null, 1, 20)).thenReturn(
                new io.github.xiaou61.platform.PageResult<>(1, 1, 20, List.of(
                        new AccountAdminApi.AdminUser(2L, "alice", "a@example.com", "learner", "2026-10-01"))));
        when(ai.usage(List.of(2L))).thenReturn(List.of(
                new AiAdminApi.UserUsage(2L, 900L, 100L, 7L, "2026-10-25T10:00:00Z")));

        ToolResult result = new AdminUserTools.ListUsers(accounts, ai).call(ADMIN,
                Map.of("query", "ali"));

        assertThat(result.error()).isFalse();
        assertThat(result.text())
                .contains("alice")
                .contains("余额=900")
                .contains("已用=100")
                .contains("调用=7");
    }

    @Test
    void badInstantIsReportedNotThrown() {
        ToolResult result = new AdminUserTools.CreditLedger(ai).call(ADMIN,
                Map.of("from", "昨天"));

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("ISO-8601");
    }
}
