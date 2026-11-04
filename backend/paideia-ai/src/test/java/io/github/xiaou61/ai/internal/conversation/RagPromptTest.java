package io.github.xiaou61.ai.internal.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.ai.AssistantConversation;
import io.github.xiaou61.ai.Harness;
import java.util.List;
import org.junit.jupiter.api.Test;

class RagPromptTest {

    @Test
    void knowledgeContextIsInjectedBeforeMemoryAndKeepsEntryCitation() {
        AssistantConversation.Request request = new AssistantConversation.Request(
                1L, List.of(), "并发怎么理解", null, null, "", List.of(),
                Harness.ASSISTANT, null, "参考条目 [[entry:42]]《并发》\n线程安全");

        String prompt = AgentScopeConversation.systemPrompt(request);

        assertThat(prompt).contains("参考资料");
        assertThat(prompt).contains("[[entry:42]]");
    }
}
